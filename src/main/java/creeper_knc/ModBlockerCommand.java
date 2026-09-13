package creeper_knc;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ModBlockerCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("fakemodblocker.admin")) {
            MessageBridge.send(sender, getMsg("command.no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            FakeModBlocker.getInstance().reloadAll();
            MessageBridge.send(sender, getMsg("command.reload-success"));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("violations")) {
            if (args.length != 2) {
                MessageBridge.send(sender, getMsg("command.usage"));
                return true;
            }
            showViolations(sender, args[1]);
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("clear")) {
            if (args.length < 2 || args.length > 3) {
                MessageBridge.send(sender, getMsg("command.usage"));
                return true;
            }
            clearViolations(sender, args[1], args.length == 3 ? args[2] : null);
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !target.isOnline()) {
                MessageBridge.send(sender, getMsg("command.player-not-found").replace("%player%", args[1]));
                return true;
            }

            List<String> channels = new ArrayList<>(target.getListeningPluginChannels());
            MessageBridge.send(sender, getMsg("command.check-header").replace("%player%", target.getName()));
            MessageBridge.send(sender, getMsg("command.check-channels") + " " + String.join(", ", channels));

            List<String> matched = new ArrayList<>();
            for (String keyword : FakeModBlocker.getInstance().getConfig().getStringList("forbiddenList")) {
                for (String ch : channels) {
                    if (ch.toLowerCase().contains(keyword.toLowerCase())) {
                        if (!matched.contains(keyword)) {
                            matched.add(keyword);
                        }
                    }
                }
            }

            if (matched.isEmpty()) {
                MessageBridge.send(sender, getMsg("command.check-none"));
            } else {
                MessageBridge.send(sender, getMsg("command.check-matched") + " " + String.join(", ", matched));
            }

            ModBlocker modBlocker = FakeModBlocker.getInstance().getModBlocker();
            ModBlocker.SignDetectionState state = modBlocker == null
                    ? null
                    : modBlocker.startSignDetection(target);

            if (state == ModBlocker.SignDetectionState.STARTED) {
                MessageBridge.send(sender, getMsg("command.sign-check-triggered").replace("%player%", target.getName()));
            } else {
                String reason = describeSkip(state);
                String template = getMsg("command.sign-check-skipped");
                if (template.contains("%reason%")) {
                    MessageBridge.send(sender, template
                            .replace("%player%", target.getName())
                            .replace("%reason%", reason));
                } else {
                    // Older messages_*.yml files have no %reason% placeholder.
                    MessageBridge.send(sender, template.replace("%player%", target.getName()));
                    MessageBridge.send(sender, getMsg("command.sign-check-reason-prefix", "&7Reason: &f") + reason);
                }
            }
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("exempt")) {
            handleExempt(sender, args);
            return true;
        }

        MessageBridge.send(sender, getMsg("command.usage"));
        return true;
    }

    private void handleExempt(CommandSender sender, String[] args) {
        ExemptManager exempt = FakeModBlocker.getInstance().getExemptManager();
        if (exempt == null) {
            MessageBridge.send(sender, getMsg("command.exempt-unavailable",
                    "&cThe exemption list is not initialized."));
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            List<String> names = exempt.names();
            if (names.isEmpty()) {
                MessageBridge.send(sender, getMsg("command.exempt-list-empty",
                        "&7Nobody is exempt right now."));
                return;
            }

            MessageBridge.send(sender, getMsg("command.exempt-list-header",
                    "&bExempt from all checks (&f%count%&b):")
                    .replace("%count%", String.valueOf(names.size())));

            for (String name : names) {
                String entry = getMsg("command.exempt-list-entry", "&7- &f%player%")
                        .replace("%player%", name);
                if (exempt.isPending(name)) {
                    entry = entry + getMsg("command.exempt-list-pending", " &8(awaiting first login)");
                }
                MessageBridge.send(sender, entry);
            }
            return;
        }

        if (args.length != 3) {
            MessageBridge.send(sender, getMsg("command.exempt-usage",
                    "&eUsage: /modblocker exempt <add | remove | list> [player]"));
            return;
        }

        String target = args[2];

        if (args[1].equalsIgnoreCase("add")) {
            ExemptManager.AddResult result = exempt.add(target);

            if (!result.wasAdded()) {
                MessageBridge.send(sender, getMsg("command.exempt-already",
                        "&7%player% is already exempt.").replace("%player%", result.getDisplayName()));
                return;
            }

            String template = result.isPending()
                    ? getMsg("command.exempt-added-pending",
                            "&a%player% is now exempt. They have never joined this server, so the entry"
                                    + " locks onto their UUID the first time they log in.")
                    : getMsg("command.exempt-added",
                            "&a%player% is now exempt from every check.");
            MessageBridge.send(sender, template.replace("%player%", result.getDisplayName()));
            return;
        }

        if (args[1].equalsIgnoreCase("remove")) {
            if (exempt.remove(target)) {
                MessageBridge.send(sender, getMsg("command.exempt-removed",
                        "&a%player% is no longer exempt.").replace("%player%", target));
            } else {
                MessageBridge.send(sender, getMsg("command.exempt-not-found",
                        "&7%player% was not on the exemption list.").replace("%player%", target));
            }
            return;
        }

        MessageBridge.send(sender, getMsg("command.exempt-usage",
                "&eUsage: /modblocker exempt <add | remove | list> [player]"));
    }

    private void showViolations(CommandSender sender, String name) {
        ViolationManager manager = violationManager();
        if (manager == null) {
            MessageBridge.send(sender, getMsg("command.escalation-unavailable",
                    "&cThe escalation system is not initialized."));
            return;
        }

        UUID uuid = resolveUuid(manager, name);
        List<ViolationManager.CounterView> counters = uuid == null ? List.of() : manager.view(uuid);

        MessageBridge.send(sender, getMsg("command.violations-header", "&bViolation record for &f%player%&b:")
                .replace("%player%", name));

        if (counters.isEmpty()) {
            MessageBridge.send(sender, getMsg("command.violations-none", "&aNo violations recorded for &f%player%&a.")
                    .replace("%player%", name));
        } else {
            String entry = getMsg("command.violations-entry", "&7- &f%mod%&7: &c%count% &7(last: &f%time%&7)");
            String expiredSuffix = getMsg("command.violations-expired", " &8(expired)");
            for (ViolationManager.CounterView counter : counters) {
                String label = ViolationManager.GLOBAL_KEY.equals(counter.getKey())
                        ? getMsg("command.violations-global-label", "any mod")
                        : counter.getKey();
                MessageBridge.send(sender, entry
                        .replace("%mod%", label)
                        .replace("%count%", String.valueOf(counter.getCount()))
                        .replace("%time%", counter.getLastFormatted())
                        + (counter.isExpired() ? expiredSuffix : ""));
            }
        }

        if (!manager.isEnabled()) {
            MessageBridge.send(sender, getMsg("command.violations-disabled",
                    "&7Note: escalation is currently disabled in config.yml."));
        }
    }

    private void clearViolations(CommandSender sender, String name, String mod) {
        ViolationManager manager = violationManager();
        if (manager == null) {
            MessageBridge.send(sender, getMsg("command.escalation-unavailable",
                    "&cThe escalation system is not initialized."));
            return;
        }

        UUID uuid = resolveUuid(manager, name);
        int removed = uuid == null ? 0 : manager.clear(uuid, mod);

        if (removed == 0) {
            MessageBridge.send(sender, getMsg("command.clear-none", "&7No violation records found for &f%player%&7.")
                    .replace("%player%", name));
            return;
        }

        MessageBridge.send(sender, getMsg("command.clear-success",
                        "&aCleared &f%count%&a violation record(s) for &f%player%&a.")
                .replace("%player%", name)
                .replace("%count%", String.valueOf(removed)));
    }

    /** Online player first, then a stored record, so offline players can be inspected and cleared. */
    private UUID resolveUuid(ViolationManager manager, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return manager.findByName(name);
    }

    private ViolationManager violationManager() {
        ModBlocker modBlocker = FakeModBlocker.getInstance().getModBlocker();
        return modBlocker == null ? null : modBlocker.getViolationManager();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("fakemodblocker.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filter(Arrays.asList("reload", "check", "violations", "clear", "exempt"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("exempt")) {
            return filter(Arrays.asList("add", "remove", "list"), args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("exempt")) {
            ExemptManager exempt = FakeModBlocker.getInstance().getExemptManager();
            if (exempt == null) {
                return Collections.emptyList();
            }
            if (args[1].equalsIgnoreCase("remove")) {
                return filter(exempt.names(), args[2]);
            }
            if (args[1].equalsIgnoreCase("add")) {
                return filter(onlineNames(), args[2]);
            }
            return Collections.emptyList();
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("check")) {
                return filter(onlineNames(), args[1]);
            }
            if (args[0].equalsIgnoreCase("violations") || args[0].equalsIgnoreCase("clear")) {
                List<String> names = onlineNames();
                ViolationManager manager = violationManager();
                if (manager != null) {
                    for (String stored : manager.knownNames()) {
                        if (!names.contains(stored)) {
                            names.add(stored);
                        }
                    }
                }
                return filter(names, args[1]);
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("clear")) {
            ViolationManager manager = violationManager();
            if (manager == null) {
                return Collections.emptyList();
            }
            UUID uuid = resolveUuid(manager, args[1]);
            List<String> keys = new ArrayList<>();
            if (uuid != null) {
                for (ViolationManager.CounterView counter : manager.view(uuid)) {
                    keys.add(counter.getKey());
                }
            }
            return filter(keys, args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> onlineNames() {
        List<String> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            players.add(p.getName());
        }
        return players;
    }

    private List<String> filter(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return options;
        }
        List<String> out = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }

    private String describeSkip(ModBlocker.SignDetectionState state) {
        if (state == null) {
            return getMsg("command.sign-check-reason-unavailable", "plugin is not initialized");
        }
        return switch (state) {
            case DISABLED -> getMsg("command.sign-check-reason-disabled",
                    "disabled in config (extra-detections.sign-translation.enabled)");
            case BEDROCK_SKIPPED -> getMsg("command.sign-check-reason-bedrock",
                    "Bedrock player, skipped via Floodgate");
            case UNSUPPORTED -> getMsg("command.sign-check-reason-unsupported",
                    "this server/API does not support the virtual sign check");
            case BRIDGE_UNAVAILABLE -> getMsg("command.sign-check-reason-bridge",
                    "the detection listener failed to register - see console");
            case START_FAILED -> getMsg("command.sign-check-reason-failed",
                    "the check could not be started - see console");
            case EXEMPT -> getMsg("command.sign-check-reason-exempt",
                    "this player is exempt from all checks");
            default -> getMsg("command.sign-check-reason-unavailable", "unknown");
        };
    }

    private String getMsg(String path) {
        return getMsg(path, "&cMissing message: " + path);
    }

    /** Goes through ModBlocker so the bundled language file still acts as the defaults layer. */
    private String getMsg(String path, String fallback) {
        ModBlocker modBlocker = FakeModBlocker.getInstance().getModBlocker();
        if (modBlocker != null) {
            return modBlocker.getMessage(path, fallback);
        }
        FileConfiguration messages = FakeModBlocker.getInstance().getMessages();
        if (messages == null) {
            return fallback;
        }
        String value = messages.getString(path);
        return value == null ? fallback : value;
    }
}