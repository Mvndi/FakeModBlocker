package creeper_knc;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Locale;

public final class XaeroFairPlay implements Listener {

    private static final char SECTION = (char) 0xA7;

    private static final String FLAG_FAIRPLAY = flag("fairxaero");

    private static final String FLAG_WM_NETHER_IS_FAIR = flag("xaerowmnetherisfair");
    private static final String FLAG_MM_NETHER_IS_FAIR = flag("xaerommnetherisfair");

    private static final String FLAG_NO_MINIMAP = flag("nominimap");

    private static final String FLAG_RESET = flag("resetxaero");

    private final FakeModBlocker plugin;

    private boolean enabled;
    private Mode mode = Mode.FAIRPLAY;
    private boolean silent = true;
    private boolean respectExempt = true;
    private boolean resendOnWorldChange = true;
    private long delayTicks = 20L;

    private boolean appliedToAnyone;

    public XaeroFairPlay(FakeModBlocker plugin) {
        this.plugin = plugin;
        reload();
    }

    private static String flag(String word) {
        StringBuilder sb = new StringBuilder(word.length() * 2);
        for (int i = 0; i < word.length(); i++) {
            sb.append(SECTION).append(word.charAt(i));
        }
        return sb.toString();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();

        boolean wasEnabled = this.enabled;

        this.enabled = config.getBoolean("force-xaero-fairplay.enabled", false);
        this.mode = Mode.parse(config.getString("force-xaero-fairplay.mode", "fairplay"));
        this.silent = config.getBoolean("force-xaero-fairplay.silent", true);
        this.respectExempt = config.getBoolean("force-xaero-fairplay.respect-exempt", true);
        this.resendOnWorldChange = config.getBoolean("force-xaero-fairplay.resend-on-world-change", true);
        this.delayTicks = Math.max(0L, config.getLong("force-xaero-fairplay.delay-ticks", 20L));

        if (this.mode == Mode.OFF) {
            this.enabled = false;
        }

        if (this.enabled) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                apply(player, 0L);
            }
        } else if (wasEnabled || appliedToAnyone) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                sendReset(player);
            }
            appliedToAnyone = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Mode getMode() {
        return mode;
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
        if (!enabled || !resendOnWorldChange) {
            return;
        }
        apply(event.getPlayer(), 0L);
    }

    private void apply(Player player, long delay) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (respectExempt && plugin.getModBlocker() != null && plugin.getModBlocker().isExempt(player)) {
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
        MessageBridge.sendLiteral(player, prefix() + FLAG_RESET);

        switch (mode) {
            case FAIRPLAY:
                MessageBridge.sendLiteral(player, prefix() + FLAG_FAIRPLAY);
                break;

            case FAIRPLAY_NETHER:
                MessageBridge.sendLiteral(player, prefix() + FLAG_FAIRPLAY);
                MessageBridge.sendLiteral(player, prefix() + FLAG_WM_NETHER_IS_FAIR);
                MessageBridge.sendLiteral(player, prefix() + FLAG_MM_NETHER_IS_FAIR);
                break;

            case DISABLE:
                MessageBridge.sendLiteral(player, prefix() + FLAG_NO_MINIMAP);
                break;

            case OFF:
            default:
                return;
        }

        appliedToAnyone = true;

        if (plugin.getConfig().getBoolean("logger")) {
            ModBlocker blocker = plugin.getModBlocker();
            if (blocker != null) {
                blocker.logToConsole("Xaero fair-play rule sent to " + player.getName()
                        + " (mode=" + mode.name().toLowerCase(Locale.ROOT) + ").");
            }
        }
    }

    private void sendReset(Player player) {
        MessageBridge.sendLiteral(player, prefix() + FLAG_RESET);
    }

    private String prefix() {
        if (silent) {
            return "";
        }
        String text = plugin.getMessages() == null
                ? null
                : plugin.getMessages().getString("xaero.notice");
        if (text == null || text.isEmpty()) {
            text = "&7[Map] This server enforces fair-play mapping. ";
        }
        return MessageBridge.toLegacySection(text) + " ";
    }

    public enum Mode {
        OFF,
        FAIRPLAY,
        FAIRPLAY_NETHER,
        DISABLE;

        static Mode parse(String raw) {
            String value = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (value) {
                case "fairplay", "fair_play", "fair" -> FAIRPLAY;
                case "fairplay_nether", "fair_play_nether", "nether" -> FAIRPLAY_NETHER;
                case "disable", "disabled", "nominimap", "no_minimap" -> DISABLE;
                default -> OFF;
            };
        }
    }
}
