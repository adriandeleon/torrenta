package com.torrenta.os;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;

/**
 * On battery or on the mains, per platform: UPower's {@code OnBattery} on Linux (read by {@link
 * SystemConditions} over the system bus), {@code pmset -g batt} on macOS, {@code
 * GetSystemPowerStatus} on Windows. The parsing is pure and tested; the reading says nothing
 * (empty) when it cannot tell, which counts as the mains.
 */
public final class PowerState {

    private PowerState() {}

    /**
     * {@code pmset -g batt}'s first line: "Now drawing from 'Battery Power'" or "'AC Power'" (or a
     * UPS's). Empty for anything else.
     */
    public static Optional<Boolean> pmset(String output) {
        if (output == null) return Optional.empty();
        String t = output.toLowerCase(Locale.ROOT);
        if (t.contains("'battery power'")) return Optional.of(true);
        if (t.contains("'ac power'") || t.contains("'ups power'")) return Optional.of(false);
        return Optional.empty();
    }

    /**
     * {@code SYSTEM_POWER_STATUS.ACLineStatus}: 0 is offline (the battery), 1 online, 255 unknown;
     * {@code BatteryFlag} 128 is "no system battery". Pure.
     */
    public static Optional<Boolean> windows(int acLineStatus, int batteryFlag) {
        if (batteryFlag == 128) return Optional.of(false);
        if (acLineStatus == 0) return Optional.of(true);
        if (acLineStatus == 1) return Optional.of(false);
        return Optional.empty();
    }

    /** Windows: {@code kernel32!GetSystemPowerStatus} through FFM; empty when it cannot be asked. */
    static Optional<Boolean> readWindows() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            MemorySegment fn = SymbolLookup.libraryLookup("kernel32", arena)
                    .find("GetSystemPowerStatus")
                    .orElse(null);
            if (fn == null) return Optional.empty();
            MethodHandle call =
                    linker.downcallHandle(fn, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            // BYTE ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag; DWORD two times.
            MemorySegment status = arena.allocate(12, 4);
            int ok = (int) call.invokeExact(status);
            if (ok == 0) return Optional.empty();
            return windows(
                    Byte.toUnsignedInt(status.get(ValueLayout.JAVA_BYTE, 0)),
                    Byte.toUnsignedInt(status.get(ValueLayout.JAVA_BYTE, 1)));
        } catch (Throwable e) {
            return Optional.empty();
        }
    }
}
