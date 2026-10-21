package com.torrenta.tray;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraysTest {

    /**
     * App.main decides java.awt.headless from this before any AWT class loads. Linux's tray is
     * D-Bus and never needs AWT headful; the others do, but only when a tray is wanted at all.
     */
    @Test
    void awtIsHeadfulOnlyWhereTheTrayIsAwtsAndWanted() {
        assertFalse(Trays.usesAwt(true, "Linux"));
        assertFalse(Trays.usesAwt(false, "Linux"));
        assertTrue(Trays.usesAwt(true, "Mac OS X"));
        assertTrue(Trays.usesAwt(true, "Windows 11"));
        assertFalse(Trays.usesAwt(false, "Mac OS X"), "the sibling applications' guard stays");
        assertFalse(Trays.usesAwt(false, "Windows 11"));
        assertTrue(Trays.usesAwt(true, null), "an unknown platform is not Linux");
    }

    /** Both backends ask for several sizes; each must come back square at the size asked. */
    @Test
    void theIconRendersAtAnySize() {
        for (int size : new int[] {16, 22, 24, 32, 48}) {
            BufferedImage image = TrayIconRenderer.render(TrayDisplay.idle(), size);
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
            assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType(), "SNI wants ARGB");
            assertTrue(opaquePixels(image) > size * size / 10, "something is drawn at " + size);
        }
    }

    /** The state is the arrow's colour: three states, three colours; the tray stays periwinkle. */
    @Test
    void theArrowCarriesTheState() {
        Color idle = TrayIconRenderer.colorFor(TrayDisplay.idle());
        Color downloading = TrayIconRenderer.colorFor(new TrayDisplay(true, false));
        Color attention = TrayIconRenderer.colorFor(new TrayDisplay(true, true));
        assertNotEquals(idle, downloading);
        assertNotEquals(downloading, attention);
        assertNotEquals(idle, attention);
        assertEquals(attention, TrayIconRenderer.colorFor(new TrayDisplay(false, true)), "attention wins");

        // The shaft's centre is the arrow; a point on the tray's bottom edge is the tray.
        int size = 48;
        BufferedImage a = TrayIconRenderer.render(TrayDisplay.idle(), size);
        BufferedImage b = TrayIconRenderer.render(new TrayDisplay(true, false), size);
        int shaftX = size / 2;
        int shaftY = (int) (size * 0.18); // the shaft's top third, clear of the head
        assertNotEquals(a.getRGB(shaftX, shaftY), b.getRGB(shaftX, shaftY), "the arrow changes colour");
        int trayY = size - (int) (size * 0.06) - 1; // the tray's bottom stroke, centred
        assertEquals(a.getRGB(shaftX, trayY), b.getRGB(shaftX, trayY), "the tray does not");
        assertTrue((a.getRGB(shaftX, trayY) >>> 24) > 200, "the tray's stroke is there");
    }

    private static int opaquePixels(BufferedImage image) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 128) n++;
            }
        }
        return n;
    }
}
