package com.torrenta.os;

import java.io.IOException;
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

class HandlersTest {

    @Test
    void thePlatformIsReadOffOsName() {
        assertEquals(Handlers.Platform.LINUX, Handlers.platform("Linux"));
        assertEquals(Handlers.Platform.MAC, Handlers.platform("Mac OS X"));
        assertEquals(Handlers.Platform.WINDOWS, Handlers.platform("Windows 11"));
        assertEquals(Handlers.Platform.LINUX, Handlers.platform(null), "anything else is Linux's way");
    }

    @Test
    void applicationDirsFollowTheXdgSpecification() {
        List<Path> dirs = Handlers.applicationDirs(Map.of(), "/home/x");
        assertEquals(
                List.of(
                        Path.of("/home/x", ".local", "share", "applications"),
                        Path.of("/usr/local/share", "applications"),
                        Path.of("/usr/share", "applications")),
                dirs);
        List<Path> custom =
                Handlers.applicationDirs(Map.of("XDG_DATA_HOME", "/data/home", "XDG_DATA_DIRS", "/a:/b:"), "/home/x");
        assertEquals(
                List.of(
                        Path.of("/data/home", "applications"),
                        Path.of("/a", "applications"),
                        Path.of("/b", "applications")),
                custom);
    }

    /** The installed entry is found by what it says, not by what the package called it. */
    @Test
    void theDesktopEntryIsFoundByItsWmClass(@TempDir Path dir) throws IOException {
        Path apps = dir.resolve("applications");
        Files.createDirectories(apps);
        Files.writeString(apps.resolve("other.desktop"), "[Desktop Entry]\nName=Other\nStartupWMClass=other.App\n");
        assertEquals(Optional.empty(), Handlers.desktopId(List.of(apps, dir.resolve("missing"))));
        Files.writeString(
                apps.resolve("torrenta-Torrenta.desktop"),
                "[Desktop Entry]\nName=Torrenta\nStartupWMClass=" + Handlers.WM_CLASS + "\n");
        assertEquals(
                Optional.of("torrenta-Torrenta.desktop"), Handlers.desktopId(List.of(dir.resolve("missing"), apps)));
    }

    @Test
    void theLinuxCommandIsXdgMimeDefaultForBothTypes() {
        assertEquals(
                List.of(
                        "xdg-mime",
                        "default",
                        "torrenta-Torrenta.desktop",
                        "application/x-bittorrent",
                        "x-scheme-handler/magnet"),
                Handlers.xdgMimeDefault("torrenta-Torrenta.desktop"));
    }

    @Test
    void theWindowsCommandsWriteTheProgIdAndTheProtocolUnderTheUsersKey() {
        Path exe = Path.of("C:", "Users", "x", "AppData", "Local", "Torrenta", "Torrenta.exe");
        List<List<String>> commands = Handlers.windowsRegister(exe);
        assertEquals(8, commands.size());
        for (List<String> argv : commands) {
            assertEquals(List.of("reg", "add"), argv.subList(0, 2));
            assertTrue(argv.get(2).startsWith("HKCU\\Software\\Classes\\"), argv.get(2));
            assertEquals("/f", argv.get(argv.size() - 1), "never a prompt");
        }
        assertEquals(
                List.of(
                        "reg",
                        "add",
                        "HKCU\\Software\\Classes\\.torrent",
                        "/ve",
                        "/t",
                        "REG_SZ",
                        "/d",
                        "Torrenta.TorrentFile",
                        "/f"),
                commands.get(0));
        assertEquals(
                List.of(
                        "reg",
                        "add",
                        "HKCU\\Software\\Classes\\magnet",
                        "/v",
                        "URL Protocol",
                        "/t",
                        "REG_SZ",
                        "/d",
                        "",
                        "/f"),
                commands.get(5),
                "the marker Windows requires of a URL scheme");
        String command = "\"" + exe + "\" \"%1\"";
        assertEquals(command, commands.get(3).get(7), "the open command quotes the launcher and the argument");
        assertEquals(command, commands.get(7).get(7));
        assertEquals(exe + ",0", commands.get(2).get(7), "the launcher's own icon");

        List<List<String>> undo = Handlers.windowsUnregister();
        assertEquals(List.of("reg", "delete", "HKCU\\Software\\Classes\\magnet", "/f"), undo.get(0));
        assertEquals(List.of("reg", "delete", "HKCU\\Software\\Classes\\Torrenta.TorrentFile", "/f"), undo.get(1));
        assertEquals(List.of("reg", "delete", "HKCU\\Software\\Classes\\.torrent", "/ve", "/f"), undo.get(2));
    }

    @Test
    void onlyTheInstalledLauncherIsRegistered() {
        assertEquals(
                Optional.of(Path.of("C:", "Torrenta", "Torrenta.exe")),
                Handlers.launcher(
                        Optional.of(Path.of("C:", "Torrenta", "Torrenta.exe").toString())));
        assertEquals(
                Optional.of(Path.of("/opt/torrenta/bin/Torrenta")),
                Handlers.launcher(Optional.of("/opt/torrenta/bin/Torrenta")));
        assertEquals(Optional.empty(), Handlers.launcher(Optional.of("/usr/lib/jvm/bin/java")), "a development run");
        assertEquals(Optional.empty(), Handlers.launcher(Optional.empty()));
    }

