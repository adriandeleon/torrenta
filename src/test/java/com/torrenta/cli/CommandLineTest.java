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
