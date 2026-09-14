package creeper_knc;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class FakeModBlocker extends JavaPlugin {

    /** Language files shipped inside the jar; all of them are written to the data folder on startup. */
    private static final List<String> BUNDLED_LANGUAGES = List.of("en", "cn");

    private static FakeModBlocker instance;
    private FileConfiguration messages;
    private SchedulerAdapter scheduler;
    private ModBlocker modBlocker;
    private ExemptManager exemptManager;
    private XaeroFairPlay xaeroFairPlay;
    private MinimapFairPlay minimapFairPlay;

    public static FakeModBlocker getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        scheduler = new SchedulerAdapter(this);

        MessageBridge.init();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        saveDefaultConfig();
        getConfig().setDefaults(new YamlConfiguration());
        loadMessages();

        exemptManager = new ExemptManager(this);

        modBlocker = new ModBlocker();
        xaeroFairPlay = new XaeroFairPlay(this);
        minimapFairPlay = new MinimapFairPlay(this);

        ModBlockerCommand command = new ModBlockerCommand();
        Objects.requireNonNull(getCommand("modblocker"), "Command 'modblocker' not defined in plugin.yml")
                .setExecutor(command);
        Objects.requireNonNull(getCommand("modblocker"), "Command 'modblocker' not defined in plugin.yml")
                .setTabCompleter(command);

        getServer().getPluginManager().registerEvents(modBlocker, this);
        getServer().getPluginManager().registerEvents(xaeroFairPlay, this);
        getServer().getPluginManager().registerEvents(minimapFairPlay, this);

        for (String channel : MinimapFairPlay.CHANNELS) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, channel);
            getServer().getMessenger().registerIncomingPluginChannel(this, channel, minimapFairPlay);
        }

        getServer().getMessenger().registerIncomingPluginChannel(this, "fml:hs", modBlocker);
        getServer().getMessenger().registerIncomingPluginChannel(this, "fml:hsl", modBlocker);
        getServer().getMessenger().registerIncomingPluginChannel(this, "fabric:registry/sync", modBlocker);

        getLogger().info("FakeModBlocker enabled.");
    }

    @Override
    public void onDisable() {
        if (modBlocker != null) {
            modBlocker.shutdown();
        }
    }

    public void reloadAll() {
        reloadConfig();
        getConfig().setDefaults(new YamlConfiguration());
        loadMessages();
        if (exemptManager != null) {
            exemptManager.load();
        }
        if (modBlocker != null) {
            modBlocker.reloadModBlockerConfig();
        }
        if (xaeroFairPlay != null) {
            xaeroFairPlay.reload();
        }
        if (minimapFairPlay != null) {
            minimapFairPlay.reload();
        }
    }

    public void loadMessages() {
        // Every bundled language lands in the data folder, so admins can read and edit
        // both without having to unpack the jar.
        for (String bundled : BUNDLED_LANGUAGES) {
            saveLanguageResource("messages_" + bundled + ".yml");
        }

        String lang = normalizeLanguage(getConfig().getString("language", "en"));
        String filename = "messages_" + lang + ".yml";
        File messageFile = new File(getDataFolder(), filename);

        if (!messageFile.exists()) {
            getLogger().warning("Language file '" + filename + "' not found. Falling back to 'messages_en.yml'."
                    + " Bundled languages: " + String.join(", ", BUNDLED_LANGUAGES));
            filename = "messages_en.yml";
            messageFile = new File(getDataFolder(), filename);

            if (!messageFile.exists()) {
                getLogger().info("Creating default 'messages_en.yml'...");
                saveResource("messages_en.yml", false);
            }
        }

        try {
            YamlConfiguration loaded = YamlConfiguration.loadConfiguration(messageFile);

            // Layer the shipped file underneath: a language file written for an older version
            // still resolves keys added later, instead of printing "Missing message".
            YamlConfiguration defaults = loadBundledMessages(filename);
            if (defaults != null) {
                loaded.setDefaults(defaults);
            }

            this.messages = loaded;
            getLogger().info("Loaded language file: " + filename);
        } catch (Exception e) {
            getLogger().severe("Failed to load language file: " + filename);
            e.printStackTrace();
        }
    }

    /** Writes a bundled language file if it is missing; ignores languages the jar does not carry. */
    private void saveLanguageResource(String filename) {
        if (new File(getDataFolder(), filename).exists()) {
            return;
        }
        try {
            saveResource(filename, false);
        } catch (IllegalArgumentException ignored) {
            // Not bundled in this build.
        }
    }

    private YamlConfiguration loadBundledMessages(String filename) {
        try (InputStream in = getResource(filename)) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Accepts the common spellings of a locale so 'zh', 'zh_CN' and 'chinese' all find messages_cn.yml. */
    private String normalizeLanguage(String raw) {
        String lang = (raw == null ? "en" : raw).trim().toLowerCase(Locale.ROOT);
        return switch (lang) {
            case "zh", "zh_cn", "zh-cn", "zhcn", "chinese", "中文" -> "cn";
            case "english" -> "en";
            default -> lang;
        };
    }

    public FileConfiguration getMessages() {
        return messages;
    }

    public SchedulerAdapter getScheduler() {
        return scheduler;
    }

    public ModBlocker getModBlocker() {
        return modBlocker;
    }

    public ExemptManager getExemptManager() {
        return exemptManager;
    }

    public XaeroFairPlay getXaeroFairPlay() {
        return xaeroFairPlay;
    }

    public MinimapFairPlay getMinimapFairPlay() {
        return minimapFairPlay;
    }
}