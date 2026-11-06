package com.torrenta.os;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutostartSpecTest {

    @Test
    void theLinuxEntryQuotesTheLauncherAndMatchesTheInstalledEntry() {
        String entry = AutostartSpec.linuxDesktopEntry("/opt/torrenta/bin/Torrenta", true, Optional.empty());
        assertTrue(entry.startsWith("[Desktop Entry]\n"));
        assertTrue(entry.contains("\nExec=\"/opt/torrenta/bin/Torrenta\" --minimized\n"));
        // Without the installed entry's WM class the window falls back to a generic icon.
        assertTrue(entry.contains("\nStartupWMClass=" + Handlers.WM_CLASS + "\n"));
        // Plasma starts it once the panel (the tray's host) is up.
        assertTrue(entry.contains("\nX-KDE-autostart-after=panel\n"));
        assertFalse(entry.contains("Icon="));
        assertFalse(AutostartSpec.linuxDesktopEntry("/opt/torrenta/bin/Torrenta", false, Optional.empty())
                .contains("--minimized"));
    }

    @Test
    void theLinuxEntryNamesTheIconWhenThereIsOne() {
        String entry = AutostartSpec.linuxDesktopEntry(
                "/opt/torrenta/bin/Torrenta", false, Optional.of("/opt/torrenta/lib/Torrenta.png"));
        assertTrue(entry.contains("\nIcon=/opt/torrenta/lib/Torrenta.png\n"));
    }

    @Test
    void theLinuxEntrySurvivesAPathWithSpaces() {
        String entry = AutostartSpec.linuxDesktopEntry("/home/a b/Torrenta/bin/Torrenta", false, Optional.empty());
        assertTrue(entry.contains("Exec=\"/home/a b/Torrenta/bin/Torrenta\"\n"));
    }

    @Test
    void execFollowsTheDesktopEntryQuotingRules() {
        // Each reserved character gets the quoting backslash, and then every backslash is doubled
        // again by the string-value layer the spec applies first on reading.
        assertEquals("\"/a\\\\\"b\"", AutostartSpec.desktopExecArg("/a\"b"));
        assertEquals("\"/a\\\\`b\"", AutostartSpec.desktopExecArg("/a`b"));
        assertEquals("\"/a\\\\$HOME\"", AutostartSpec.desktopExecArg("/a$HOME"));
        assertEquals("\"/a\\\\\\\\b\"", AutostartSpec.desktopExecArg("/a\\b"));
        // % starts a field code (%f, %u …) even inside quotes.
        assertEquals("\"/opt/100%%/Torrenta\"", AutostartSpec.desktopExecArg("/opt/100%/Torrenta"));
        assertEquals("\"/a\\nb\"", AutostartSpec.desktopExecArg("/a\nb"));
        assertEquals("\"/it's here\"", AutostartSpec.desktopExecArg("/it's here"));
        assertEquals("/a\\\\b", AutostartSpec.desktopString("/a\\b"));
    }

    @Test
    void anEntryTurnedOffByTheDesktopDoesNotRun() {
        String ours = AutostartSpec.linuxDesktopEntry("/opt/torrenta/bin/Torrenta", true, Optional.empty());
        assertTrue(AutostartSpec.linuxEntryRuns(ours));
        // KDE's Autostart page and the spec's own "deleted".
        assertFalse(AutostartSpec.linuxEntryRuns(ours + "Hidden=true\n"));
        assertFalse(AutostartSpec.linuxEntryRuns(ours + "Hidden = True\n"));
        // GNOME's.
        assertFalse(AutostartSpec.linuxEntryRuns(
                ours.replace("X-GNOME-Autostart-enabled=true", "X-GNOME-Autostart-enabled=false")));
        // Only the main group counts.
        assertTrue(AutostartSpec.linuxEntryRuns(ours + "[Desktop Action quit]\nHidden=true\n"));
    }

    @Test
    void theLaunchAgentRunsOnceAtLoginAndEscapesThePath() {
        String plist = AutostartSpec.macLaunchAgent("/Applications/T & T.app/Contents/MacOS/Torrenta", true);
        assertTrue(plist.contains("<string>" + AutostartSpec.MAC_LABEL + "</string>"));
        assertTrue(plist.contains("<string>/Applications/T &amp; T.app/Contents/MacOS/Torrenta</string>"));
        assertTrue(plist.contains("<string>--minimized</string>"));
        assertTrue(plist.contains("<key>RunAtLoad</key>\n    <true/>"));
        assertTrue(plist.contains("<key>KeepAlive</key>\n    <false/>"));
        assertFalse(AutostartSpec.macLaunchAgent("/x", false).contains("--minimized"));
    }

    @Test
    void theRegFileQuotesTheLauncherAndEscapesForRegedit() {
        String reg = AutostartSpec.windowsRunRegFile("C:\\Program Files\\Torrenta\\Torrenta.exe", true);
        assertTrue(reg.startsWith("Windows Registry Editor Version 5.00\r\n\r\n"));
        assertTrue(reg.contains("[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n"));
        assertTrue(reg.contains(
                "\"Torrenta\"=\"\\\"C:\\\\Program Files\\\\Torrenta\\\\Torrenta.exe\\\" --minimized\"\r\n"));
        assertEquals(
                "\"C:\\Program Files\\Torrenta\\Torrenta.exe\"",
                AutostartSpec.windowsCommand("C:\\Program Files\\Torrenta\\Torrenta.exe", false));
    }

    @Test
    void theRegCommandsArePlainArgv() {
        assertEquals(List.of("reg", "import", "C:\\t\\a.reg"), AutostartSpec.windowsImport("C:\\t\\a.reg"));
        assertEquals(
                List.of("reg", "delete", AutostartSpec.RUN_KEY, "/v", "Torrenta", "/f"), AutostartSpec.windowsRemove());
        assertEquals(List.of("reg", "query", AutostartSpec.RUN_KEY, "/v", "Torrenta"), AutostartSpec.windowsQuery());
    }

    @Test
    void aRegQueryAnswerIsReadForItsData() {
        String answer = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\r\n"
                + "    Torrenta    REG_SZ    \"C:\\Program Files\\Torrenta\\Torrenta.exe\" --minimized\r\n\r\n";
        assertEquals(
                Optional.of("\"C:\\Program Files\\Torrenta\\Torrenta.exe\" --minimized"),
                AutostartSpec.regQueryData(answer));
        assertEquals(Optional.empty(), AutostartSpec.regQueryData("ERROR: The system was unable to find"));
        assertEquals(Optional.empty(), AutostartSpec.regQueryData("    TorrentaOld    REG_SZ    x"));
    }

    @Test
    void taskManagersDisabledIsAnOddFirstByte() {
        assertTrue(AutostartSpec.approvedSaysDisabled("030000005A1B2C3D4E5F6071"));
        assertTrue(AutostartSpec.approvedSaysDisabled("07000000"));
        assertFalse(AutostartSpec.approvedSaysDisabled("020000000000000000000000"));
        assertFalse(AutostartSpec.approvedSaysDisabled("06000000"));
        assertFalse(AutostartSpec.approvedSaysDisabled(""));
        assertFalse(AutostartSpec.approvedSaysDisabled("zz"));
    }
}
