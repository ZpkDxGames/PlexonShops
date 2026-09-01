package com.plexon.shops.messages;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts supported legacy ampersand codes into MiniMessage tags before parsing. */
final class HybridMiniMessage {
    private static final Pattern BUNGEE_HEX = Pattern.compile(
            "(?i)&x&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])"
    );
    private static final Pattern HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern LEGACY = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Map<Character, String> TAGS = Map.ofEntries(
            Map.entry('0', "black"),
            Map.entry('1', "dark_blue"),
            Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"),
            Map.entry('4', "dark_red"),
            Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"),
            Map.entry('7', "gray"),
            Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"),
            Map.entry('a', "green"),
            Map.entry('b', "aqua"),
            Map.entry('c', "red"),
            Map.entry('d', "light_purple"),
            Map.entry('e', "yellow"),
            Map.entry('f', "white"),
            Map.entry('k', "obfuscated"),
            Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"),
            Map.entry('n', "underlined"),
            Map.entry('o', "italic"),
            Map.entry('r', "reset")
    );

    private HybridMiniMessage() {
    }

    static String convert(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        Matcher bungeeMatcher = BUNGEE_HEX.matcher(input);
        StringBuffer bungeeBuffer = new StringBuffer();
        while (bungeeMatcher.find()) {
            String hex = bungeeMatcher.group(1) + bungeeMatcher.group(2) + bungeeMatcher.group(3)
                    + bungeeMatcher.group(4) + bungeeMatcher.group(5) + bungeeMatcher.group(6);
            bungeeMatcher.appendReplacement(bungeeBuffer, Matcher.quoteReplacement("<#" + hex + '>'));
        }
        bungeeMatcher.appendTail(bungeeBuffer);

        Matcher hexMatcher = HEX.matcher(bungeeBuffer.toString());
        StringBuffer hexBuffer = new StringBuffer();
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(hexBuffer, Matcher.quoteReplacement("<#" + hexMatcher.group(1) + '>'));
        }
        hexMatcher.appendTail(hexBuffer);

        Matcher legacyMatcher = LEGACY.matcher(hexBuffer.toString());
        StringBuffer result = new StringBuffer();
        while (legacyMatcher.find()) {
            char code = legacyMatcher.group(1).toLowerCase(Locale.ROOT).charAt(0);
            String tag = TAGS.get(code);
            legacyMatcher.appendReplacement(result, Matcher.quoteReplacement(tag == null ? legacyMatcher.group() : '<' + tag + '>'));
        }
        legacyMatcher.appendTail(result);
        return result.toString();
    }
}
