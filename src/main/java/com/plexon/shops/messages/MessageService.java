package com.plexon.shops.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/** MiniMessage-backed language service with compatibility for Plexon's legacy separators. */
public final class MessageService {
    private static final String DEFAULT_PREFIX = "<dark_gray>[</dark_gray><aqua>PlexonShops</aqua><dark_gray>]</dark_gray> ";
    private final MiniMessage miniMessage;
    private final YamlConfiguration messages;
    private final Component prefix;
    private final Component separator;

    private MessageService(YamlConfiguration messages) {
        this.miniMessage = MiniMessage.miniMessage();
        this.messages = messages;
        this.prefix = parse(messages.getString("prefix", DEFAULT_PREFIX));
        this.separator = parse(messages.getString("separator", "&8&m--------------------------------&r"));
    }

    public static MessageService load(File file) {
        return new MessageService(YamlConfiguration.loadConfiguration(file));
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
        return parse(input == null ? "" : input);
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(prefix.append(get(key, resolvers)));
    }

    public String sanitizePlayerText(String input, boolean formattingAllowed) {
        String compact = input == null ? "" : input.strip().replace('\n', ' ').replace('\r', ' ');
        return formattingAllowed ? compact : miniMessage.escapeTags(compact);
    }

    public Component prefix() {
        return prefix;
    }

    public Component separator() {
        return separator;
    }

    private Component parse(String input, TagResolver... resolvers) {
        return miniMessage.deserialize(HybridMiniMessage.convert(input), resolvers);
    }

    private TagResolver withBuiltIns(TagResolver... resolvers) {
        return TagResolver.builder()
                .resolver(TagResolver.resolver("separator", Tag.inserting(separator)))
                .resolvers(resolvers)
                .build();
    }
}
