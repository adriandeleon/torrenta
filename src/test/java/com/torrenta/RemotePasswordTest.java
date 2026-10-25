package com.torrenta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.torrenta.store.Settings;
import com.torrenta.webapi.Passwords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code --set-remote-password} with no terminal: one line of stdin, kept as its hash. */
class RemotePasswordTest {

    @TempDir
    Path dir;

    private int run(String stdin, ByteArrayOutputStream out) {
        return RemotePassword.run(
                dir,
                null,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void aLineOfStdinBecomesTheHashAndNeverThePassword() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, run("hunter2\n", out));
        String file = Files.readString(dir.resolve(Settings.FILE_NAME));
        assertFalse(file.contains("hunter2"));
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.load();
        assertTrue(Passwords.verify("hunter2".toCharArray(), settings.webApi().passwordHash()));
        assertFalse(settings.webApi().enabled(), "the switch stays the person's");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("webapi.enabled=true"));
    }

    @Test
    void anEmptyPasswordChangesNothing() {
        assertEquals(1, run("\n", new ByteArrayOutputStream()));
        assertEquals(1, run("", new ByteArrayOutputStream()));
        assertFalse(Files.exists(dir.resolve(Settings.FILE_NAME)));
    }
}
