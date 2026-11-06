package com.torrenta.os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The entry's life on a home folder of the test's own, and on Windows against a stand-in registry. */
class AutostartTest {

    @TempDir
    Path home;

    private static final String LAUNCHER = "/opt/torrenta/bin/Torrenta";

    private Autostart on(Handlers.Platform platform, Optional<String> launcher, Map<String, String> env) {
        return new Autostart(platform, launcher, home, env, argv -> {
            throw new AssertionError("no command on " + platform + ": " + argv);
        });
    }

    @Test
    void aRunFromSourceHasNothingToStart() {
        Autostart entry = on(Handlers.Platform.LINUX, Optional.empty(), Map.of());
        assertFalse(entry.available());
        assertEquals(Autostart.Result.NOT_INSTALLED, entry.state());
        assertEquals(Autostart.Result.NOT_INSTALLED, entry.set(true, true));
        assertFalse(Files.exists(home.resolve(".config")));
        assertEquals(
                Autostart.Result.NOT_INSTALLED,
                on(Handlers.Platform.LINUX, Optional.of(""), Map.of()).state());
    }

    @Test
    void theHeadlessLauncherIsNotStartedAtLogin() {
        Autostart entry = on(Handlers.Platform.LINUX, Optional.of("/opt/torrenta/bin/torrenta-nox"), Map.of());
        assertFalse(entry.available());
    }

    @Test
    void onLinuxTheEntryIsWrittenReadAndRemoved() throws IOException {
        Autostart entry = on(Handlers.Platform.LINUX, Optional.of(LAUNCHER), Map.of());
        Path file = home.resolve(".config/autostart/torrenta.desktop");
        assertEquals(Autostart.Result.OFF, entry.state());

        assertEquals(Autostart.Result.ON, entry.set(true, true));
        assertEquals(Autostart.Result.ON, entry.state());
        assertTrue(Files.readString(file).contains("Exec=\"" + LAUNCHER + "\" --minimized\n"));
        assertFalse(Files.exists(file.resolveSibling("torrenta.desktop.tmp")));

        assertEquals(Autostart.Result.OFF, entry.set(false, true));
        assertFalse(Files.exists(file));
        // Off twice is still off.
        assertEquals(Autostart.Result.OFF, entry.set(false, true));
    }

    @Test
    void xdgConfigHomeIsWhereTheEntryGoes() {
        Path config = home.resolve("elsewhere");
        Autostart entry =
                on(Handlers.Platform.LINUX, Optional.of(LAUNCHER), Map.of("XDG_CONFIG_HOME", config.toString()));
        entry.set(true, false);
        assertTrue(Files.isRegularFile(config.resolve("autostart/torrenta.desktop")));
        assertEquals(config.resolve("autostart/torrenta.desktop").toString(), entry.where());
    }

    @Test
    void anEntryTheDesktopTurnedOffReadsOffAndIsNotWrittenBack() throws IOException {
        Autostart entry = on(Handlers.Platform.LINUX, Optional.of(LAUNCHER), Map.of());
        entry.set(true, true);
        Path file = home.resolve(".config/autostart/torrenta.desktop");
        Files.writeString(file, Files.readString(file) + "Hidden=true\n");
        assertEquals(Autostart.Result.OFF, entry.state());
        assertFalse(entry.refresh(false));
        assertTrue(Files.readString(file).contains("Hidden=true"));
        // Ticked here again, it runs again.
        assertEquals(Autostart.Result.ON, entry.set(true, true));
    }

    @Test
    void aStartWritesAgainOnlyAnEntryThatNamesSomethingElse() throws IOException {
        Path file = home.resolve(".config/autostart/torrenta.desktop");
        on(Handlers.Platform.LINUX, Optional.of("/old/place/bin/Torrenta"), Map.of())
                .set(true, true);
        Autostart moved = on(Handlers.Platform.LINUX, Optional.of(LAUNCHER), Map.of());

        assertTrue(moved.refresh(true));
        assertTrue(Files.readString(file).contains("Exec=\"" + LAUNCHER + "\" --minimized"));
        assertFalse(moved.refresh(true));
        assertTrue(moved.refresh(false));
        assertFalse(Files.readString(file).contains("--minimized"));
    }

    @Test
    void theIconIsTheOneJpackageInstalledBesideTheLauncher() throws IOException {
        Path root = home.resolve("opt/torrenta");
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/Torrenta.png"), "png");
        String launcher = root.resolve("bin/Torrenta").toString();
        assertEquals(Optional.of(root.resolve("lib/Torrenta.png").toString()), Autostart.linuxIcon(launcher));
        assertEquals(
                Optional.empty(),
                Autostart.linuxIcon(home.resolve("nowhere/bin/Torrenta").toString()));
    }

