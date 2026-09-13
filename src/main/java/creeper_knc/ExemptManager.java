package creeper_knc;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ExemptManager {

    private static final String FILE_NAME = "exempt.yml";

    private static final Method OFFLINE_IF_CACHED = findOfflineIfCached();

    private final FakeModBlocker plugin;
    private final File file;

    private final Map<UUID, String> byUuid = new ConcurrentHashMap<>();

    private final Map<String, String> byName = new ConcurrentHashMap<>();

    public ExemptManager(FakeModBlocker plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    private static Method findOfflineIfCached() {
        try {
            return Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean isExempt(Player player) {
        if (player == null) {
            return false;
        }
        if (byUuid.containsKey(player.getUniqueId())) {
            return true;
        }
        return byName.containsKey(player.getName().toLowerCase(Locale.ROOT));
    }

    public boolean isExempt(UUID uuid) {
        return uuid != null && byUuid.containsKey(uuid);
    }

    public int size() {
        return byUuid.size() + byName.size();
    }

    public List<String> names() {
        List<String> out = new ArrayList<>(byUuid.values());
        out.addAll(byName.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public boolean isPending(String name) {
        return name != null && byName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public AddResult add(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return new AddResult(false, false, "");
        }
        String name = rawName.trim();

        UUID uuid = resolveUuid(name);
        if (uuid != null) {
            if (byUuid.containsKey(uuid)) {
                return new AddResult(false, false, byUuid.get(uuid));
            }
            byName.remove(name.toLowerCase(Locale.ROOT));
            byUuid.put(uuid, name);
            save();
            return new AddResult(true, false, name);
        }

        String key = name.toLowerCase(Locale.ROOT);
        if (byName.containsKey(key)) {
            return new AddResult(false, true, byName.get(key));
        }
        byName.put(key, name);
        save();
        return new AddResult(true, true, name);
    }

    public boolean remove(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return false;
        }
        String name = rawName.trim();
        boolean removed = byName.remove(name.toLowerCase(Locale.ROOT)) != null;

        UUID uuid = resolveUuid(name);
        if (uuid != null && byUuid.remove(uuid) != null) {
            removed = true;
        }

        if (!removed) {
            for (Map.Entry<UUID, String> entry : byUuid.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(name)) {
                    byUuid.remove(entry.getKey());
                    removed = true;
                    break;
                }
            }
        }

        if (removed) {
            save();
        }
        return removed;
    }

    public void onPlayerJoin(Player player) {
        if (player == null) {
            return;
        }
        String key = player.getName().toLowerCase(Locale.ROOT);

        if (byName.remove(key) != null) {
            byUuid.put(player.getUniqueId(), player.getName());
            save();
            return;
        }

        String stored = byUuid.get(player.getUniqueId());
        if (stored != null && !stored.equals(player.getName())) {
            byUuid.put(player.getUniqueId(), player.getName());
            save();
        }
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }

        if (OFFLINE_IF_CACHED != null) {
            try {
                Object cached = OFFLINE_IF_CACHED.invoke(null, name);
                if (cached instanceof OfflinePlayer offline) {
                    return offline.getUniqueId();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public void load() {
        byUuid.clear();
        byName.clear();

        if (!file.exists()) {
            return;
        }

        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

            ConfigurationSection players = yml.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    try {
                        byUuid.put(UUID.fromString(key), players.getString(key, key));
                    } catch (IllegalArgumentException ignored) {
                        byName.put(key.toLowerCase(Locale.ROOT), key);
                    }
                }
            }

            for (String pending : yml.getStringList("pending-names")) {
                if (pending != null && !pending.trim().isEmpty()) {
                    byName.put(pending.trim().toLowerCase(Locale.ROOT), pending.trim());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to read " + FILE_NAME
                    + "; starting with an empty exemption list.", t);
        }
    }

    public void save() {
        final Map<UUID, String> uuidSnapshot = Map.copyOf(byUuid);
        final List<String> nameSnapshot = new ArrayList<>(byName.values());

        plugin.getScheduler().runAsync(() -> {
            try {
                File folder = file.getParentFile();
                if (folder != null && !folder.exists()) {
                    folder.mkdirs();
                }

                YamlConfiguration yml = new YamlConfiguration();
                yml.options().setHeader(List.of(
                        "Players exempt from every FakeModBlocker check.",
                        "Managed with /modblocker exempt <add|remove|list>.",
                        "",
                        "players:       uuid -> last known name (the name is only for reading this file)",
                        "pending-names: players who had never joined when they were added; they move",
                        "               into 'players' automatically on their first login."));

                for (Map.Entry<UUID, String> entry : uuidSnapshot.entrySet()) {
                    yml.set("players." + entry.getKey(), entry.getValue());
                }
                yml.set("pending-names", nameSnapshot);

                yml.save(file);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to save " + FILE_NAME, t);
            }
        });
    }

    public static final class AddResult {
        private final boolean added;
        private final boolean pending;
        private final String displayName;

        AddResult(boolean added, boolean pending, String displayName) {
            this.added = added;
            this.pending = pending;
            this.displayName = displayName;
        }

        public boolean wasAdded() {
            return added;
        }

        public boolean isPending() {
            return pending;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
