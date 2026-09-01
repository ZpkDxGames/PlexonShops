package com.plexon.shops.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** MiniMessage-backed language service with compatibility for Plexon's legacy separators. */
public final class MessageService {
    private static final String DEFAULT_PREFIX = "<dark_gray>[</dark_gray><aqua>PlexonShops</aqua><dark_gray>]</dark_gray> ";
    private final MiniMessage templates;
    private final MiniMessage playerText;
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final YamlConfiguration messages;
    private final Component prefix;
    private final Component separator;

    private MessageService(YamlConfiguration messages) {
        this.templates = MiniMessage.miniMessage();
        this.playerText = MiniMessage.builder()
                .tags(TagResolver.builder()
                        .resolver(StandardTags.color())
                        .resolver(StandardTags.decorations())
                        .resolver(StandardTags.gradient())
                        .resolver(StandardTags.rainbow())
                        .resolver(StandardTags.reset())
                        .build())
                .build();
        this.messages = messages;
        this.prefix = parse(messages.getString("prefix", DEFAULT_PREFIX));
        this.separator = parse(messages.getString("separator", "&8&m--------------------------------&r"));
    }

    public static MessageService load(File file) {
        return new MessageService(YamlConfiguration.loadConfiguration(file));
    }

    public static MessageService load(File file, InputStream bundledDefaults) {
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        if (bundledDefaults == null) {
            return new MessageService(loaded);
        }
        try (InputStream stream = bundledDefaults;
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (IOException ignored) {
            // Reading an in-JAR resource should not fail; the on-disk messages remain usable if it does.
        }
        return new MessageService(loaded);
    }

    public Component get(String key, TagResolver... resolvers) {
        return parse(messages.getString(key, "<red>Missing message: " + key + "</red>"), withBuiltIns(resolvers));
    }

    public List<Component> list(String key, TagResolver... resolvers) {
        TagResolver combined = withBuiltIns(resolvers);
        return messages.getStringList(key).stream().map(line -> parse(line, combined)).toList();
    }

    public Component raw(String input, TagResolver... resolvers) {
        return parse(input, withBuiltIns(resolvers));
    }

    public Component stored(String input) {
        String normalized = HybridMiniMessage.convert(input);
        try {
            return playerText.deserialize(normalized);
        } catch (RuntimeException invalid) {
            return Component.text(normalized);
        }
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(prefix.append(get(key, resolvers)));
    }

    public String sanitizePlayerText(String input, boolean formattingAllowed) {
        String compact = input == null ? "" : input.strip().replace('\n', ' ').replace('\r', ' ');
        String normalized = HybridMiniMessage.convert(compact);
        return formattingAllowed ? normalized : playerText.escapeTags(normalized);
    }

    public TagResolver storedTag(String name, String input) {
        return TagResolver.resolver(name, Tag.inserting(stored(input)));
    }

    public String plainStored(String input) {
        return plainText.serialize(stored(input));
    }

    public int visibleLength(String input) {
        String plain = plainStored(input);
        return plain.codePointCount(0, plain.length());
    }

    public Component prefix() {
        return prefix;
    }

    public Component separator() {
        return separator;
    }

    private Component parse(String input, TagResolver... resolvers) {
        String normalized = HybridMiniMessage.convert(input);
        try {
            return templates.deserialize(normalized, resolvers);
        } catch (RuntimeException invalid) {
            return Component.text(normalized);
        }
    }

    private TagResolver withBuiltIns(TagResolver... resolvers) {
        return TagResolver.builder()
                .resolver(TagResolver.resolver("separator", Tag.inserting(separator)))
                .resolvers(resolvers)
                .build();
    }
}
