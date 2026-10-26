package com.torrenta.os;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Whether the network in use is metered: NetworkManager's {@code Metered} on Linux (read over the
 * system bus by {@link SystemConditions}), Windows' connection cost through PowerShell. macOS has
 * no command for Low Data Mode, so it is never asked there ({@link Conditions#seesMetered}). The
 * parsing is pure and tested.
 */
public final class NetworkCost {

    private NetworkCost() {}

    /**
     * NetworkManager's {@code NMMetered}: 1 yes, 3 guessed yes (a phone's hotspot); 0 unknown, 2
     * no, 4 guessed no. Pure.
     */
    public static boolean networkManager(long metered) {
        return metered == 1 || metered == 3;
    }

    /**
     * Windows Runtime's {@code NetworkCostType} as PowerShell prints it: {@code Fixed} and {@code
     * Variable} are paid by the byte, {@code Unrestricted} is not, {@code Unknown} (or no
     * connection) says nothing. Empty for output that is none of these.
     */
    public static Optional<Boolean> windows(String output) {
        String t = output == null ? "" : output.trim().toLowerCase(Locale.ROOT);
        if (t.equals("fixed") || t.equals("variable")) return Optional.of(true);
        if (t.equals("unrestricted") || t.equals("unknown") || t.equals("none")) return Optional.of(false);
        return Optional.empty();
    }

    /**
     * The query, for Windows PowerShell (5.1, which projects Windows Runtime types; PowerShell 7
     * does not): the internet profile's cost type, or "None" without one. No shell: arguments.
     */
    static List<String> windowsCommand() {
        String script = "$ErrorActionPreference='Stop';"
                + "[void][Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime];"
                + "$p=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile();"
                + "if($p){$p.GetConnectionCost().NetworkCostType}else{'None'}";
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
    }
}
