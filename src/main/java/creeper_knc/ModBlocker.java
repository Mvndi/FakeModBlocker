package creeper_knc;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@SuppressWarnings({"unchecked", "rawtypes"})
public class ModBlocker implements Listener, PluginMessageListener {

    private static final String FORGE_CHANNEL = "fml:hs";
    private static final String FABRIC_CHANNEL = "fabric:registry/sync";
    private static final String FORGE_CHANNEL_LEGACY = "fml:hsl";

    private FileConfiguration config = FakeModBlocker.getInstance().getConfig();
    private final List<DetectionModConfig> signDetectConfigs = new ArrayList<>();
    private final boolean signDetectionApiSupported;
    private final Set<UUID> handledChannelKick = ConcurrentHashMap.newKeySet();

    private Object signDetectionBridge;
    private Object packetEventsBridge;
    /** Optional: reports dialogs / resource pack prompts so sign detection can wait them out. */
    private Object screenTrackerBridge;
    private ViolationManager violationManager;

    /** Why the API check failed, or null when the API is usable. */
    private String signApiUnsupportedReason;
    /** Why the listener could not be registered, or null when the bridge is live. */
    private String signBridgeFailure;
    /** Keeps a broken bridge from spamming one warning per player join. */
    private boolean signBridgeWarned;
    /** Warn once per reload cycle when the feature is on but the server cannot run it. */
    private boolean signApiWarned;

    public ModBlocker() {
        loadSignDetectionConfigs();
        this.signDetectionApiSupported = detectSignDetectionApiSupport();

        this.violationManager = new ViolationManager(FakeModBlocker.getInstance(), this);

        syncSignDetectionBridge();
        syncPacketEventsBridge();
        syncScreenTrackerBridge();

        if (config.getBoolean("logger")) {
            logToConsole("Sign translation detection: " + describeSignDetectionState());
            logToConsole("Packet-level channel detection support: " + (packetEventsBridge != null));
        }
    }

    public void reloadModBlockerConfig() {
        this.config = FakeModBlocker.getInstance().getConfig();
        loadSignDetectionConfigs();

        if (violationManager != null) {
            violationManager.reload();
        }

        // Let a reload recover from a failed startup and follow enable/disable
        // toggles, instead of forcing a full server restart.
        signBridgeWarned = false;
        signApiWarned = false;
        syncSignDetectionBridge();
        syncPacketEventsBridge();
        syncScreenTrackerBridge();

        if (signDetectionBridge != null) {
            try {
                Method reloadMethod = signDetectionBridge.getClass().getMethod("reload");
                reloadMethod.invoke(signDetectionBridge);
            } catch (Throwable t) {
                if (config.getBoolean("logger")) {
                    logToConsole("Failed to reload sign detection bridge: " + t.getMessage());
                }
            }
        }

        if (config.getBoolean("logger")) {
            logToConsole("Configuration reloaded. Sign translation detection: " + describeSignDetectionState());
        }
    }