    @Test
    void onMacTheLaunchAgentIsWrittenAndRemoved() throws IOException {
        String launcher = "/Applications/Torrenta.app/Contents/MacOS/Torrenta";
        Autostart entry = on(Handlers.Platform.MAC, Optional.of(launcher), Map.of());
        Path plist = home.resolve("Library/LaunchAgents/com.torrenta.autostart.plist");
        assertEquals(Autostart.Result.ON, entry.set(true, false));
        assertEquals(AutostartSpec.macLaunchAgent(launcher, false), Files.readString(plist, StandardCharsets.UTF_8));
        assertTrue(entry.refresh(true));
        assertEquals(Autostart.Result.OFF, entry.set(false, false));
        assertFalse(Files.exists(plist));
    }

    /** A stand-in for reg.exe: one Run value, one StartupApproved value, every call recorded. */
    private static final class Registry implements Autostart.Exec {
        String run;
        String approved;
        final List<List<String>> calls = new ArrayList<>();
        final List<String> imported = new ArrayList<>();

        @Override
        public Autostart.Answer run(List<String> argv) throws IOException {
            calls.add(argv);
            if (argv.equals(AutostartSpec.windowsQuery())) return query(run, "REG_SZ");
            if (argv.equals(AutostartSpec.windowsApprovedQuery())) return query(approved, "REG_BINARY");
            if (argv.equals(AutostartSpec.windowsRemove())) {
                boolean had = run != null;
                run = null;
                return new Autostart.Answer(had ? 0 : 1, "");
            }
            if (argv.equals(AutostartSpec.windowsApprovedRemove())) {
                approved = null;
                return new Autostart.Answer(0, "");
            }
            if (argv.size() == 3 && argv.get(1).equals("import")) {
                String reg = Files.readString(Path.of(argv.get(2)), StandardCharsets.UTF_16LE);
                assertTrue(reg.startsWith("\uFEFF"), "regedit's encoding, with its BOM");
                imported.add(reg.substring(1));
                // What reg.exe would store: the data with the file's escapes taken off.
                String line = reg.lines()
                        .filter(l -> l.startsWith("\"Torrenta\"="))
                        .findFirst()
                        .orElseThrow();
                String data = line.substring("\"Torrenta\"=\"".length(), line.length() - 1);
                run = data.replace("\\\"", "\"").replace("\\\\", "\\");
                return new Autostart.Answer(0, "");
            }
            throw new AssertionError("unexpected " + argv);
        }

        private static Autostart.Answer query(String value, String type) {
            if (value == null) return new Autostart.Answer(1, "ERROR: The system was unable to find");
            return new Autostart.Answer(
                    0, "\r\nHKEY_CURRENT_USER\\…\r\n    Torrenta    " + type + "    " + value + "\r\n");
        }
    }

    @Test
    void onWindowsTheRunValueIsImportedReadAndDeleted() {
        Registry registry = new Registry();
        String exe = "C:\\Program Files\\Torrenta\\Torrenta.exe";
        Autostart entry = new Autostart(Handlers.Platform.WINDOWS, Optional.of(exe), home, Map.of(), registry);
        assertEquals(Autostart.Result.OFF, entry.state());

        assertEquals(Autostart.Result.ON, entry.set(true, true));
        assertEquals("\"" + exe + "\" --minimized", registry.run);
        assertEquals(AutostartSpec.windowsRunRegFile(exe, true), registry.imported.getFirst());

        assertFalse(entry.refresh(true));
        assertTrue(entry.refresh(false));
        assertEquals("\"" + exe + "\"", registry.run);

        assertEquals(Autostart.Result.OFF, entry.set(false, true));
        assertEquals(null, registry.run);
        // Deleting a value that is not there is off, not a failure.
        assertEquals(Autostart.Result.OFF, entry.set(false, true));
    }

    @Test
    void onWindowsTaskManagersDisabledReadsOffAndTickingHereClearsIt() {
        Registry registry = new Registry();
        String exe = "C:\\Torrenta\\Torrenta.exe";
        Autostart entry = new Autostart(Handlers.Platform.WINDOWS, Optional.of(exe), home, Map.of(), registry);
        entry.set(true, true);
        registry.approved = "030000000000000000000000";
        assertEquals(Autostart.Result.OFF, entry.state());
        assertFalse(entry.refresh(false), "one turned off is left off");

        assertEquals(Autostart.Result.ON, entry.set(true, true));
        assertEquals(null, registry.approved);
    }

    @Test
    void aFailedImportSaysSo() {
        Autostart entry = new Autostart(
                Handlers.Platform.WINDOWS,
                Optional.of("C:\\Torrenta\\Torrenta.exe"),
                home,
                Map.of(),
                argv -> new Autostart.Answer(argv.get(1).equals("import") ? 5 : 1, ""));
        assertEquals(Autostart.Result.FAILED, entry.set(true, true));
    }
}
