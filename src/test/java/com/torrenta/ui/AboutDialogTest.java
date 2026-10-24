package com.torrenta.ui;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import com.torrenta.AppInfo;
import com.torrenta.ui.AboutDialog.UpdateState;
import com.torrenta.ui.AboutDialog.UpdateStatus;
import com.torrenta.update.ReleaseInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static final ReleaseInfo NEWER = new ReleaseInfo("9.9.9", "https://example.org/r", "");

    @Test
    void aRunningCheckComesFirstThenANewerReleaseThenAFailure() {
        assertEquals(UpdateState.CHECKING, AboutDialog.state(new UpdateStatus(true, NEWER, "x", 5)));
        assertEquals(UpdateState.AVAILABLE, AboutDialog.state(new UpdateStatus(false, NEWER, "x", 5)));
        assertEquals(UpdateState.FAILED, AboutDialog.state(new UpdateStatus(false, null, "HTTP 500", 5)));
    }

    @Test
    void upToDateOnlyOnceACheckHasRun() {
        assertEquals(UpdateState.UP_TO_DATE, AboutDialog.state(new UpdateStatus(false, null, null, 5)));
        assertEquals(UpdateState.NOT_CHECKED, AboutDialog.state(new UpdateStatus(false, null, null, 0)));
    }

    @Test
    void theLastCheckIsALocalDateAndTimeOrNothing() {
        // 2026-10-23 18:10:15 UTC.
        String text = AboutDialog.lastChecked(1_792_779_015_000L, ZoneId.of("UTC"), Locale.US);
        assertTrue(text.startsWith("Oct 23, 2026"), text);
        assertTrue(text.contains("6:10:15"), text);
        assertNull(AboutDialog.lastChecked(0, ZoneId.of("UTC"), Locale.US));
    }

    private static final Rectangle2D SCREEN = new Rectangle2D(0, 0, 1920, 1080);

    @Test
    void thePanelSitsOverTheMiddleOfTheWindow() {
        Point2D at = AboutDialog.centred(new Rectangle2D(100, 50, 1200, 800), 600, 480, SCREEN);
        assertEquals(new Point2D(400, 210), at);
    }

    @Test
    void aWindowHalfOffTheScreenDoesNotTakeThePanelWithIt() {
        Point2D at = AboutDialog.centred(new Rectangle2D(1500, 900, 1200, 800), 600, 480, SCREEN);
        assertEquals(new Point2D(1320, 600), at);
        Point2D left = AboutDialog.centred(new Rectangle2D(-1000, -500, 1200, 800), 600, 480, SCREEN);
        assertEquals(new Point2D(0, 0), left);
    }
}
