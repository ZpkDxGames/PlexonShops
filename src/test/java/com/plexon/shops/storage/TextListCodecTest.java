package com.plexon.shops.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextListCodecTest {
    @Test
    void roundTripsFormattingUnicodeAndBlankLines() {
        List<String> input = List.of(
                "<gradient:#8CE6FF:#5BA8FF>Plexon</gradient>",
                "",
                "Olá, mundo • ★"
        );

        assertEquals(input, TextListCodec.decode(TextListCodec.encode(input)));
    }

    @Test
    void keepsLegacyUnencodedValuesReadable() {
        assertEquals(List.of("old value"), TextListCodec.decode("old value"));
    }
}
