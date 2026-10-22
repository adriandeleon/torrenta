package com.torrenta.ui;

import java.util.List;
import java.util.Optional;

import com.torrenta.ui.WindowGeometry.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowGeometryTest {

    private static final Bounds LAPTOP = new Bounds(0, 0, 1366, 738);
    private static final Bounds DESK = new Bounds(0, 32, 1920, 1048);
    /** A second monitor to the right of the desk one. */
    private static final Bounds RIGHT = new Bounds(1920, 0, 1920, 1080);

    @Test
    void boundsSurviveTheRoundTripAsWholePixels() {
        Bounds b = new Bounds(100.4, 120.6, 1000, 640.2);
        assertEquals("100,121,1000,640", WindowGeometry.format(b));
        assertEquals(Optional.of(new Bounds(100, 121, 1000, 640)), WindowGeometry.parse("100,121,1000,640"));
        assertEquals(Optional.of(new Bounds(-1800, 40, 1200, 800)), WindowGeometry.parse(" -1800, 40 ,1200,800"));
    }

    @Test
    void aHandEditedOrDamagedValueIsNoValue() {
        for (String bad : new String[] {null, "", "  ", "1,2,3", "1,2,3,4,5", "a,b,c,d", "0,0,NaN,600", "0,0,100,100"})
            assertEquals(Optional.empty(), WindowGeometry.parse(bad), String.valueOf(bad));
    }

    @Test
    void aFirstWindowIsWideEnoughForTheColumnsAndCentred() {
        Bounds b = WindowGeometry.firstRun(1136, 680, DESK);
        assertEquals(new Bounds(392, 216, 1136, 680), b);
    }

    @Test
    void aFirstWindowIsNeverSmallerThanTheOldDefault() {
        Bounds b = WindowGeometry.firstRun(700, 400, DESK);
        assertEquals(WindowGeometry.FALLBACK_WIDTH, b.width());
        assertEquals(WindowGeometry.FALLBACK_HEIGHT, b.height());
    }

    @Test
    void aFirstWindowOnASmallScreenTakesAtMostNinetyPercent() {
        Bounds b = WindowGeometry.firstRun(1400, 680, LAPTOP);
        assertEquals(Math.floor(1366 * 0.9), b.width());
        assertEquals(Math.floor(738 * 0.9), b.height());
        assertTrue(b.x() >= 0 && b.maxX() <= LAPTOP.maxX());
    }

    @Test
    void savedBoundsOnAScreenComeBackAsTheyWere() {
        Bounds saved = new Bounds(100, 120, 1000, 640);
        assertEquals(Optional.of(saved), WindowGeometry.restore(saved, List.of(DESK, RIGHT)));
        Bounds onTheRight = new Bounds(2100, 100, 1000, 640);
        assertEquals(Optional.of(onTheRight), WindowGeometry.restore(onTheRight, List.of(DESK, RIGHT)));
    }

    @Test
    void aWindowOnAMonitorThatIsGoneOpensAFirstWindowInstead() {
        Bounds onTheRight = new Bounds(2100, 100, 1000, 640);
        assertEquals(Optional.empty(), WindowGeometry.restore(onTheRight, List.of(DESK)));
        Bounds chosen = WindowGeometry.choose(Optional.of(onTheRight), List.of(DESK), DESK, 1136, 680);
        assertEquals(WindowGeometry.firstRun(1136, 680, DESK), chosen);
    }

    @Test
    void aWindowHangingOffTheEdgeIsMovedBackOn() {
        // Mostly on the desk screen, its right edge past the end of it, its title under the top bar.
        Bounds saved = new Bounds(1200, 10, 1000, 640);
        assertEquals(Optional.of(new Bounds(920, 32, 1000, 640)), WindowGeometry.restore(saved, List.of(DESK)));
    }

    @Test
    void onlyASliverOnScreenDoesNotCount() {
        Bounds saved = new Bounds(1900, 100, 1000, 640); // 20 px of it on the desk screen
        assertEquals(Optional.empty(), WindowGeometry.restore(saved, List.of(DESK)));
    }

    @Test
    void aWindowFromABiggerScreenIsShrunkToTheSmallerOne() {
        Bounds saved = new Bounds(0, 0, 1800, 1000);
        assertEquals(Optional.of(new Bounds(0, 0, 1366, 738)), WindowGeometry.restore(saved, List.of(LAPTOP)));
    }

    @Test
    void theScreenHoldingMostOfTheTopStripWins() {
        // Straddles the two monitors, more of it on the right one.
        Bounds saved = new Bounds(1700, 100, 1000, 640);
        Bounds restored = WindowGeometry.restore(saved, List.of(DESK, RIGHT)).orElseThrow();
        assertEquals(1920, restored.x());
    }

    @Test
    void noSavedBoundsIsAFirstWindow() {
        assertEquals(
                WindowGeometry.firstRun(1136, 680, DESK),
                WindowGeometry.choose(Optional.empty(), List.of(DESK), DESK, 1136, 680));
    }
}
