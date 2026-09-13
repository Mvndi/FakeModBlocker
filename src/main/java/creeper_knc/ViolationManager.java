package creeper_knc;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Optional escalation ("strike") system.
 *
 * <p>Every detection is counted per player, and the punishment is chosen from a configured
 * ladder instead of the fixed per-detection action: e.g. warn on the first offence, kick on
 * the second, ban on the third. Counts survive restarts through {@code violations.yml} and
 * can expire after a configurable idle period.
 *
 * <p>The whole feature is off unless {@code escalation.enabled} is true, in which case the
 * plugin behaves exactly as it did before this class existed.
 */
public final class ViolationManager {

    /** Counter key used when {@code escalation.per-mod} is false: one shared counter per player. */
    static final String GLOBAL_KEY = "*";

    static final String SOURCE_CHANNEL = "plugin channel";
    static final String SOURCE_SIGN = "sign translation";

    private static final String FILE_NAME = "violations.yml";

    private final FakeModBlocker plugin;
    private final ModBlocker parent;
    private final File file;

    private final Map<UUID, PlayerRecord> records = new ConcurrentHashMap<>();
    /** Counter keys already punished during the player's current session. */
    private final Map<UUID, Set<String>> countedThisSession = new ConcurrentHashMap<>();

    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final Object ioLock = new Object();

    private volatile Settings settings = Settings.disabled();
    /** Keeps a broken ladder from printing one warning per detection. */
    private boolean ladderWarned;

    public ViolationManager(FakeModBlocker plugin, ModBlocker parent) {
        this.plugin = plugin;
        this.parent = parent;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);