    public void shutdown() {
        disposeSignDetectionBridge();
        disposePacketEventsBridge();
        disposeScreenTrackerBridge();
        if (violationManager != null) {
            // Async tasks are rejected once the plugin is disabling, so write on this thread.
            violationManager.flush();
        }
        handledChannelKick.clear();
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public boolean shouldSkipSignDetectionForBedrock(Player player) {
        if (!config.getBoolean("extra-detections.sign-translation.skip-bedrock-via-floodgate", true)) {
            return false;
        }
        try {
            boolean apiResult = FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
            if (config.getBoolean("logger")) {
                logToConsole("Floodgate check for " + player.getName() + ": " + apiResult);
            }
            if (apiResult) {
                return true;
            }
        } catch (Throwable t) {
            if (config.getBoolean("logger")) {
                logToConsole("Floodgate API error for " + player.getName() + ": " + t.getMessage());
            }
        }

        boolean fallback = isLikelyFloodgateBedrock(player);
        if (config.getBoolean("logger") && fallback) {
            logToConsole("Floodgate fallback matched for " + player.getName()
                    + " (API=false, but prefix/UUID indicate Bedrock)");
        }
        return fallback;
    }

    private boolean isLikelyFloodgateBedrock(Player player) {
        String prefix = getFloodgatePrefix();
        if (prefix == null || prefix.isEmpty()) {
            prefix = ".";
        }

        return player.getName().startsWith(prefix) && isFloodgateStyleUuid(player.getUniqueId());
    }

    private String getFloodgatePrefix() {
        org.bukkit.plugin.Plugin floodgate = Bukkit.getPluginManager().getPlugin("Floodgate");
        if (floodgate == null) {
            floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        }
        if (!(floodgate instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin)) {
            return ".";
        }

        try {
            return javaPlugin.getConfig().getString("username-prefix", ".");
        } catch (Throwable ignored) {
            return ".";
        }
    }

    private boolean isFloodgateStyleUuid(UUID uuid) {
        return uuid != null && uuid.toString().toLowerCase(Locale.ROOT).startsWith("00000000-0000-0000-");
    }

    public boolean isExempt(Player player) {
        if (player == null) {
            return false;
        }
        ExemptManager exempt = FakeModBlocker.getInstance().getExemptManager();
        if (exempt != null && exempt.isExempt(player)) {
            return true;
        }
        return player.hasPermission("fakemodblocker.bypass");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        ExemptManager exempt = FakeModBlocker.getInstance().getExemptManager();
        if (exempt != null) {
            exempt.onPlayerJoin(player);
        }

        if (isExempt(player)) {
            if (config.getBoolean("logger")) {
                logToConsole("Skipping every check for exempt player " + player.getName() + ".");
            }
            return;
        }

        if (config.getBoolean("enable")) {
            FakeModBlocker.getInstance().getScheduler().runDelayed(player, 15L, () -> {
                if (player.isOnline()) {
                    checkForMods(player);
                }
            });
        }

        triggerSignDetection(player);
    }

    public boolean triggerSignDetection(Player player) {
        return startSignDetection(player) == SignDetectionState.STARTED;
    }

    /**
     * Same as {@link #triggerSignDetection(Player)}, but reports why the check did not run.
     * Every branch leaves a trace in console, so a skip is never silent.
     */
    public SignDetectionState startSignDetection(Player player) {
        if (isExempt(player)) {
            if (config.getBoolean("logger")) {
                logToConsole("Sign translation detection skipped for " + player.getName() + ": exempt.");
            }
            return SignDetectionState.EXEMPT;
        }

        if (!config.getBoolean("extra-detections.sign-translation.enabled", false)) {
            if (config.getBoolean("logger")) {
                logToConsole("Sign translation detection skipped for " + player.getName() + ": disabled in config.");
            }
            return SignDetectionState.DISABLED;
        }

        if (shouldSkipSignDetectionForBedrock(player)) {
            if (config.getBoolean("logger")) {
                logToConsole("Skipped sign translation detection for Bedrock player via Floodgate: " + player.getName());
            }
            return SignDetectionState.BEDROCK_SKIPPED;
        }

        if (!signDetectionApiSupported) {
            if (config.getBoolean("logger")) {
                logToConsole("Sign translation detection is enabled in config, but current server/API does not support it ("
                        + signApiUnsupportedReason + "). Skipped for " + player.getName());
            }
            return SignDetectionState.UNSUPPORTED;
        }

        if (signDetectionBridge == null) {
            // A broken install, not a configuration choice: warn once even when logger is off,
            // otherwise this state looks exactly like "disabled" from the console.
            String reason = signBridgeFailure != null ? signBridgeFailure : "bridge was never created";
            if (!signBridgeWarned) {
                signBridgeWarned = true;
                FakeModBlocker.getInstance().getLogger().warning("Sign translation detection is enabled, but its listener"
                        + " is not registered (" + reason + "). The check is being skipped for every player, starting with "
                        + player.getName() + ". Run /modblocker reload to retry.");
            } else if (config.getBoolean("logger")) {
                logToConsole("Sign translation detection skipped for " + player.getName()
                        + ": listener not registered (" + reason + ").");
            }
            return SignDetectionState.BRIDGE_UNAVAILABLE;
        }

        try {
            Method method = signDetectionBridge.getClass().getMethod("openSignCheckLater", Player.class);
            method.invoke(signDetectionBridge, player);
            return SignDetectionState.STARTED;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            FakeModBlocker.getInstance().getLogger().log(Level.WARNING,
                    "Failed to start sign detection for " + player.getName(), cause);
            return SignDetectionState.START_FAILED;
        }
    }

    private void checkForMods(Player player) {
        if (isExempt(player)) {
            return;
        }

        boolean flagged = false;
        List<String> detected = new ArrayList<>();
        List<String> forbidden = config.getStringList("forbiddenList");

        if (config.getBoolean("logger")) {
            String ch = String.join(", ", player.getListeningPluginChannels());
            logToConsole(getMessage("log.channel-list")
                    .replace("%player%", player.getName())
                    .replace("%channels%", ch));
        }

        for (String channel : player.getListeningPluginChannels()) {
            for (String keyword : forbidden) {
                if (channelMatchesKeyword(channel, keyword)) {
                    flagged = true;
                    if (!detected.contains(keyword)) {
                        detected.add(keyword);
                    }
                }
            }
        }

        if (flagged && !detected.isEmpty() && !player.hasPermission("fakemodblocker.kickbypass")) {
            handleDetectedMods(player, detected);
        }
    }

    private void handleDetectedMods(Player player, List<String> mods) {
        if (isExempt(player)) {
            return;
        }

        boolean escalating = violationManager != null && violationManager.isEnabled();

        // The fixed punishment fires once per session; escalation does its own per-mod
        // session bookkeeping, so it must not be swallowed by this guard.
        if (!escalating && !handledChannelKick.add(player.getUniqueId())) {
            return;
        }

        logToConsole(getMessage("log.detected-mods")
                .replace("%player%", player.getName())
                .replace("%mods%", String.join(", ", mods)));

        if (escalating) {
            if (violationManager.handle(player, mods, ViolationManager.SOURCE_CHANNEL, null)) {
                return;
            }
            // Ladder unusable: fall back to the fixed punishment, still only once per session.
            if (!handledChannelKick.add(player.getUniqueId())) {
                return;
            }
        }

        StringBuilder message = new StringBuilder();
        for (String mod : mods) {
            String path = "kick.mods." + mod.toLowerCase(Locale.ROOT);
            FileConfiguration messages = FakeModBlocker.getInstance().getMessages();
            String msg = messages.contains(path)
                    ? messages.getString(path)
                    : messages.getString("kick.reason-default");

            if (msg == null) {
                msg = "&c[ModBlocker] Kick reason not defined.";
            }

            message.append(msg.replace("%player%", player.getName())).append("\n");
        }

        kickWithReason(player, message.toString().trim());
    }

    void handleSignDetection(Player player, DetectionModConfig detectConfig) {
        if (isExempt(player)) {
            return;
        }

        String reason = detectConfig.getReason() != null
                ? detectConfig.getReason()
                : "&cDetected forbidden mod: " + detectConfig.getName();

        // NOTICE and IGNORE stay observational: turning escalation on must never
        // upgrade a mod the admin only wanted to watch into a kick or a ban.
        if (detectConfig.getAction() == DetectionAction.KICK || detectConfig.getAction() == DetectionAction.BAN) {
            if (violationManager != null
                    && violationManager.isEnabled()
                    && detectConfig.isEscalationEnabled()
                    && violationManager.handle(player, List.of(detectConfig.getName()),
                            ViolationManager.SOURCE_SIGN, reason)) {
                return;
            }
        }

        switch (detectConfig.getAction()) {
            case NOTICE:
                if (config.getBoolean("logger")) {
                    logToConsole("Sign detection found " + player.getName() + " using " + detectConfig.getName());
                }
                notifyStaff("Player " + player.getName() + " may be using " + detectConfig.getName());
                break;

            case KICK:
                if (config.getBoolean("logger")) {
                    logToConsole("Sign detection found " + player.getName() + " using " + detectConfig.getName() + ", kicking player.");
                }

                notifyStaff("Player " + player.getName() + " was kicked for using " + detectConfig.getName());

                kickWithReason(player, reason);
                break;

            case BAN:
                Date expires = parseDuration(detectConfig.getDuration());

                if (config.getBoolean("logger")) {
                    logToConsole("Sign detection found " + player.getName() + " using " + detectConfig.getName() + ", banning player.");
                }

                notifyStaff("Player " + player.getName() + " was banned for using " + detectConfig.getName());

                banAndKick(player, reason, expires, "FakeModBlocker-SIGN");
                break;

            case IGNORE:
                if (config.getBoolean("logger")) {
                    logToConsole("Sign detection matched " + detectConfig.getName() + " but action is IGNORE.");
                }
                break;
        }
    }

    void notifyStaff(String message) {
        if (!config.getBoolean("notifyStaff", true)) {
            return;
        }

        String permission = config.getString("notificationPermission", "fakemodblocker.notify");
        String prefix = getMessage("prefix");
        String msg = prefix + message;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(permission)) {
                MessageBridge.send(online, msg);
            }
        }
    }

    /**
     * Pure API probe: whether this server exposes everything the virtual sign check needs.
     * Deliberately independent of the config toggle, so enabling the feature and reloading
     * can activate it without a restart.
     */
    private boolean detectSignDetectionApiSupport() {
        String[] requiredClasses = {
                "io.papermc.paper.event.packet.UncheckedSignChangeEvent",
                "io.papermc.paper.math.Position",
                "org.bukkit.block.sign.Side",
                "org.bukkit.block.data.type.WallSign",
                "net.kyori.adventure.text.Component"
        };
        for (String name : requiredClasses) {
            try {
                Class.forName(name);
            } catch (Throwable t) {
                this.signApiUnsupportedReason = "missing class " + name
                        + " (server is not Paper, or Paper build is too old)";
                return false;
            }
        }

        try {
            Class<?> positionClass = Class.forName("io.papermc.paper.math.Position");
            Class<?> sideClass = Class.forName("org.bukkit.block.sign.Side");
            Class<?> tileStateClass = Class.forName("org.bukkit.block.TileState");
            Class<?> blockDataClass = Class.forName("org.bukkit.block.data.BlockData");
            Class<?> signClass = Class.forName("org.bukkit.block.Sign");

            checkMethod(Player.class, "openVirtualSign", positionClass, sideClass);
            checkMethod(Player.class, "sendBlockUpdate", Location.class, tileStateClass);
            checkMethod(Player.class, "sendBlockChange", Location.class, blockDataClass);
            checkMethod(signClass, "getSide", sideClass);

            this.signApiUnsupportedReason = null;
            return true;
        } catch (NoSuchMethodException e) {
            this.signApiUnsupportedReason = "missing method " + e.getMessage()
                    + " (Paper API too old; sign-translation needs Player#openVirtualSign, which was added in Paper 1.21.5"
                    + " and does not exist on 1.21.4 or earlier; Spigot and some Paper forks like Purpur/Pufferfish/Leaves may also lack it)";
            return false;
        } catch (Throwable t) {
            this.signApiUnsupportedReason = t.getClass().getSimpleName() + " - " + t.getMessage();
            return false;
        }
    }

    private void checkMethod(Class<?> owner, String name, Class<?>... args) throws NoSuchMethodException {
        try {
            owner.getMethod(name, args);
        } catch (NoSuchMethodException e) {
            StringBuilder sb = new StringBuilder(owner.getName()).append('#').append(name).append('(');
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(args[i].getSimpleName());
            }
            sb.append(')');
            throw new NoSuchMethodException(sb.toString());
        }
    }

    /** Human-readable state of the sign check, used for every status line. */
    private String describeSignDetectionState() {
        if (!config.getBoolean("extra-detections.sign-translation.enabled", false)) {
            return "INACTIVE (disabled in config)";
        }
        if (!signDetectionApiSupported) {
            return "INACTIVE (unsupported server/API: " + signApiUnsupportedReason + ")";
        }
        if (signDetectionBridge == null) {
            return "INACTIVE (listener not registered: "
                    + (signBridgeFailure != null ? signBridgeFailure : "bridge was never created") + ")";
        }
        return "ACTIVE";
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private boolean detectPacketEventsSupport() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            Class.forName("com.github.retrooper.packetevents.protocol.packettype.PacketType");
            Class.forName("com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage");
            return Bukkit.getPluginManager().getPlugin("packetevents") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Brings the PacketEvents bridge in line with the current config. */
    private void syncPacketEventsBridge() {
        if (!config.getBoolean("extra-detections.packet-events.enabled", true)) {
            if (packetEventsBridge != null) {
                disposePacketEventsBridge();
                if (config.getBoolean("logger")) {
                    logToConsole("Packet-level channel detection disabled in config; bridge unloaded.");
                }
            }
            return;
        }

        if (packetEventsBridge != null) {
            return;
        }

        if (!detectPacketEventsSupport()) {
            if (config.getBoolean("logger")) {
                logToConsole("PacketEvents not detected. Packet-level channel detection disabled.");
            }
            return;
        }

        tryCreateAndRegisterPacketEventsBridge();
    }

    /**
     * The screen tracker rides along with sign detection: it exists purely so the virtual sign does
     * not overwrite a dialog or resource pack prompt. No PacketEvents, no tracker - the check then
     * only avoids Bukkit containers, which still works, just with less awareness.
     */
    private void syncScreenTrackerBridge() {
        boolean wanted = config.getBoolean("extra-detections.sign-translation.enabled", false)
                && config.getBoolean("extra-detections.sign-translation.avoid-open-screens", true);

        if (!wanted) {
            if (screenTrackerBridge != null) {
                disposeScreenTrackerBridge();
            }
            return;
        }
        if (screenTrackerBridge != null || !detectPacketEventsSupport()) {
            return;
        }

        try {
            Class<?> bridgeClass = Class.forName("creeper_knc.ScreenTrackerBridge");
            Constructor<?> constructor = bridgeClass.getConstructor(FakeModBlocker.class, ModBlocker.class);
            Object bridge = constructor.newInstance(FakeModBlocker.getInstance(), this);
            bridgeClass.getMethod("init").invoke(bridge);
            this.screenTrackerBridge = bridge;
        } catch (Throwable t) {
            // Purely an enhancement, so a failure here must not take sign detection down with it.
            this.screenTrackerBridge = null;
            if (config.getBoolean("logger")) {
                logToConsole("Screen tracker unavailable (" + unwrap(t).getMessage()
                        + "). Sign detection will only avoid Bukkit containers.");
            }
        }
    }

    private void disposeScreenTrackerBridge() {
        Object bridge = this.screenTrackerBridge;
        this.screenTrackerBridge = null;
        if (bridge == null) {
            return;
        }
        try {
            bridge.getClass().getMethod("shutdown").invoke(bridge);
        } catch (Throwable ignored) {
        }
    }

    private void disposePacketEventsBridge() {
        Object bridge = this.packetEventsBridge;
        this.packetEventsBridge = null;
        if (bridge == null) {
            return;
        }
        try {
            bridge.getClass().getMethod("shutdown").invoke(bridge);
        } catch (Throwable ignored) {
        }
    }

    private void tryCreateAndRegisterPacketEventsBridge() {
        try {
            Class<?> bridgeClass = Class.forName("creeper_knc.PacketEventsBridge");
            Constructor<?> constructor = bridgeClass.getConstructor(FakeModBlocker.class, ModBlocker.class);
            Object bridge = constructor.newInstance(FakeModBlocker.getInstance(), this);

            Method initMethod = bridgeClass.getMethod("init");
            initMethod.invoke(bridge);

            this.packetEventsBridge = bridge;

            if (config.getBoolean("logger")) {
                logToConsole("PacketEvents bridge loaded successfully.");
            }
        } catch (Throwable t) {
            // Only reached when PacketEvents itself is installed, so any failure here is a
            // packaging/compatibility problem worth surfacing rather than an optional dependency.
            this.packetEventsBridge = null;
            FakeModBlocker.getInstance().getLogger().log(Level.WARNING,
                    "PacketEvents is installed and packet-level detection is enabled, but the bridge could not be loaded."
                            + " Packet-level channel detection stays off.", unwrap(t));
        }
    }

    public void handlePacketChannel(Player player, String channel) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!config.getBoolean("enable")) {
            return;
        }
        if (isExempt(player)) {
            return;
        }

        List<String> forbidden = config.getStringList("forbiddenList");
        List<String> matched = new ArrayList<>();

        for (String keyword : forbidden) {
            if (channelMatchesKeyword(channel, keyword)) {
                if (!matched.contains(keyword)) {
                    matched.add(keyword);
                }
            }
        }

        if (matched.isEmpty()) {
            return;
        }

        if (config.getBoolean("logger")) {
            logToConsole("Packet-level detection on " + player.getName()
                    + ": channel=" + channel + ", matched=" + matched);
        }

        if (player.hasPermission("fakemodblocker.kickbypass")) {
            return;
        }

        handleDetectedMods(player, matched);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        handledChannelKick.remove(uuid);
        if (violationManager != null) {
            violationManager.onPlayerQuit(uuid);
        }
        if (packetEventsBridge != null) {
            try {
                Method m = packetEventsBridge.getClass().getMethod("onPlayerQuit", UUID.class);
                m.invoke(packetEventsBridge, uuid);
            } catch (Throwable ignored) {
            }
        }
        // The sign bridge keeps a pending-check entry per player; drop it so a leaver leaves nothing.
        if (signDetectionBridge != null) {
            try {
                Method m = signDetectionBridge.getClass().getMethod("onPlayerQuit", UUID.class);
                m.invoke(signDetectionBridge, uuid);
            } catch (Throwable ignored) {
            }
        }
        ScreenGate.forget(uuid);
    }

    /** Brings the sign bridge in line with the current config: create, keep, or tear down. */
    private void syncSignDetectionBridge() {
        if (!config.getBoolean("extra-detections.sign-translation.enabled", false)) {
            if (signDetectionBridge != null) {
                disposeSignDetectionBridge();
                if (config.getBoolean("logger")) {
                    logToConsole("Sign translation detection disabled in config; bridge unloaded.");
                }
            }
            return;
        }

        if (!signDetectionApiSupported) {
            if (!signApiWarned) {
                signApiWarned = true;
                FakeModBlocker.getInstance().getLogger().warning("Sign translation detection is enabled in config, but this"
                        + " server does not support it (" + signApiUnsupportedReason + "). The check stays off.");
            }
            return;
        }

        if (signDetectionBridge != null) {
            return;
        }

        tryCreateAndRegisterSignBridge();
    }

    private void disposeSignDetectionBridge() {
        Object bridge = this.signDetectionBridge;
        this.signDetectionBridge = null;
        if (bridge == null) {
            return;
        }
        try {
            bridge.getClass().getMethod("shutdown").invoke(bridge);
        } catch (Throwable ignored) {
        }
        if (bridge instanceof Listener listener) {
            HandlerList.unregisterAll(listener);
        }
    }

    private void tryCreateAndRegisterSignBridge() {
        Object bridge = null;
        try {
            Class<?> bridgeClass = Class.forName("creeper_knc.VirtualSignDetectionBridge");
            Constructor<?> constructor = bridgeClass.getConstructor(FakeModBlocker.class, ModBlocker.class);
            bridge = constructor.newInstance(FakeModBlocker.getInstance(), this);

            if (bridge instanceof Listener listener) {
                Bukkit.getPluginManager().registerEvents(listener, FakeModBlocker.getInstance());
            }

            this.signDetectionBridge = bridge;
            this.signBridgeFailure = null;

            if (config.getBoolean("logger")) {
                logToConsole("Virtual sign detection bridge loaded successfully.");
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            this.signDetectionBridge = null;
            this.signBridgeFailure = cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? ": " + cause.getMessage() : "");

            // registerEvents() gives up part-way through the listener's handlers, so drop
            // whatever did get registered instead of leaving half a listener behind.
            if (bridge instanceof Listener listener) {
                HandlerList.unregisterAll(listener);
            }

            FakeModBlocker.getInstance().getLogger().log(Level.WARNING,
                    "Sign translation detection is enabled in config, but its listener could not be registered."
                            + " The check will be skipped for every player until this is fixed.", cause);
        }
    }

    private void kickPlayerCompat(Player player, String reason) {
        MessageBridge.kick(player, reason);
    }

    static String sanitizeCommandArgument(String raw) {
        if (raw == null) {
            return "";
        }

        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == 39) {
                continue;
            }
            out.append(Character.isWhitespace(c) || Character.isISOControl(c) ? '_' : c);
        }

        int start = 0;
        while (start < out.length() && (out.charAt(start) == '/' || out.charAt(start) == '@')) {
            start++;
        }
        return out.substring(start);
    }

    /** Single kick path for every detection: honours useCustomKickCommand. */
    void kickWithReason(Player player, String reason) {
        if (config.getBoolean("useCustomKickCommand")) {
            String cmd = config.getString("command", "")
                    .replace("%player%", sanitizeCommandArgument(player.getName()))
                    .replace("%kickMessage%", MessageBridge.toLegacySection(reason));

            FakeModBlocker.getInstance().getScheduler().runGlobal(() ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            );
        } else {
            kickPlayerCompat(player, reason);
        }
    }

    /** Records the ban and removes the player. {@code expires} of null means permanent. */
    void banAndKick(Player player, String reason, Date expires, String source) {
        banPlayerCompat(player, reason, expires, source);
        kickPlayerCompat(player, reason);
    }

    private void banPlayerCompat(Player player, String reason, Date expires, String source) {
        String legacyReason = MessageBridge.toLegacySection(reason);

        try {
            Class<?> banListTypeClass = Class.forName("io.papermc.paper.ban.BanListType");
            Object profileType = Enum.valueOf((Class<Enum>) banListTypeClass.asSubclass(Enum.class), "PROFILE");

            Method getBanList = Bukkit.class.getMethod("getBanList", banListTypeClass);
            Object banList = getBanList.invoke(null, profileType);

            Method getPlayerProfile = player.getClass().getMethod("getPlayerProfile");
            Object profile = getPlayerProfile.invoke(player);

            Method addBan = null;
            for (Method method : banList.getClass().getMethods()) {
                if (!method.getName().equals("addBan")) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 4) {
                    addBan = method;
                    break;
                }
            }

            if (addBan != null) {
                addBan.invoke(banList, profile, legacyReason, expires, source);
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            Bukkit.getBanList(BanList.Type.NAME)
                    .addBan(player.getName(), legacyReason, expires, source);
        } catch (Throwable ignored) {
        }
    }

    /** Null means permanent. Accepts 30d / 12h / 90m / 45s / 2w and compounds like 1d12h. */
    private Date parseDuration(String duration) {
        long millis = ViolationManager.parseMillis(duration);
        return millis <= 0L ? null : new Date(System.currentTimeMillis() + millis);
    }

    private void loadSignDetectionConfigs() {
        signDetectConfigs.clear();

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
            List<String> keys = readKeys(keySource);

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
                if (config.getBoolean("logger")) {
                    logToConsole("Sign detection config missing key/keys: " + modName);
                }
                continue;
            }

            DetectionAction action;
            try {
                action = DetectionAction.valueOf(actionRaw.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                action = DetectionAction.NOTICE;
            }

            signDetectConfigs.add(new DetectionModConfig(modName, keys, action, reason, duration, escalation));

            if (config.getBoolean("logger")) {
                logToConsole("Loaded sign detection item: " + modName + " | keys=" + keys + " | action=" + action);
            }
        }

        if (config.getBoolean("logger")) {
            logToConsole("Loaded total sign detection items: " + signDetectConfigs.size());
        }
    }

    static List<String> readKeys(ConfigurationSection section) {
        if (section == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        List<String> list = section.getStringList("keys");
        if (list != null) {
            for (String s : list) {
                if (s != null && !s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        String single = section.getString("key");
        if (single != null && !single.isEmpty()) {
            out.add(single);
        }
        return out;
    }

    @Override
    public void onPluginMessageReceived(String channel, @NotNull Player player, byte[] message) {
        String ch = channel.toLowerCase(Locale.ROOT);

        if (!ch.equals(FORGE_CHANNEL.toLowerCase(Locale.ROOT))
                && !ch.equals(FORGE_CHANNEL_LEGACY.toLowerCase(Locale.ROOT))) {

            if (ch.equals(FABRIC_CHANNEL.toLowerCase(Locale.ROOT))) {
                logToConsole(getMessage("log.plugin-message")
                        .replace("%player%", player.getName())
                        .replace("%mod%", "Fabric mod"));
            } else if (ch.contains("forge")
                    || ch.contains("fabric")
                    || ch.contains("mod")
                    || ch.contains("lunar")
                    || ch.contains("fml")) {
                String unknown = getMessage("log.unknown-channel", "unknown channel: %channel%")
                        .replace("%channel%", channel);
                logToConsole(getMessage("log.plugin-message")
                        .replace("%player%", player.getName())
                        .replace("%mod%", unknown));
            }
        } else {
            logToConsole(getMessage("log.plugin-message")
                    .replace("%player%", player.getName())
                    .replace("%mod%", "Forge mod"));
        }
    }

    private boolean channelMatchesKeyword(String channel, String keyword) {
        if (channel == null || keyword == null || keyword.isEmpty()) {
            return false;
        }
        String ch = channel.toLowerCase(Locale.ROOT);
        String kw = keyword.toLowerCase(Locale.ROOT);

        if (kw.contains(":")) {
            return ch.equals(kw);
        }

        return ch.equals(kw) || ch.startsWith(kw + ":");
    }

    void logToConsole(String msg) {
        String finalMessage = getMessage("prefix") + msg;
        CommandSender console = Bukkit.getConsoleSender();
        MessageBridge.send(console, finalMessage);
    }

    String getMessage(String path) {
        return getMessage(path, "");
    }

    /**
     * Single-argument {@code getString} on purpose: only that form consults the defaults layer,
     * which is the language file bundled in the jar. The two-argument form would skip it and
     * hand back the fallback for every key the admin's file predates.
     */
    String getMessage(String path, String fallback) {
        FileConfiguration messages = FakeModBlocker.getInstance().getMessages();
        if (messages == null) {
            return fallback;
        }
        String value = messages.getString(path);
        return value == null ? fallback : value;
    }

    public enum DetectionAction {
        NOTICE, KICK, BAN, IGNORE
    }

    /** Outcome of a sign-translation check request. */
    public enum SignDetectionState {
        /** The virtual sign was scheduled for the player. */
        STARTED,
        /** extra-detections.sign-translation.enabled is false. */
        DISABLED,
        /** Bedrock player, skipped through Floodgate. */
        BEDROCK_SKIPPED,
        /** Server/API lacks the virtual sign API. */
        UNSUPPORTED,
        /** Feature is on and supported, but the listener is not registered. */
        BRIDGE_UNAVAILABLE,
        /** The bridge exists but threw while starting the check. */
        START_FAILED,
        EXEMPT
    }

    public static class DetectionModConfig {
        private final String name;
        private final List<String> keys;
        private final DetectionAction action;
        private final String reason;
        private final String duration;
        private final boolean escalation;

        public DetectionModConfig(String name, List<String> keys, DetectionAction action, String reason, String duration) {
            this(name, keys, action, reason, duration, true);
        }

        public DetectionModConfig(String name, List<String> keys, DetectionAction action, String reason,
                                  String duration, boolean escalation) {
            this.name = name;
            this.keys = keys == null ? List.of() : List.copyOf(keys);
            this.action = action;
            this.reason = reason;
            this.duration = duration;
            this.escalation = escalation;
        }

        /** False when this mod opts out of the ladder and keeps its fixed action. */
        public boolean isEscalationEnabled() {
            return escalation;
        }

        public String getName() {
            return name;
        }

        public List<String> getKeys() {
            return keys;
        }

        public String getKey() {
            return keys.isEmpty() ? null : keys.get(0);
        }

        public DetectionAction getAction() {
            return action;
        }

        public String getReason() {
            return reason;
        }

        public String getDuration() {
            return duration;
        }
    }
}