package com.torrenta.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line. */
class CommandLineTest {

    @Test
    void noArgumentsJustStarts() {
        CommandLine cli = CommandLine.parse();
        assertTrue(cli.shouldRun());
        assertTrue(cli.inputs().isEmpty());
        assertNull(cli.error());
    }

    @Test
    void helpAndVersionStopBeforeStarting() {
        for (String arg : new String[] {"-h", "--help"}) {
            assertTrue(CommandLine.parse(arg).help(), arg);
            assertFalse(CommandLine.parse(arg).shouldRun(), arg);
        }
        for (String arg : new String[] {"-v", "-V", "--version"}) {
            assertTrue(CommandLine.parse(arg).version(), arg);
            assertFalse(CommandLine.parse(arg).shouldRun(), arg);
        }
    }

    @Test
    void torrentFilesAndMagnetsAreInputsInOrder() {
        CommandLine cli = CommandLine.parse("a.torrent", "magnet:?xt=urn:btih:abc", "b.torrent");
        assertEquals(List.of("a.torrent", "magnet:?xt=urn:btih:abc", "b.torrent"), cli.inputs());
        assertTrue(cli.shouldRun());
    }

    @Test
    void magnetDetectionIgnoresCase() {
        assertTrue(CommandLine.isMagnet("MAGNET:?xt=urn:btih:abc"));
        assertFalse(CommandLine.isMagnet("file.torrent"));
    }

    @Test
    void anUnknownOptionIsAnError() {
        CommandLine cli = CommandLine.parse("--bogus");
        assertNotNull(cli.error());
        assertTrue(cli.error().contains("--bogus"));
        assertFalse(cli.shouldRun());
    }

    @Test
    void configDirInBothSpellings() {
        assertEquals("/tmp/c", CommandLine.parse("--config-dir", "/tmp/c").configDir());
        assertEquals(
                "/tmp/c", CommandLine.parse("--config-dir=/tmp/c", "a.torrent").configDir());
        assertNull(CommandLine.parse("a.torrent").configDir());
        assertNotNull(CommandLine.parse("--config-dir").error(), "a value is required");
        assertNotNull(CommandLine.parse("--config-dir=").error(), "a value is required");
    }

    @Test
    void usageNamesTheLauncher() {
        String usage = CommandLine.usage("torrenta");
        assertTrue(usage.startsWith("Usage: torrenta"));
        assertTrue(usage.contains("--version"));
        assertTrue(usage.contains("--config-dir"));
        assertTrue(usage.contains("--headless"));
        assertTrue(usage.contains("--set-remote-password"));
    }

    @Test
    void theRemotePasswordIsAFlagOfItsOwn() {
        CommandLine cli = CommandLine.parse("--set-remote-password", "--config-dir", "/srv/torrenta");
        assertTrue(cli.setRemotePassword());
        assertEquals("/srv/torrenta", cli.configDir());
        assertTrue(cli.inputs().isEmpty(), "the password is never an argument, where ps would show it");
        assertFalse(CommandLine.parse("a.torrent").setRemotePassword());
    }

    @Test
    void headlessIsAFlagBesideTheInputs() {
        CommandLine cli = CommandLine.parse("--headless", "--config-dir", "/srv/torrenta", "magnet:?xt=urn:btih:a");
        assertTrue(cli.headless());
        assertTrue(cli.shouldRun(), "headless runs; it is not an answer that stops");
        assertEquals("/srv/torrenta", cli.configDir());
        assertEquals(java.util.List.of("magnet:?xt=urn:btih:a"), cli.inputs());
        assertFalse(CommandLine.parse("a.torrent").headless());
    }

    /**
     * jpackage passes a launcher's own arguments only when none are given, so the headless
     * launcher is known by its path, with arguments or without.
     */
    @Test
    void theHeadlessLauncherIsHeadlessWhateverItIsGiven() {
        CommandLine given = CommandLine.parse("--config-dir", "/srv/torrenta");
        assertTrue(given.headless("/opt/torrenta/bin/torrenta-nox"));
        assertTrue(given.headless("C:\\Program Files\\Torrenta\\torrenta-nox.exe"));
        assertFalse(given.headless("/opt/torrenta/bin/Torrenta"), "the window's launcher");
        assertFalse(given.headless(null), "not packaged: dev-run, the tests");
        assertTrue(CommandLine.parse("--headless").headless(null));
    }

    @Test
    void reportStopsBeforeStartingAndKeepsTheConfigFolder() {
        CommandLine cli = CommandLine.parse("--report", "--config-dir", "/tmp/c");
        assertTrue(cli.report());
        assertFalse(cli.shouldRun());
        assertEquals("/tmp/c", cli.configDir());
        assertFalse(CommandLine.parse().report());
        assertTrue(CommandLine.usage("torrenta").contains("--report"));
    }
}
