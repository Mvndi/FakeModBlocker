package creeper_knc;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageBridge {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private static boolean initialized = false;

    private static boolean adventurePresent = false;
    private static boolean miniMessagePresent = false;
    private static boolean senderComponentSendPresent = false;
    private static boolean playerComponentKickPresent = false;

    private static Class<?> componentClass;

    private static Object miniMessageInstance;
    private static Method miniMessageDeserializeMethod;

    private static Object legacyAmpSerializer;
    private static Method legacyAmpDeserializeMethod;
    private static Method legacyAmpSerializeMethod;

    private static Object legacySectionSerializer;
    private static Method legacySectionSerializeMethod;

    private MessageBridge() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            componentClass = Class.forName("net.kyori.adventure.text.Component");
            adventurePresent = true;
        } catch (Throwable t) {
            adventurePresent = false;
            return;
        }

        try {
            Class<?> miniMessageClass = Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            Method miniMessageFactory = miniMessageClass.getMethod("miniMessage");
            miniMessageInstance = miniMessageFactory.invoke(null);
            miniMessageDeserializeMethod = miniMessageClass.getMethod("deserialize", String.class);
            miniMessagePresent = true;
        } catch (Throwable t) {
            miniMessagePresent = false;
        }

        try {
            Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            Method builderMethod = serializerClass.getMethod("builder");

            Method buildMethod;
            Method characterMethod;
            Method hexColorsMethod;
            Method unusualHexMethod;

            Object ampBuilder = builderMethod.invoke(null);
            buildMethod = ampBuilder.getClass().getMethod("build");
            characterMethod = ampBuilder.getClass().getMethod("character", char.class);
            hexColorsMethod = ampBuilder.getClass().getMethod("hexColors");
            unusualHexMethod = ampBuilder.getClass().getMethod("useUnusualXRepeatedCharacterHexFormat");

            ampBuilder = characterMethod.invoke(ampBuilder, '&');
            ampBuilder = hexColorsMethod.invoke(ampBuilder);
            ampBuilder = unusualHexMethod.invoke(ampBuilder);
            legacyAmpSerializer = buildMethod.invoke(ampBuilder);

            Object sectionBuilder = builderMethod.invoke(null);
            sectionBuilder = characterMethod.invoke(sectionBuilder, '§');
            sectionBuilder = hexColorsMethod.invoke(sectionBuilder);
            sectionBuilder = unusualHexMethod.invoke(sectionBuilder);
            legacySectionSerializer = buildMethod.invoke(sectionBuilder);

            legacyAmpDeserializeMethod = serializerClass.getMethod("deserialize", String.class);
            legacyAmpSerializeMethod = serializerClass.getMethod("serialize", componentClass);
            legacySectionSerializeMethod = serializerClass.getMethod("serialize", componentClass);
        } catch (Throwable t) {
            adventurePresent = false;
            miniMessagePresent = false;
            legacyAmpSerializer = null;
            legacySectionSerializer = null;
        }

        try {
            CommandSender.class.getMethod("sendMessage", componentClass);
            senderComponentSendPresent = true;
        } catch (Throwable t) {
            senderComponentSendPresent = false;
        }

        try {
            Player.class.getMethod("kick", componentClass);
            playerComponentKickPresent = true;
        } catch (Throwable t) {
            playerComponentKickPresent = false;
        }
    }

    public static void send(CommandSender sender, String message) {
        if (sender == null) {
            return;
        }

        Object component = parseComponentOrNull(message);
        if (component != null && senderComponentSendPresent) {
            try {
                Method sendMethod = sender.getClass().getMethod("sendMessage", componentClass);
                sendMethod.invoke(sender, component);
                return;
            } catch (Throwable ignored) {
            }
        }

        sender.sendMessage(toLegacySection(message));
    }

    public static void kick(Player player, String message) {
        if (player == null) {
            return;
        }

        Object component = parseComponentOrNull(message);
        if (component != null && playerComponentKickPresent) {
            try {
                Method kickMethod = player.getClass().getMethod("kick", componentClass);
                kickMethod.invoke(player, component);
                return;
            } catch (Throwable ignored) {
            }
        }

        try {
            player.kickPlayer(toLegacySection(message));
        } catch (Throwable ignored) {
        }
    }

    public static void sendLiteral(Player player, String literal) {
        if (player == null || literal == null || literal.isEmpty()) {
            return;
        }

        if (adventurePresent && senderComponentSendPresent && componentClass != null) {
            try {
                Method textFactory = componentClass.getMethod("text", String.class);
                Object component = textFactory.invoke(null, literal);
                Method sendMethod = CommandSender.class.getMethod("sendMessage", componentClass);
                sendMethod.invoke(player, component);
                return;
            } catch (Throwable ignored) {
            }
        }

        try {
            Class<?> textComponentClass = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> baseComponentClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Object component = textComponentClass.getConstructor(String.class).newInstance(literal);
            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            Method sendMethod = spigot.getClass().getMethod("sendMessage", baseComponentClass);
            sendMethod.setAccessible(true);
            sendMethod.invoke(spigot, component);
            return;
        } catch (Throwable ignored) {
        }

        player.sendMessage(literal);
    }

    public static String toLegacySection(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        Object component = parseComponentOrNull(input);
        if (component != null && legacySectionSerializer != null && legacySectionSerializeMethod != null) {
            try {
                return (String) legacySectionSerializeMethod.invoke(legacySectionSerializer, component);
            } catch (Throwable ignored) {
            }
        }

        return legacyColorOnly(input);
    }

    private static Object parseComponentOrNull(String input) {
        if (!adventurePresent || input == null || input.isEmpty()) {
            return null;
        }

        String normalized = convertHexToLegacyAmp(input);

        if (miniMessagePresent && miniMessageInstance != null && miniMessageDeserializeMethod != null) {
            try {
                Object mmComponent = miniMessageDeserializeMethod.invoke(miniMessageInstance, normalized);

                if (legacyAmpSerializer != null
                        && legacyAmpSerializeMethod != null
                        && legacyAmpDeserializeMethod != null) {
                    String reserialized = (String) legacyAmpSerializeMethod.invoke(legacyAmpSerializer, mmComponent);
                    return legacyAmpDeserializeMethod.invoke(legacyAmpSerializer, reserialized);
                }

                return mmComponent;
            } catch (Throwable ignored) {
            }
        }

        if (legacyAmpSerializer != null && legacyAmpDeserializeMethod != null) {
            try {
                return legacyAmpDeserializeMethod.invoke(legacyAmpSerializer, normalized);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static String legacyColorOnly(String input) {
        return convertHexToSection(input).replace('&', '§');
    }

    private static String convertHexToLegacyAmp(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("&x");
            for (char c : hex.toCharArray()) {
                replacement.append("&").append(c);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String convertHexToSection(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder buffer = new StringBuilder();

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }
}