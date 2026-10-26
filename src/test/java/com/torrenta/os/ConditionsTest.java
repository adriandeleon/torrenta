package com.torrenta.os;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionsTest {

    @Test
    void pmsetSaysWhereThePowerComesFrom() {
        assertEquals(
                Optional.of(true),
                PowerState.pmset(
                        "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=4653155)\t84%; discharging;"));
        assertEquals(
                Optional.of(false),
                PowerState.pmset("Now drawing from 'AC Power'\n -InternalBattery-0\t100%; charged;"));
        assertEquals(Optional.of(false), PowerState.pmset("Now drawing from 'UPS Power'"));
        assertEquals(Optional.empty(), PowerState.pmset(""));
        assertEquals(Optional.empty(), PowerState.pmset(null));
    }

    @Test
    void windowsPowerStatusIsReadByItsFlags() {
        assertEquals(Optional.of(true), PowerState.windows(0, 1), "offline: the battery");
        assertEquals(Optional.of(false), PowerState.windows(1, 8), "online, charging");
        assertEquals(Optional.of(false), PowerState.windows(255, 128), "no system battery: a desktop, the mains");
        assertEquals(Optional.empty(), PowerState.windows(255, 1), "unknown");
    }

    @Test
    void meteredIsYesOrAGuessedYes() {
        assertTrue(NetworkCost.networkManager(1));
        assertTrue(NetworkCost.networkManager(3), "guessed yes: a phone's hotspot");
        for (long n : new long[] {0, 2, 4, 9}) assertFalse(NetworkCost.networkManager(n), String.valueOf(n));
        assertEquals(Optional.of(true), NetworkCost.windows("Fixed\\r\\n".replace("\\r\\n", "\r\n")));
        assertEquals(Optional.of(true), NetworkCost.windows(" Variable "));
        assertEquals(Optional.of(false), NetworkCost.windows("Unrestricted"));
        assertEquals(Optional.of(false), NetworkCost.windows("None"), "no internet profile");
        assertEquals(Optional.empty(), NetworkCost.windows("The term 'x' is not recognized"));
    }

    @Test
    void macOsHasNoMeteredRowAndACaptureCanFixTheConditions() {
        assertFalse(Conditions.seesMetered("Mac OS X"));
        assertTrue(Conditions.seesMetered("Linux"));
        assertTrue(Conditions.seesMetered("Windows 11"));
        assertEquals(new Conditions(true, false), Conditions.parse("battery"));
        assertEquals(new Conditions(true, true), Conditions.parse("Battery,metered"));
        assertEquals(Conditions.NORMAL, Conditions.parse(""));
    }

    /** The real source on each CI machine reads without throwing; what it reads is the runner's. */
    @Test
    void thisMachinesSourceReads() {
        try (Conditions.Source source = SystemConditions.create()) {
            assertNotNull(source.read());
            source.onChange(() -> {});
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAnswersGetSystemPowerStatus() {
        assertTrue(PowerState.readWindows().isPresent(), "kernel32 through FFM");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macOsAnswersPmset() {
        assertTrue(PowerState.pmset(SystemConditions.run(java.util.List.of("pmset", "-g", "batt")))
                .isPresent());
    }
}