        reload();
        load();
    }

    // ---------------------------------------------------------------- config

    /** Re-reads the {@code escalation} section. Does not touch stored counts. */
    public void reload() {
        ladderWarned = false;

        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection("escalation");
        if (section == null) {
            settings = Settings.disabled();
            if (config.getBoolean("logger")) {
                parent.logToConsole("No 'escalation' section in config.yml; the warn -> kick -> ban"
                        + " system stays off. Add the section from the default config to enable it.");
            }
            return;
        }

        boolean enabled = section.getBoolean("enabled", false);
        boolean perMod = section.getBoolean("per-mod", true);
        boolean oncePerSession = section.getBoolean("count-once-per-session", true);

        String resetRaw = section.getString("reset-after", "30d");
        long resetMillis = parseMillis(resetRaw);
        if (resetMillis == 0L && isMalformedDuration(resetRaw)) {
            plugin.getLogger().warning("escalation.reset-after is not a valid duration ('" + resetRaw
                    + "'). Violations will never expire. Use forms like 30d, 12h, 90m, 1d12h, or 0 for never.");
        }

        boolean useCustomBan = section.getBoolean("use-custom-ban-command", false);
        String banCommand = section.getString("ban-command", "ban %player% %reason%");
        String tempBanCommand = section.getString("temp-ban-command", "tempban %player% %duration% %reason%");

        List<Step> ladder = readLadder(section);
        if (enabled && ladder.isEmpty()) {
            plugin.getLogger().warning("escalation.enabled is true but escalation.ladder has no usable entry."
                    + " Detections fall back to the fixed per-detection punishment.");
        }

        settings = new Settings(enabled, perMod, oncePerSession, resetMillis,
                useCustomBan, banCommand, tempBanCommand, ladder);

        if (config.getBoolean("logger")) {
            parent.logToConsole("Escalation system: " + (enabled ? "ACTIVE" : "INACTIVE")
                    + " | steps=" + ladder.size()
                    + " | per-mod=" + perMod
                    + " | reset-after=" + (resetMillis > 0 ? resetRaw : "never"));
        }
    }

    /**
     * Reads the ladder. The documented form is a list of maps, but a section keyed by the
     * violation number is accepted too, because both shapes are natural to write in YAML.
     */
    private List<Step> readLadder(ConfigurationSection section) {
        List<Step> ladder = new ArrayList<>();

        for (Map<?, ?> raw : section.getMapList("ladder")) {
            Step step = readStep(string(raw.get("violations")), raw);
            if (step != null) {
                ladder.add(step);
            }
        }

        if (ladder.isEmpty()) {
            ConfigurationSection ladderSection = section.getConfigurationSection("ladder");
            if (ladderSection != null) {
                for (String key : ladderSection.getKeys(false)) {
                    ConfigurationSection entry = ladderSection.getConfigurationSection(key);
                    if (entry == null) {
                        continue;
                    }
                    Map<String, Object> values = new LinkedHashMap<>(entry.getValues(false));
                    values.putIfAbsent("violations", key);
                    Step step = readStep(key, values);
                    if (step != null) {
                        ladder.add(step);
                    }
                }
            }
        }

        ladder.sort(Comparator.comparingInt(s -> s.violations));
        return Collections.unmodifiableList(ladder);
    }

    private Step readStep(String violationsRaw, Map<?, ?> values) {
        int violations;
        try {
            violations = Integer.parseInt(String.valueOf(violationsRaw).trim());
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Skipping escalation ladder entry with an invalid 'violations' value: "
                    + violationsRaw);
            return null;
        }

        if (violations < 1) {
            plugin.getLogger().warning("Skipping escalation ladder entry with violations=" + violations
                    + " (must be 1 or higher).");
            return null;
        }

        String actionRaw = string(values.get("action"));
        EscalationAction action;
        try {
            action = EscalationAction.valueOf(actionRaw == null ? "WARN" : actionRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown escalation action '" + actionRaw + "' at violations=" + violations
                    + "; using WARN. Valid actions: WARN, KICK, BAN, COMMAND, IGNORE.");
            action = EscalationAction.WARN;
        }

        String duration = string(values.get("duration"));
        if (action == EscalationAction.BAN && isMalformedDuration(duration)) {
            plugin.getLogger().warning("escalation ladder entry at violations=" + violations
                    + " has an invalid duration ('" + duration + "'); the ban will be permanent.");
        }

        String command = string(values.get("command"));
        if (action == EscalationAction.COMMAND && (command == null || command.trim().isEmpty())) {
            plugin.getLogger().warning("escalation ladder entry at violations=" + violations
                    + " uses action COMMAND but defines no 'command'; nothing will happen at that step.");
        }

        return new Step(violations, action, string(values.get("message")), duration, command);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------- runtime

    public boolean isEnabled() {
        return settings.enabled;
    }

    /**
     * Records a detection and applies the matching ladder step.
     *
     * @param mods           detected mod / channel keyword names, used as counter keys and in messages
     * @param source         where the detection came from, for logs
     * @param contextReason  the per-mod reason from config, exposed as {@code %reason%}
     * @return true when escalation took responsibility for this detection; false when the caller
     *         should fall back to its own fixed punishment
     */
    public boolean handle(Player player, List<String> mods, String source, String contextReason) {
        Settings current = settings;
        if (!current.enabled) {
            return false;
        }

        if (current.ladder.isEmpty()) {
            if (!ladderWarned) {
                ladderWarned = true;
                plugin.getLogger().warning("Escalation is enabled but the ladder is empty; falling back to the"
                        + " fixed punishment for " + player.getName() + " and every later detection.");
            }
            return false;
        }

        List<String> names = (mods == null || mods.isEmpty())
                ? List.of("unknown")
                : new ArrayList<>(new LinkedHashSet<>(mods));

        // Counter keys are lower-cased so the channel keyword "freecam" and the
        // sign-translation entry "Freecam" share one strike counter.
        List<String> keys;
        if (current.perMod) {
            Set<String> unique = new LinkedHashSet<>();
            for (String name : names) {
                unique.add(name.toLowerCase(Locale.ROOT));
            }
            keys = new ArrayList<>(unique);
        } else {
            keys = List.of(GLOBAL_KEY);
        }

        UUID uuid = player.getUniqueId();
        PlayerRecord rec = records.computeIfAbsent(uuid, u -> new PlayerRecord(u, player.getName()));
        rec.name = player.getName();

        Set<String> session = countedThisSession.computeIfAbsent(uuid, u -> ConcurrentHashMap.newKeySet());

        long now = System.currentTimeMillis();
        int bestIndex = -1;
        int bestCount = 0;
        boolean counted = false;

        for (String key : keys) {
            if (current.countOncePerSession && !session.add(key)) {
                continue;
            }
            counted = true;

            int count = rec.increment(key, now, current.resetAfterMillis);
            int index = stepIndexFor(current.ladder, count);
            if (index > bestIndex) {
                bestIndex = index;
                bestCount = count;
            }
        }

        if (!counted) {
            if (plugin.getConfig().getBoolean("logger")) {
                parent.logToConsole("Escalation: " + player.getName() + " already counted this session for "
                        + String.join(", ", keys) + "; no extra strike.");
            }
            return true;
        }

        requestSave();

        if (bestIndex < 0) {
            // Counts still below the first rung: recorded, nothing to apply yet.
            parent.logToConsole("Escalation: " + player.getName() + " now at violation " + bestCount
                    + " for " + String.join(", ", names) + " (" + source + "); below the first ladder step.");
            return true;
        }

        applyStep(player, current, bestIndex, names, bestCount, source, contextReason);
        return true;
    }

    private void applyStep(Player player, Settings current, int index, List<String> mods,
                           int count, String source, String contextReason) {
        Step step = current.ladder.get(index);
        String modLabel = String.join(", ", mods);
        String durationLabel = describeDuration(step.duration);
        String nextLabel = index + 1 < current.ladder.size()
                ? String.valueOf(current.ladder.get(index + 1).violations)
                : message("escalation.next-none", "-");

        Placeholders placeholders = new Placeholders(player.getName(), modLabel, count, index + 1,
                current.ladder.size(), nextLabel, durationLabel, contextReason, source, actionLabel(step.action));

        parent.logToConsole(placeholders.apply(message("escalation.log",
                "%player% reached violation %count% for %mod% (%source%) -> step %step%/%steps%: %action%")));

        if (step.action != EscalationAction.IGNORE) {
            parent.notifyStaff(placeholders.apply(message("escalation.notify",
                    "&e%player% &7hit escalation step &f%step%&7/&f%steps% &7(&f%action%&7) for &f%mod%&7,"
                            + " violation &f%count%")));
        }

        String text = placeholders.apply(resolveMessage(step));

        switch (step.action) {
            case IGNORE:
                break;

            case WARN:
                MessageBridge.send(player, text);
                break;

            case KICK:
                parent.kickWithReason(player, text);
                break;

            case BAN:
                Date expires = parseExpiry(step.duration);
                if (current.useCustomBanCommand) {
                    // The external ban plugin is expected to remove the player itself.
                    dispatch(placeholders.applyForCommand(expires == null
                            ? current.banCommand
                            : current.tempBanCommand, step.duration, text));
                } else {
                    parent.banAndKick(player, text, expires, "FakeModBlocker");
                }
                break;

            case COMMAND:
                if (step.command != null && !step.command.trim().isEmpty()) {
                    dispatch(placeholders.applyForCommand(step.command, step.duration, text));
                }
                break;
        }
    }

    private void dispatch(String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        plugin.getScheduler().runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private String resolveMessage(Step step) {
        if (step.message != null && !step.message.isEmpty()) {
            return step.message;
        }
        return switch (step.action) {
            case WARN -> message("escalation.warn",
                    "&e&lWARNING &r&fYou are using &c%mod%&f, which is not allowed here."
                            + " &7(violation %count%) &fRemove it before you are removed from the server.");
            case KICK -> message("escalation.kick",
                    "&c&lWarning: &f%mod% &cis not allowed on this server.\n"
                            + "&7This is violation &f%count%&7. Joining again with it will be punished harder.");
            case BAN -> message("escalation.ban",
                    "&4&lBanned: &fyou rejoined with &c%mod%&f after being warned.\n"
                            + "&7Violations: &f%count% &7| Duration: &f%duration%");
            default -> "";
        };
    }

    private String actionLabel(EscalationAction action) {
        return message("escalation.action-" + action.name().toLowerCase(Locale.ROOT),
                action.name().toLowerCase(Locale.ROOT));
    }

    private String describeDuration(String duration) {
        long millis = parseMillis(duration);
        if (millis <= 0L) {
            return message("escalation.duration-permanent", "permanent");
        }
        return duration.trim();
    }

    private Date parseExpiry(String duration) {
        long millis = parseMillis(duration);
        return millis <= 0L ? null : new Date(System.currentTimeMillis() + millis);
    }

    private static int stepIndexFor(List<Step> ladder, int count) {
        int index = -1;
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).violations <= count) {
                index = i;
            }
        }
        return index;
    }

    private String message(String path, String fallback) {
        String value = parent.getMessage(path, fallback);
        return value == null || value.isEmpty() ? fallback : value;
    }

    // ---------------------------------------------------------------- admin API

    public void onPlayerQuit(UUID uuid) {
        countedThisSession.remove(uuid);
    }

    /** Read-only view of a player's counters, newest activity first. Never null. */
    public List<CounterView> view(UUID uuid) {
        PlayerRecord rec = uuid == null ? null : records.get(uuid);
        if (rec == null) {
            return List.of();
        }
        return rec.snapshot(settings.resetAfterMillis);
    }

    /** Names of every player with a stored record, for tab completion. */
    public List<String> knownNames() {
        List<String> names = new ArrayList<>();
        for (PlayerRecord rec : records.values()) {
            if (rec.name != null && !rec.name.isEmpty()) {
                names.add(rec.name);
            }
        }
        return names;
    }

    /** Resolves a stored record by exact name (case-insensitive), for offline lookups. */
    public UUID findByName(String name) {
        if (name == null) {
            return null;
        }
        for (PlayerRecord rec : records.values()) {
            if (name.equalsIgnoreCase(rec.name)) {
                return rec.uuid;
            }
        }
        return null;
    }

    /**
     * Clears stored violations.
     *
     * @param mod a single counter key, or null for every counter of that player
     * @return how many counters were removed
     */
    public int clear(UUID uuid, String mod) {
        PlayerRecord rec = uuid == null ? null : records.get(uuid);
        if (rec == null) {
            return 0;
        }

        int removed;
        if (mod == null) {
            removed = rec.counters.size();
            records.remove(uuid);
        } else {
            // Stored keys are lower-cased; removeCounter also matches case-insensitively.
            removed = rec.removeCounter(mod.toLowerCase(Locale.ROOT)) ? 1 : 0;
            if (rec.counters.isEmpty()) {
                records.remove(uuid);
            }
        }

        // Also forget the session marks, so a cleared player can be counted again right away.
        Set<String> session = countedThisSession.get(uuid);
        if (session != null) {
            if (mod == null) {
                session.clear();
            } else {
                session.remove(mod.toLowerCase(Locale.ROOT));
            }
        }

        if (removed > 0) {
            requestSave();
        }
        return removed;
    }

    // ---------------------------------------------------------------- storage

    private void load() {
        records.clear();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yml;
        try {
            yml = YamlConfiguration.loadConfiguration(file);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Could not read " + FILE_NAME + "; starting with empty"
                    + " violation records.", t);
            return;
        }

        long now = System.currentTimeMillis();
        long resetMillis = settings.resetAfterMillis;
        int dropped = 0;

        for (Map<?, ?> raw : yml.getMapList("records")) {
            UUID uuid;
            try {
                uuid = UUID.fromString(String.valueOf(raw.get("uuid")));
            } catch (RuntimeException e) {
                continue;
            }

            PlayerRecord rec = new PlayerRecord(uuid, string(raw.get("name")));
            Object counters = raw.get("counters");
            if (counters instanceof List<?> list) {
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> counter)) {
                        continue;
                    }
                    String key = string(counter.get("key"));
                    if (key == null || key.isEmpty()) {
                        continue;
                    }

                    int count = toInt(counter.get("count"));
                    long last = toLong(counter.get("last"));
                    if (count <= 0) {
                        continue;
                    }

                    // Expired counters are dropped on load so the file cannot grow forever.
                    if (resetMillis > 0L && last > 0L && now - last > resetMillis) {
                        dropped++;
                        continue;
                    }

                    rec.put(key.toLowerCase(Locale.ROOT), count, last);
                }
            }

            if (!rec.counters.isEmpty()) {
                records.put(uuid, rec);
            }
        }

        if (plugin.getConfig().getBoolean("logger")) {
            parent.logToConsole("Loaded violation records for " + records.size() + " player(s)"
                    + (dropped > 0 ? ", dropped " + dropped + " expired counter(s)." : "."));
        }

        if (dropped > 0) {
            requestSave();
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** Queues one async write; further changes made before it runs are included for free. */
    private void requestSave() {
        if (!saveQueued.compareAndSet(false, true)) {
            return;
        }

        try {
            plugin.getScheduler().runAsync(() -> {
                saveQueued.set(false);
                saveNow();
            });
        } catch (Throwable t) {
            // Scheduler refused (typically during shutdown): write on this thread instead of losing data.
            saveQueued.set(false);
            saveNow();
        }
    }

    /** Writes the file on the calling thread. Used on shutdown, where async tasks are rejected. */
    public void flush() {
        saveNow();
    }

    private void saveNow() {
        synchronized (ioLock) {
            try {
                File folder = file.getParentFile();
                if (folder != null && !folder.exists()) {
                    folder.mkdirs();
                }

                YamlConfiguration yml = new YamlConfiguration();
                yml.options().header("FakeModBlocker violation records. Managed by the plugin -"
                        + " edit only while the server is stopped.\n"
                        + "Use /modblocker violations <player> and /modblocker clear <player> [mod] instead.");

                List<Map<String, Object>> out = new ArrayList<>();
                for (PlayerRecord rec : records.values()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("uuid", rec.uuid.toString());
                    entry.put("name", rec.name == null ? "" : rec.name);
                    entry.put("counters", rec.serialize());
                    out.add(entry);
                }

                yml.set("records", out);
                yml.save(file);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to save " + FILE_NAME, t);
            }
        }
    }

    // ---------------------------------------------------------------- duration util

    /**
     * Parses {@code 30d}, {@code 12h}, {@code 90m}, {@code 45s}, {@code 2w} and compounds like
     * {@code 1d12h}. Returns 0 for empty, "0", "permanent"/"never"/"none", and for anything
     * that cannot be parsed - callers treat 0 as "permanent" or "never expires".
     */
    static long parseMillis(String raw) {
        if (raw == null) {
            return 0L;
        }

        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.equals("0") || text.equals("perm") || text.equals("permanent")
                || text.equals("never") || text.equals("none")) {
            return 0L;
        }

        long total = 0L;
        long number = 0L;
        boolean sawDigit = false;
        boolean sawUnit = false;

        for (char c : text.toCharArray()) {
            if (c >= '0' && c <= '9') {
                number = number * 10L + (c - '0');
                sawDigit = true;
                continue;
            }

            long unit = switch (c) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> -1L;
            };

            if (unit < 0L || !sawDigit) {
                return 0L;
            }

            total += number * unit;
            number = 0L;
            sawDigit = false;
            sawUnit = true;
        }

        // A trailing number without a unit (e.g. "7d3") is a typo, not a duration.
        return sawDigit || !sawUnit ? 0L : total;
    }

    /** True when the text looks like an attempt at a duration that {@link #parseMillis} rejected. */
    private static boolean isMalformedDuration(String raw) {
        if (raw == null) {
            return false;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.equals("0") || text.equals("perm") || text.equals("permanent")
                || text.equals("never") || text.equals("none")) {
            return false;
        }
        return parseMillis(text) == 0L;
    }

    // ---------------------------------------------------------------- types

    public enum EscalationAction {
        WARN, KICK, BAN, COMMAND, IGNORE
    }

    /** Immutable snapshot of the {@code escalation} config section. */
    private static final class Settings {
        private final boolean enabled;
        private final boolean perMod;
        private final boolean countOncePerSession;
        private final long resetAfterMillis;
        private final boolean useCustomBanCommand;
        private final String banCommand;
        private final String tempBanCommand;
        private final List<Step> ladder;

        private Settings(boolean enabled, boolean perMod, boolean countOncePerSession, long resetAfterMillis,
                         boolean useCustomBanCommand, String banCommand, String tempBanCommand, List<Step> ladder) {
            this.enabled = enabled;
            this.perMod = perMod;
            this.countOncePerSession = countOncePerSession;
            this.resetAfterMillis = resetAfterMillis;
            this.useCustomBanCommand = useCustomBanCommand;
            this.banCommand = banCommand == null ? "" : banCommand;
            this.tempBanCommand = tempBanCommand == null ? "" : tempBanCommand;
            this.ladder = ladder == null ? List.of() : ladder;
        }

        private static Settings disabled() {
            return new Settings(false, true, true, 0L, false, "", "", List.of());
        }
    }

    private static final class Step {
        private final int violations;
        private final EscalationAction action;
        private final String message;
        private final String duration;
        private final String command;

        private Step(int violations, EscalationAction action, String message, String duration, String command) {
            this.violations = violations;
            this.action = action;
            this.message = message;
            this.duration = duration;
            this.command = command;
        }
    }

    /** One placeholder set, shared by chat messages and dispatched commands. */
    private static final class Placeholders {
        private final String player;
        private final String mod;
        private final int count;
        private final int step;
        private final int steps;
        private final String next;
        private final String duration;
        private final String reason;
        private final String source;
        private final String action;

        private Placeholders(String player, String mod, int count, int step, int steps, String next,
                             String duration, String reason, String source, String action) {
            this.player = player;
            this.mod = mod;
            this.count = count;
            this.step = step;
            this.steps = steps;
            this.next = next;
            this.duration = duration;
            this.reason = reason == null ? "" : reason;
            this.source = source == null ? "" : source;
            this.action = action;
        }

        private String apply(String raw) {
            if (raw == null) {
                return "";
            }
            return raw.replace("%player%", player)
                    .replace("%mods%", mod)
                    .replace("%mod%", mod)
                    .replace("%count%", String.valueOf(count))
                    .replace("%step%", String.valueOf(step))
                    .replace("%steps%", String.valueOf(steps))
                    .replace("%next%", next)
                    .replace("%duration%", duration)
                    .replace("%reason%", reason)
                    .replace("%source%", source)
                    .replace("%action%", action);
        }

        /**
         * Same placeholders for a console command, except {@code %reason%} is the punishment text
         * flattened to legacy colours and {@code %duration%} is the raw config token, which is what
         * ban plugins expect to parse.
         */
        private String applyForCommand(String raw, String rawDuration, String punishmentText) {
            if (raw == null) {
                return "";
            }
            String flattened = MessageBridge.toLegacySection(punishmentText).replace("\n", " ");
            return raw.replace("%player%", ModBlocker.sanitizeCommandArgument(player))
                    .replace("%mods%", mod)
                    .replace("%mod%", mod)
                    .replace("%count%", String.valueOf(count))
                    .replace("%step%", String.valueOf(step))
                    .replace("%steps%", String.valueOf(steps))
                    .replace("%next%", next)
                    .replace("%duration%", rawDuration == null ? "" : rawDuration.trim())
                    .replace("%reason%", flattened)
                    .replace("%kickMessage%", flattened)
                    .replace("%source%", source)
                    .replace("%action%", action);
        }
    }

    /** One counter as shown by {@code /modblocker violations}. */
    public static final class CounterView {
        private final String key;
        private final int count;
        private final long last;
        private final boolean expired;

        private CounterView(String key, int count, long last, boolean expired) {
            this.key = key;
            this.count = count;
            this.last = last;
            this.expired = expired;
        }

        public String getKey() {
            return key;
        }

        public int getCount() {
            return count;
        }

        public boolean isExpired() {
            return expired;
        }

        public String getLastFormatted() {
            if (last <= 0L) {
                return "-";
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(last));
        }
    }

    private static final class PlayerRecord {
        private final UUID uuid;
        private volatile String name;
        private final Map<String, Counter> counters = new ConcurrentHashMap<>();

        private PlayerRecord(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        private int increment(String key, long now, long resetAfterMillis) {
            Counter counter = counters.computeIfAbsent(key, k -> new Counter());
            synchronized (counter) {
                if (resetAfterMillis > 0L && counter.last > 0L && now - counter.last > resetAfterMillis) {
                    counter.count = 0;
                }
                counter.count++;
                counter.last = now;
                return counter.count;
            }
        }

        private void put(String key, int count, long last) {
            Counter counter = new Counter();
            counter.count = count;
            counter.last = last;
            counters.put(key, counter);
        }

        private boolean removeCounter(String key) {
            if (counters.remove(key) != null) {
                return true;
            }
            for (String existing : counters.keySet()) {
                if (existing.equalsIgnoreCase(key)) {
                    return counters.remove(existing) != null;
                }
            }
            return false;
        }

        private List<CounterView> snapshot(long resetAfterMillis) {
            long now = System.currentTimeMillis();
            List<CounterView> out = new ArrayList<>();
            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                Counter counter = entry.getValue();
                int count;
                long last;
                synchronized (counter) {
                    count = counter.count;
                    last = counter.last;
                }
                boolean expired = resetAfterMillis > 0L && last > 0L && now - last > resetAfterMillis;
                out.add(new CounterView(entry.getKey(), count, last, expired));
            }
            out.sort(Comparator.comparingLong((CounterView v) -> v.last).reversed());
            return out;
        }

        private List<Map<String, Object>> serialize() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                Counter counter = entry.getValue();
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("key", entry.getKey());
                synchronized (counter) {
                    map.put("count", counter.count);
                    map.put("last", counter.last);
                }
                out.add(map);
            }
            return out;
        }
    }

    private static final class Counter {
        private int count;
        private long last;
    }
}
