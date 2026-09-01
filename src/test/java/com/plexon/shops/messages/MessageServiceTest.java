package com.plexon.shops.messages;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesAllowedFormattingButEscapesItWithoutPermission() throws Exception {
        Path file = temporaryDirectory.resolve("messages.yml");
        Files.createFile(file);
        MessageService messages = MessageService.load(file.toFile());

        String formatted = messages.sanitizePlayerText(
                "<gradient:#8CE6FF:#5BA8FF>Neon Shop</gradient>", true);
        String plain = messages.sanitizePlayerText("&cPlain Shop", false);

        assertEquals("Neon Shop", messages.plainStored(formatted));
        assertEquals("<red>Plain Shop", messages.plainStored(plain));
    }

    @Test
    void playerFormattingCannotCreateClickEvents() throws Exception {
        Path file = temporaryDirectory.resolve("messages.yml");
        Files.createFile(file);
        MessageService messages = MessageService.load(file.toFile());

        Component component = messages.stored("<click:run_command:'/op @s'>Danger</click>");

        assertNoClickEvent(component);
    }

    private void assertNoClickEvent(Component component) {
        assertNull(component.clickEvent());
        component.children().forEach(this::assertNoClickEvent);
    }
}
