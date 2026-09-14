package creeper_knc;

import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VirtualSignDetectionBridge implements Listener {

    private static final int PAGE_SIZE = 4;
    private static final long OPEN_DELAY_TICKS = 40L;
    private static final long OPEN_SIGN_DELAY_TICKS = 1L;
    private static final long NEXT_PAGE_DELAY_TICKS = 10L;
    /** How often to look again while the player has something else on screen. */
    private static final long SCREEN_RECHECK_TICKS = 20L;
    /**
     * Backoff before reopening after a round the client never answered. Index = rounds failed so
     * far, clamped to the last entry. Giving up after one silent round would mean a client that
     * simply stalls past the response timeout is never checked again.
     */
    private static final long[] RETRY_BACKOFF_TICKS = {20L * 10, 20L * 30, 20L * 60, 20L * 120};
    private static final int DEFAULT_MAX_ATTEMPTS = 4;
    private static final int DEFAULT_EVADE_SECONDS = 300;

    private final FakeModBlocker plugin;
    private final ModBlocker parent;
    private FileConfiguration config;
    private final Map<UUID, DetectSession> detectSessions = new ConcurrentHashMap<>();
    /**
     * "This player still owes us a check", kept across rounds.
     *
     * <p>A DetectSession is one sign being opened and dies whenever a page times out or the screen
     * is busy; this map is what remembers that the player was never actually checked. Without it,
     * anything that made a round fail also silently excused the player for the whole session.
     */
    private final Map<UUID, PendingDetect> pendingDetects = new ConcurrentHashMap<>();
    private final Map<UUID, Inventory> flashInventories = new ConcurrentHashMap<>();
    private final List<LineEntry> lineEntries = new ArrayList<>();

    public VirtualSignDetectionBridge(FakeModBlocker plugin, ModBlocker parent) {
        this.plugin = plugin;
        this.parent = parent;
        this.config = plugin.getConfig();
        reload();
    }

    public void reload() {
        this.config = plugin.getConfig();
        lineEntries.clear();

        ConfigurationSection root = config.getConfigurationSection("extra-detections.sign-translation.mods");
        if (root == null) {
            return;
        }

        for (String modName : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(modName);
            if (section == null) {
                continue;
            }

            ConfigurationSection detectSec = section.getConfigurationSection("detect");
            ConfigurationSection punishmentSec = section.getConfigurationSection("punishment");

            ConfigurationSection keySource = detectSec != null ? detectSec : section;
            List<String> keys = ModBlocker.readKeys(keySource);

            String actionRaw;
            String reason;
            String duration;
            boolean escalation;

            if (detectSec != null || punishmentSec != null) {
                actionRaw = punishmentSec != null ? punishmentSec.getString("action", "NOTICE") : "NOTICE";
                reason = punishmentSec != null ? punishmentSec.getString("reason") : null;
                duration = punishmentSec != null ? punishmentSec.getString("duration") : null;
                escalation = punishmentSec == null || punishmentSec.getBoolean("escalation", true);
            } else {
                actionRaw = section.getString("action", "NOTICE");
                reason = section.getString("reason");
                duration = section.getString("duration");
                escalation = section.getBoolean("escalation", true);
            }

            if (keys.isEmpty()) {
                continue;
            }

            ModBlocker.DetectionAction action;
            try {
                action = ModBlocker.DetectionAction.valueOf(actionRaw.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                action = ModBlocker.DetectionAction.NOTICE;
            }

            ModBlocker.DetectionModConfig mod =
                    new ModBlocker.DetectionModConfig(modName, keys, action, reason, duration, escalation);
            for (String k : keys) {
                lineEntries.add(new LineEntry(mod, k));
            }
        }
    }

    public void shutdown() {
        for (Map.Entry<UUID, DetectSession> entry : new HashMap<>(detectSessions).entrySet()) {
            UUID uuid = entry.getKey();
            DetectSession session = entry.getValue();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                cleanup(uuid);
                continue;
            }

            restoreClientBlock(player, session.signLocation);
            cleanup(uuid);
        }
    }

    public void openSignCheckLater(Player player) {
        if (player.hasPermission("fakemodblocker.bypass")) {
            // Leave early so an exempt player never triggers a round, nor an "unchecked" warning.
            return;
        }
        pendingDetects.put(player.getUniqueId(), new PendingDetect());
        armDetection(player, OPEN_DELAY_TICKS);
    }

    /**
     * Queue one round. The chain keeps itself alive for as long as {@link #pendingDetects} still
     * owes a check, so the player either gets checked or leaves - waiting it out is not an option.
     */
    private void armDetection(Player player, long delayTicks) {
        UUID uuid = player.getUniqueId();
        plugin.getScheduler().runDelayed(player, Math.max(1L, delayTicks), () -> {
            if (!player.isOnline()) {
                cleanup(uuid);
                pendingDetects.remove(uuid);
                ScreenGate.forget(uuid);
                return;
            }
            if (!pendingDetects.containsKey(uuid)) {
                return; // already checked
            }
            if (detectSessions.containsKey(uuid)) {
                return; // a round is already running
            }

            if (parent.shouldSkipSignDetectionForBedrock(player)) {
                if (config.getBoolean("logger")) {
                    parent.logToConsole("Skipped delayed virtual sign detection for Bedrock player via Floodgate: " + player.getName());
                }
                pendingDetects.remove(uuid);
                return;
            }

            detectSessions.put(
                    uuid,
                    new DetectSession(player.getLocation().getBlock().getLocation().add(0.0, -5.0, 0.0), 0)
            );
            openDetectionSign(player);
        });
    }

    /** Checked for real: hit, all pages walked, or exempt. Stop owing a round. */
    private void finishDetection(UUID uuid) {
        pendingDetects.remove(uuid);
        cleanup(uuid);
    }

    /**
     * The round produced nothing usable, but the debt stands: reopen after a backoff.
     * Only once the attempts run out is this reported as an unfinished check.
     */
    private void retryRound(Player player, String why) {
        UUID uuid = player.getUniqueId();
        cleanup(uuid);

        PendingDetect pending = pendingDetects.get(uuid);
        if (pending == null || !player.isOnline()) {
            return;
        }

        int failed = pending.recordFailedRound();
        if (failed >= maxAttempts()) {
            pendingDetects.remove(uuid);
            onDetectionEvaded(player, why + " (no response after " + failed + " rounds)");
            return;
        }

        long delay = RETRY_BACKOFF_TICKS[Math.min(failed - 1, RETRY_BACKOFF_TICKS.length - 1)];
        if (config.getBoolean("logger")) {
            parent.logToConsole("Sign detection for " + player.getName() + " produced no response ("
                    + why + "); retrying in " + (delay / 20) + "s (round " + (failed + 1) + ").");
        }
        armDetection(player, delay);
    }

    /**
     * Could not finish the check. Reporting only by default: a stuck resource pack prompt, a slow
     * client or a bad connection all land here, and kicking for those does more damage than this
     * symbolic check ever prevents. Admins who want it strict can set evade-action to KICK.
     */
    private void onDetectionEvaded(Player player, String why) {
        parent.logToConsole("Player " + player.getName() + " never completed the sign check: " + why);
        parent.notifyStaff("Player " + player.getName() + " did not complete the mod check (" + why + ")");

        String action = config.getString("extra-detections.sign-translation.evade-action", "NOTICE");
        if (!"KICK".equalsIgnoreCase(action) || !player.isOnline()
                || player.hasPermission("fakemodblocker.bypass")) {
            return;
        }
        MessageBridge.kick(player, parent.getMessage("sign.evade-kick",
                "&c&lCheck could not be completed&r\n&7Close any open screen and rejoin."));
    }

    private int maxAttempts() {
        return Math.max(1, config.getInt("extra-detections.sign-translation.max-attempts", DEFAULT_MAX_ATTEMPTS));
    }

    private long evadeTimeoutTicks() {
        return Math.max(1, config.getInt("extra-detections.sign-translation.evade-timeout-seconds",
                DEFAULT_EVADE_SECONDS)) * 20L;
    }

    /** Called by {@link ModBlocker} so a leaving player does not leave state behind. */
    public void onPlayerQuit(UUID uuid) {
        detectSessions.remove(uuid);
        pendingDetects.remove(uuid);
        flashInventories.remove(uuid);
        ScreenGate.forget(uuid);
    }

    private void openDetectionSign(Player player) {
        UUID uuid = player.getUniqueId();
        DetectSession session = detectSessions.get(uuid);
        if (session == null || !player.isOnline()) {
            cleanup(uuid);
            return;
        }

        // A round scheduled before the player finished must not start a new one afterwards.
        if (!pendingDetects.containsKey(uuid)) {
            cleanup(uuid);
            return;
        }

        if (lineEntries.isEmpty()) {
            finishDetection(uuid);
            return;
        }

        int page = session.page;
        int start = page * PAGE_SIZE;
        if (start >= lineEntries.size()) {
            restoreClientBlock(player, session.signLocation);
            endRound(player, "all pages walked");
            return;
        }

        // openVirtualSign is setScreen() on the client, so opening now would throw away a resource
        // pack prompt or a dialog and the player would never get to answer it. Wait instead.
        //
        // There is deliberately no wait limit. An upper bound here would read as "hold any GUI open
        // long enough and this session is never checked" - the player has to close it to play, and
        // the poll picks the check right back up the second they do.
        if (avoidOpenScreens() && ScreenGate.hasScreenOpen(player)) {
            PendingDetect pending = pendingDetects.get(uuid);
            if (pending != null) {
                long waited = pending.addScreenWait(SCREEN_RECHECK_TICKS);
                if (!pending.isEvadeReported() && waited >= evadeTimeoutTicks()) {
                    pending.markEvadeReported();
                    onDetectionEvaded(player, "a screen has been open for " + (waited / 20)
                            + "s, the check cannot start");
                }
            }
            if (!player.isOnline()) {
                cleanup(uuid);
                return;
            }
            plugin.getScheduler().runDelayed(player, SCREEN_RECHECK_TICKS, () -> {
                if (!player.isOnline()) {
                    cleanup(uuid);
                    return;
                }
                openDetectionSign(player);
            });
            return;
        }

        session.openToken++;
        final int token = session.openToken;
        session.waitingResponse = true;
        int end = Math.min(start + PAGE_SIZE, lineEntries.size());

        Location signLocation = session.signLocation;

        if (config.getBoolean("logger")) {
            parent.logToConsole("Opening virtual sign detection for " + player.getName()
                    + " page " + (page + 1)
                    + " range [" + start + ", " + (end - 1) + "]");
        }

        plugin.getScheduler().runMain(signLocation, () -> {
            try {
                if (!player.isOnline()) {
                    cleanup(uuid);
                    return;
                }

                BlockData signBlockData = Material.OAK_WALL_SIGN.createBlockData(data -> {
                    WallSign wallSign = (WallSign) data;
                    wallSign.setFacing(player.getFacing().getOppositeFace());
                });
                player.sendBlockChange(signLocation, signBlockData);

                Sign virtualSign = (Sign) signBlockData.createBlockState();
                virtualSign.getSide(Side.BACK).setColor(DyeColor.BLACK);
                virtualSign.getSide(Side.BACK).setGlowingText(false);
                virtualSign.getSide(Side.FRONT).setColor(DyeColor.BLACK);
                virtualSign.getSide(Side.FRONT).setGlowingText(false);

                for (int i = 0; i < PAGE_SIZE; i++) {
                    int configIndex = start + i;
                    if (configIndex >= end) {
                        virtualSign.getSide(Side.BACK).line(i, Component.empty());
                        continue;
                    }

                    LineEntry entry = lineEntries.get(configIndex);
                    virtualSign.getSide(Side.BACK).line(
                            i,
                            Component.text("[FSM_T" + token + "_" + i + "] ").append(Component.translatable(entry.key))
                    );
                }

                player.sendBlockUpdate(signLocation, virtualSign);

                plugin.getScheduler().runDelayed(player, OPEN_SIGN_DELAY_TICKS, () -> {
                    if (!player.isOnline()) {
                        restoreClientBlock(player, signLocation);
                        cleanup(uuid);
                        return;
                    }

                    try {
                        player.openVirtualSign(
                                Position.block(signLocation.getBlockX(), signLocation.getBlockY(), signLocation.getBlockZ()),
                                Side.BACK
                        );

                        // Taking the fake block away is what closes the editor: the client's
                        // SignBlockEntity goes invalid, AbstractSignEditScreen#tick notices and runs
                        // onDone() -> removed(), which unconditionally sends the text back. The text
                        // itself was snapshotted when the screen was built, so removing the block does
                        // not change what comes back.
                        //
                        // closeInventory() would also work, but it is clientSideCloseContainer() =
                        // setScreen(null) and therefore closes whatever the player has on screen -
                        // including a resource pack prompt they had not answered yet.
                        restoreClientBlock(player, signLocation);

                        plugin.getScheduler().runDelayed(player, 15L, () -> {
                            DetectSession latest = detectSessions.get(uuid);
                            if (latest == null || !player.isOnline()) {
                                return;
                            }

                            if (latest.openToken != token || !latest.waitingResponse) {
                                return;
                            }

                            latest.waitingResponse = false;

                            if (config.getBoolean("logger")) {
                                parent.logToConsole("Sign detection timeout fallback for " + player.getName()
                                        + " page " + (latest.page + 1));
                            }

                            restoreClientBlock(player, latest.signLocation);

                            int nextPage = latest.page + 1;
                            if (nextPage * PAGE_SIZE < lineEntries.size()) {
                                latest.page = nextPage;
                                plugin.getScheduler().runDelayed(player, NEXT_PAGE_DELAY_TICKS, () -> {
                                    if (!player.isOnline()) {
                                        cleanup(uuid);
                                        return;
                                    }
                                    openDetectionSign(player);
                                });
                            } else {
                                if (config.getBoolean("logger")) {
                                    parent.logToConsole("Sign detection completed with no match (timeout fallback).");
                                }
                                endRound(player, "last page timed out");
                            }
                        });

                    } catch (Throwable t) {
                        if (config.getBoolean("logger")) {
                            parent.logToConsole("Failed to open virtual sign for " + player.getName() + ": " + t.getMessage());
                        }
                        restoreClientBlock(player, signLocation);
                        retryRound(player, "could not open the virtual sign (" + t.getMessage() + ")");
                    }
                });
            } catch (Throwable t) {
                if (config.getBoolean("logger")) {
                    parent.logToConsole("openDetectionSign error for " + player.getName() + ": " + t.getMessage());
                }
                retryRound(player, "error while building the virtual sign (" + t.getMessage() + ")");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUncheckedSignChange(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!config.getBoolean("extra-detections.sign-translation.enabled", false)) {
            return;
        }

        DetectSession session = detectSessions.get(uuid);
        if (session == null) {
            return;
        }

        if (event.getEditedBlockPosition().blockX() != session.signLocation.getBlockX()
                || event.getEditedBlockPosition().blockY() != session.signLocation.getBlockY()
                || event.getEditedBlockPosition().blockZ() != session.signLocation.getBlockZ()) {
            return;
        }

        if (!session.waitingResponse) {
            return;
        }

        List<String> plainLines = new ArrayList<>();
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        for (Component line : event.lines()) {
            plainLines.add(serializer.serialize(line));
        }

        int responseToken = extractMarkerToken(plainLines);
        if (responseToken >= 0 && responseToken != session.openToken) {
            event.setCancelled(true);
            restoreClientBlock(player, session.signLocation);
            if (config.getBoolean("logger")) {
                parent.logToConsole("Sign detection: discarded stale response (token "
                        + responseToken + " vs current " + session.openToken + ") for " + player.getName());
            }
            return;
        }

        session.waitingResponse = false;
        event.setCancelled(true);

        // The client did answer, so this round is not a wash even if a later page times out.
        PendingDetect pending = pendingDetects.get(uuid);
        if (pending != null) {
            pending.markResponded();
        }

        if (!player.isOnline()) {
            cleanup(uuid);
            return;
        }

        if (player.hasPermission("fakemodblocker.bypass")) {
            restoreClientBlock(player, session.signLocation);
            finishDetection(uuid);
            return;
        }

        if (lineEntries.isEmpty()) {
            restoreClientBlock(player, session.signLocation);
            finishDetection(uuid);
            return;
        }

        if (config.getBoolean("logger")) {
            parent.logToConsole("Virtual sign returned: " + plainLines);
        }

        int start = session.page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, lineEntries.size());

        for (int lineIndex = 0; lineIndex < plainLines.size(); lineIndex++) {
            int configIndex = start + lineIndex;
            if (configIndex >= end) {
                continue;
            }

            LineEntry entry = lineEntries.get(configIndex);
            String plain = plainLines.get(lineIndex) == null ? "" : plainLines.get(lineIndex).trim();
            String marker = "[FSM_T" + session.openToken + "_" + lineIndex + "]";

            if (config.getBoolean("logger")) {
                parent.logToConsole("Sign check -> mod=" + entry.mod.getName()
                        + ", key=" + entry.key
                        + ", plainLine=" + plain
                        + ", translated=" + (plain.startsWith(marker) && !plain.contains(entry.key)));
            }

            if (plain.startsWith(marker) && !plain.contains(entry.key)) {
                if (config.getBoolean("logger")) {
                    parent.logToConsole("Sign detection hit: " + entry.mod.getName() + " | key=" + entry.key + " | content=" + plain);
                }
                restoreClientBlock(player, session.signLocation);
                finishDetection(uuid);
                parent.handleSignDetection(player, entry.mod);
                return;
            }
        }

        restoreClientBlock(player, session.signLocation);

        int nextPage = session.page + 1;
        if (nextPage * PAGE_SIZE < lineEntries.size()) {
            session.page = nextPage;

            if (config.getBoolean("logger")) {
                parent.logToConsole("Page " + (session.page) + " not matched. Continue page " + (nextPage + 1));
            }

            plugin.getScheduler().runDelayed(player, NEXT_PAGE_DELAY_TICKS, () -> {
                if (!player.isOnline()) {
                    cleanup(uuid);
                    return;
                }
                openDetectionSign(player);
            });
        } else {
            if (config.getBoolean("logger")) {
                parent.logToConsole("Sign detection completed with no match.");
            }
            endRound(player, "all pages checked");
        }
    }

    /**
     * A round reached its end. It only counts as done if the client answered at least one page -
     * a round where every page timed out proves nothing and has to be retried, otherwise a client
     * that never replies would quietly pass as clean.
     */
    private void endRound(Player player, String why) {
        UUID uuid = player.getUniqueId();
        PendingDetect pending = pendingDetects.get(uuid);
        if (pending != null && !pending.hasResponded()) {
            retryRound(player, why);
            return;
        }
        finishDetection(uuid);
    }

    private boolean avoidOpenScreens() {
        return config.getBoolean("extra-detections.sign-translation.avoid-open-screens", true);
    }

    private int extractMarkerToken(List<String> plainLines) {
        for (String line : plainLines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("[FSM_T")) {
                continue;
            }
            int closeBracket = trimmed.indexOf(']');
            if (closeBracket <= 6) {
                continue;
            }
            String inner = trimmed.substring(6, closeBracket);
            int underscore = inner.indexOf('_');
            if (underscore <= 0) {
                continue;
            }
            try {
                return Integer.parseInt(inner.substring(0, underscore));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private void restoreClientBlock(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null || !player.isOnline()) {
            return;
        }

        plugin.getScheduler().runMain(loc, () -> {
            if (!player.isOnline() || loc.getWorld() == null) {
                return;
            }
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                return;
            }
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        });
    }

    private void cleanup(UUID uuid) {
        detectSessions.remove(uuid);
        flashInventories.remove(uuid);
    }

    private static final class LineEntry {
        private final ModBlocker.DetectionModConfig mod;
        private final String key;

        private LineEntry(ModBlocker.DetectionModConfig mod, String key) {
            this.mod = mod;
            this.key = key;
        }
    }

    private static final class DetectSession {
        private final Location signLocation;
        private int page;
        private int openToken;
        private boolean waitingResponse;

        private DetectSession(Location signLocation, int page) {
            this.signLocation = signLocation;
            this.page = page;
            this.openToken = 0;
            this.waitingResponse = false;
        }
    }

    /**
     * The running tally of "has this player been checked yet", kept across rounds.
     *
     * <p>{@link DetectSession} is one sign opening and is thrown away whenever a page times out or
     * the screen is busy; this survives that, which is what stops a failed round from doubling as a
     * free pass.
     */
    private static final class PendingDetect {
        /** Rounds in a row where the client answered nothing. */
        private int failedRounds;
        /** Did the client answer any page of the current round? */
        private boolean responded;
        /** Ticks spent waiting for the player's screen to free up. */
        private long screenWaitedTicks;
        /** Report an unfinished check once, not once per poll. */
        private boolean evadeReported;

        private void markResponded() {
            responded = true;
        }

        private boolean hasResponded() {
            return responded;
        }

        /** Starts the next round from a clean slate and returns how many rounds have failed. */
        private int recordFailedRound() {
            responded = false;
            return ++failedRounds;
        }

        private long addScreenWait(long ticks) {
            screenWaitedTicks += ticks;
            return screenWaitedTicks;
        }

        private boolean isEvadeReported() {
            return evadeReported;
        }

        private void markEvadeReported() {
            evadeReported = true;
        }
    }
}