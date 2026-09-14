package creeper_knc;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MinimapFairPlay implements Listener, PluginMessageListener {

    public static final String CHANNEL_JOURNEYMAP = "journeymap:perm_req";
    public static final String CHANNEL_VOXELMAP = "voxelmap:settings";

    public static final List<String> CHANNELS = List.of(CHANNEL_JOURNEYMAP, CHANNEL_VOXELMAP);

    private static final int JM_MARKER = 42;
    private static final int VOXEL_MARKER = 0;
    private static final int MAX_UTF_BYTES = 32767;

    private final FakeModBlocker plugin;

    private boolean enabled;
    private boolean respectExempt = true;
    private long delayTicks = 40L;

    private boolean journeymapEnabled;
    private boolean voxelmapEnabled;

    private String journeymapPayload = "{}";
    private String voxelmapPayload = "{}";

    public MinimapFairPlay(FakeModBlocker plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("force-minimap-fairplay.enabled", false);
        this.respectExempt = plugin.getConfig().getBoolean("force-minimap-fairplay.respect-exempt", true);
        this.delayTicks = Math.max(0L, plugin.getConfig().getLong("force-minimap-fairplay.delay-ticks", 40L));

        this.journeymapEnabled = plugin.getConfig().getBoolean("force-minimap-fairplay.journeymap.enabled", true);
        this.voxelmapEnabled = plugin.getConfig().getBoolean("force-minimap-fairplay.voxelmap.enabled", true);

        this.journeymapPayload = buildJourneymapJson();
        this.voxelmapPayload = buildVoxelmapJson();

        if (enabled) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                apply(player, 0L);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private boolean jm(String key, boolean def) {
        return plugin.getConfig().getBoolean("force-minimap-fairplay.journeymap." + key, def);
    }

    private boolean voxel(String key, boolean def) {
        return plugin.getConfig().getBoolean("force-minimap-fairplay.voxelmap." + key, def);
    }

    private static String tri(boolean allowed) {
        return allowed ? "ALL" : "NONE";
    }

    private static String bool(boolean value) {
        return value ? "true" : "false";
    }

    private String buildJourneymapJson() {
        boolean players = jm("players", false);
        boolean mobs = jm("mobs", false);
        boolean animals = jm("animals", false);
        boolean villagers = jm("villagers", false);
        boolean anyRadar = players || mobs || animals || villagers;

        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("journeymapEnabled", bool(jm("journeymap-enabled", true)));
        cfg.put("useWorldId", "true");
        cfg.put("viewOnlyServerProperties", "true");
        cfg.put("allowMultiplayerSettings", "ALL");

        cfg.put("worldPlayerRadar", tri(players));
        cfg.put("worldPlayerRadarUpdateTime", "5");
        cfg.put("seeUndergroundPlayers", tri(jm("underground-players", false)));
        cfg.put("hideOps", bool(jm("hide-ops", false)));
        cfg.put("hideSpectators", bool(jm("hide-spectators", true)));

        cfg.put("allowDeathPoints", bool(jm("death-points", true)));
        cfg.put("showInGameBeacons", bool(jm("beacons", true)));
        cfg.put("allowWaypoints", bool(jm("waypoints", true)));
        cfg.put("allowRightClickTeleport", bool(jm("teleport", false)));

        cfg.put("radarLateralDistance", String.valueOf(
                plugin.getConfig().getInt("force-minimap-fairplay.journeymap.radar-lateral-distance", 512)));
        cfg.put("radarVerticalDistance", String.valueOf(
                plugin.getConfig().getInt("force-minimap-fairplay.journeymap.radar-vertical-distance", 320)));

        cfg.put("maxAnimalsData", "128");
        cfg.put("maxAmbientCreaturesData", "128");
        cfg.put("maxMobsData", "128");
        cfg.put("maxPlayersData", "128");
        cfg.put("maxVillagersData", "128");

        cfg.put("teleportEnabled", bool(jm("teleport", false)));
        cfg.put("crossDimTeleport", bool(jm("teleport", false)));

        cfg.put("renderRange", "0");
        cfg.put("surfaceRenderRange", "0");
        cfg.put("caveRenderRange", "0");

        cfg.put("surfaceMapping", tri(jm("surface", true)));
        cfg.put("topoMapping", tri(jm("topo", true)));
        cfg.put("biomeMapping", tri(jm("biome", true)));
        cfg.put("caveMapping", tri(jm("caves", false)));

        cfg.put("radarEnabled", tri(anyRadar));
        cfg.put("playerRadarEnabled", bool(players));
        cfg.put("playerRadarNamesEnabled", bool(jm("player-names", false)));
        cfg.put("villagerRadarEnabled", bool(villagers));
        cfg.put("animalRadarEnabled", bool(animals));
        cfg.put("mobRadarEnabled", bool(mobs));

        cfg.put("configVersion", plugin.getConfig().getString(
                "force-minimap-fairplay.journeymap.config-version", "6.1.0-beta99"));

        return toJson(cfg);
    }

    private String buildVoxelmapJson() {
        boolean players = voxel("radar-players", false);
        boolean mobs = voxel("radar-mobs", false);
        boolean radar = voxel("radar", players || mobs);

        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("radarAllowed", bool(radar));
        cfg.put("radarMobsAllowed", bool(mobs));
        cfg.put("radarPlayersAllowed", bool(players));
        cfg.put("cavesAllowed", bool(voxel("caves", false)));
        return toRawJson(cfg);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }
        apply(event.getPlayer(), delayTicks);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!enabled) {
            return;
        }
        apply(event.getPlayer(), 0L);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!enabled || !CHANNEL_JOURNEYMAP.equals(channel)) {
            return;
        }
        if (isSkipped(player)) {
            return;
        }
        sendJourneymap(player);
    }

    private boolean isSkipped(Player player) {
        if (!respectExempt) {
            return false;
        }
        ModBlocker blocker = plugin.getModBlocker();
        return blocker != null && blocker.isExempt(player);
    }

    private void apply(Player player, long delay) {
        if (player == null || !player.isOnline() || isSkipped(player)) {
            return;
        }
        if (delay <= 0L) {
            send(player);
        } else {
            plugin.getScheduler().runDelayed(player, delay, () -> {
                if (player.isOnline()) {
                    send(player);
                }
            });
        }
    }

    private void send(Player player) {
        if (journeymapEnabled) {
            sendJourneymap(player);
        }
        if (voxelmapEnabled) {
            sendVoxelmap(player);
        }
        if (plugin.getConfig().getBoolean("logger")) {
            ModBlocker blocker = plugin.getModBlocker();
            if (blocker != null) {
                blocker.logToConsole("Minimap fair-play sent to " + player.getName()
                        + " (journeymap=" + journeymapEnabled + ", voxelmap=" + voxelmapEnabled + ").");
            }
        }
    }

    private void sendJourneymap(Player player) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(JM_MARKER);
        writeBoolean(buffer, false);
        if (!writeUtf(buffer, journeymapPayload)) {
            return;
        }
        writeBoolean(buffer, true);
        writeBoolean(buffer, false);
        dispatch(player, CHANNEL_JOURNEYMAP, buffer.toByteArray());
    }

    private void sendVoxelmap(Player player) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(VOXEL_MARKER);
        if (!writeUtf(buffer, voxelmapPayload)) {
            return;
        }
        dispatch(player, CHANNEL_VOXELMAP, buffer.toByteArray());
    }

    private void dispatch(Player player, String channel, byte[] data) {
        try {
            player.sendPluginMessage(plugin, channel, data);
        } catch (Throwable t) {
            if (plugin.getConfig().getBoolean("logger")) {
                plugin.getLogger().warning("Failed to send " + channel + " to "
                        + player.getName() + ": " + t.getMessage());
            }
        }
    }

    private static void writeBoolean(ByteArrayOutputStream out, boolean value) {
        out.write(value ? 1 : 0);
    }

    private boolean writeUtf(ByteArrayOutputStream out, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_UTF_BYTES) {
            plugin.getLogger().warning("Minimap fair-play payload is " + raw.length
                    + " bytes, over the " + MAX_UTF_BYTES + " limit; not sending.");
            return false;
        }
        writeVarInt(out, raw.length);
        out.write(raw, 0, raw.length);
        return true;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    private static String toJson(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\":\"")
              .append(escape(entry.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String toRawJson(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
        }
        return sb.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