    @Test
    void applyRunsWhatThePlatformNeedsAndSaysWhatHappened() {
        List<List<String>> ran = new ArrayList<>();
        Handlers.Runner record = argv -> {
            ran.add(argv);
            return 0;
        };
        Handlers.Outcome linux = Handlers.apply(
                true, Handlers.Platform.LINUX, Optional.of("torrenta-Torrenta.desktop"), Optional.empty(), record);
        assertTrue(linux.ok(), linux.text());
        assertEquals(List.of(Handlers.xdgMimeDefault("torrenta-Torrenta.desktop")), ran);
        assertTrue(linux.text().contains("xdg-mime"), linux.text());

        ran.clear();
        Handlers.Outcome notInstalled =
                Handlers.apply(true, Handlers.Platform.LINUX, Optional.empty(), Optional.empty(), record);
        assertTrue(notInstalled.skipped(), "a development run: said from Settings, quiet at start");
        assertTrue(ran.isEmpty(), "nothing to run without an entry");

        Handlers.Outcome linuxOff =
                Handlers.apply(false, Handlers.Platform.LINUX, Optional.of("x.desktop"), Optional.empty(), record);
        assertTrue(linuxOff.skipped());
        assertTrue(ran.isEmpty(), "xdg-mime has no undefault");

        Path exe = Path.of("C:", "Torrenta", "Torrenta.exe");
        Handlers.Outcome windows =
                Handlers.apply(true, Handlers.Platform.WINDOWS, Optional.empty(), Optional.of(exe), record);
        assertTrue(windows.ok() && !windows.skipped(), windows.text());
        assertEquals(Handlers.windowsRegister(exe), ran);
        ran.clear();
        Handlers.Outcome windowsOff =
                Handlers.apply(false, Handlers.Platform.WINDOWS, Optional.empty(), Optional.of(exe), record);
        assertTrue(windowsOff.ok() && !windowsOff.skipped());
        assertEquals(Handlers.windowsUnregister(), ran);
        ran.clear();
        assertTrue(Handlers.apply(true, Handlers.Platform.WINDOWS, Optional.empty(), Optional.empty(), record)
                .skipped());
        assertTrue(ran.isEmpty());

        Handlers.Outcome mac = Handlers.apply(true, Handlers.Platform.MAC, Optional.empty(), Optional.empty(), record);
        assertTrue(mac.skipped());
        assertTrue(ran.isEmpty(), "the bundle declares it; nothing runs");
        assertTrue(mac.text().contains("Open with"), mac.text());

        Handlers.Runner failing = argv -> 1;
        Handlers.Outcome failed =
                Handlers.apply(true, Handlers.Platform.LINUX, Optional.of("x.desktop"), Optional.empty(), failing);
        assertFalse(failed.ok());
        assertFalse(failed.skipped());
        assertTrue(failed.text().contains("exit 1"), failed.text());
        Handlers.Runner missing = argv -> {
            throw new IOException("No such file");
        };
        assertFalse(Handlers.apply(true, Handlers.Platform.LINUX, Optional.of("x.desktop"), Optional.empty(), missing)
                .ok());
    }

    /** The packages declare the types; these are the files the build hands jpackage. */
    @Test
    void thePackagingResourcesDeclareBothTypes() throws IOException {
        String desktop = Files.readString(Path.of("packaging", "linux", "Torrenta.desktop"));
        assertTrue(
                desktop.lines().anyMatch(l -> l.equals("MimeType=application/x-bittorrent;x-scheme-handler/magnet;")),
                desktop);
        assertTrue(
                desktop.lines().anyMatch(l -> l.equals("Exec=APPLICATION_LAUNCHER %U")),
                "the files and links go on the command line");
        assertTrue(
                desktop.lines().anyMatch(l -> l.equals("StartupWMClass=" + Handlers.WM_CLASS)),
                "what desktopId looks for");

        java.util.Properties association = new java.util.Properties();
        try (var in = Files.newInputStream(Path.of("packaging", "torrent-file.properties"))) {
            association.load(in);
        }
        assertEquals("torrent", association.getProperty("extension"));
        assertEquals(Handlers.MIME_TORRENT, association.getProperty("mime-type"));
        assertFalse(association.getProperty("description", "").isBlank());

        String macScript = Files.readString(Path.of("scripts", "add-mac-types.sh"));
        assertTrue(macScript.contains("CFBundleDocumentTypes"), "the plist patch adds the document type");
        assertTrue(macScript.lines().anyMatch(l -> l.contains("CFBundleTypeExtensions:0 string torrent")), macScript);
        assertTrue(macScript.contains("CFBundleURLTypes"), "and the URL type");
        assertTrue(macScript.contains("CFBundleURLSchemes"), macScript);
        assertTrue(macScript.lines().anyMatch(l -> l.contains("magnet")), "for magnet links");
        assertTrue(macScript.contains("codesign"), "the edited bundle is re-signed");

        String installers = Files.readString(Path.of("scripts", "build-installers.sh"));
        assertTrue(installers.contains("--file-associations"), "the installers carry the .torrent association");
    }
}
