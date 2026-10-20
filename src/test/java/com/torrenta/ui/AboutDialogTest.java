package com.torrenta.ui;

import java.nio.file.Path;

import com.torrenta.AppInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AboutDialogTest {

    @Test
    void detailsNameTheBuildTheEngineTheRuntimeAndTheConfigFolder() {
        String details = AboutDialog.environmentDetails(Path.of("/home/x/.torrenta/settings.properties"));
        assertTrue(details.startsWith(AppInfo.NAME + " " + AppInfo.VERSION), details);
        assertTrue(details.contains("Engine: libtorrent4j " + AppInfo.ENGINE_VERSION), details);
        assertTrue(details.contains("Java: " + System.getProperty("java.version")), details);
        assertTrue(details.contains("\nJavaFX: "), details);
        assertTrue(details.contains("OS: " + System.getProperty("os.name")), details);
        assertTrue(details.endsWith("Config: " + Path.of("/home/x/.torrenta")), details);
    }

    @Test
    void noSettingsFileNoConfigLine() {
        String details = AboutDialog.environmentDetails(null);
        assertFalse(details.contains("Config:"), details);
        assertFalse(details.endsWith("\n\n"), "no dangling blank line: " + details);
    }

    @Test
    void theIconShipsWithTheApp() {
        assertTrue(AboutDialog.class.getResource("/com/torrenta/icons/icon-128.png") != null);
    }
}
