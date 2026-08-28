package com.plexon.shops.storage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Delimiter-safe text-list encoding used by the SQLite schema. */
public final class TextListCodec {
    private static final String PREFIX = "v1:";

    private TextListCodec() {
    }

    public static String encode(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + values.stream()
                .map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .reduce((left, right) -> left + ',' + right)
                .orElse("");
    }

    public static List<String> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        if (!encoded.startsWith(PREFIX)) {
            return List.of(encoded);
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        String payload = encoded.substring(PREFIX.length());
        return Arrays.stream(payload.split(",", -1))
                .map(value -> new String(decoder.decode(value), StandardCharsets.UTF_8))
                .toList();
    }
}
