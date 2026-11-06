package com.torrenta.os;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a start-at-login entry says, per platform: the XDG autostart desktop entry (GNOME, KDE and
 * every desktop that follows the spec), the macOS LaunchAgent, the Windows {@code HKCU\…\Run}
 * value. Pure, so the exact bytes written into a person's login configuration are tested rather
 * than found out on their desktop. Nux's {@code AutostartSpec}, with Torrenta's names, an icon for
 * KDE's list and the reading back of a person's own "off".
 */
public final class AutostartSpec {

    /** The registry value name and the desktop entry's {@code Name}. */
    public static final String NAME = "Torrenta";
    /** The autostart desktop entry's file name under {@code ~/.config/autostart}. */
    public static final String LINUX_FILE = "torrenta.desktop";
    /** The LaunchAgent's label, and its file name under {@code ~/Library/LaunchAgents} with {@code .plist}. */
    public static final String MAC_LABEL = "com.torrenta.autostart";
    /** The argument the entry adds when Torrenta should come up behind its tray icon. */
    public static final String MINIMIZED = "--minimized";

    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    /**
     * Where Task Manager's Startup tab and Settings → Apps → Startup keep a person's "disabled":
     * a binary value of the same name whose first byte is odd ({@code 03}) when it is off. The Run
     * value stays, so it alone does not say the entry runs.
     */
    static final String APPROVED_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";

    private AutostartSpec() {}

    // ---- Linux ----------------------------------------------------------------------------------

    /**
     * The XDG autostart entry. {@code Exec} is quoted (an installed launcher can sit under a path
     * with a space), {@code StartupWMClass} is the installed entry's so the running window matches
     * its launcher, and {@code X-KDE-autostart-after=panel} starts it once Plasma's panel is up, so
     * the tray icon has a host to register with when Torrenta comes up behind it.
     *
     * @param icon the installed icon (KDE's Autostart list shows it), if one was found
     */
    public static String linuxDesktopEntry(String launcher, boolean minimized, Optional<String> icon) {
        StringBuilder entry = new StringBuilder();
        entry.append("[Desktop Entry]\n");
        entry.append("Type=Application\n");
        entry.append("Name=").append(NAME).append('\n');
        entry.append("Comment=Start Torrenta when you log in\n");
        entry.append("Exec=")
                .append(desktopExecArg(launcher))
                .append(minimized ? " " + MINIMIZED : "")
                .append('\n');
        icon.ifPresent(path -> entry.append("Icon=").append(desktopString(path)).append('\n'));
        entry.append("Terminal=false\n");
        entry.append("StartupWMClass=").append(Handlers.WM_CLASS).append('\n');
        entry.append("X-GNOME-Autostart-enabled=true\n");
        entry.append("X-KDE-autostart-after=panel\n");
        return entry.toString();
    }

    /**
     * Whether an autostart entry would run: present, and neither {@code Hidden=true} (what KDE's
     * Autostart page and the spec use for "off") nor {@code X-GNOME-Autostart-enabled=false}
     * (GNOME's). Only the {@code [Desktop Entry]} group is read.
     */
    public static boolean linuxEntryRuns(String content) {
        boolean inMain = false;
        for (String raw : content.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                inMain = line.equals("[Desktop Entry]");
                continue;
            }
            if (!inMain) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip().toLowerCase(Locale.ROOT);
            if (key.equals("Hidden") && value.equals("true")) return false;
            if (key.equals("X-GNOME-Autostart-enabled") && value.equals("false")) return false;
        }
        return true;
    }

    /**
     * One quoted {@code Exec} argument, per the Desktop Entry spec. Two layers apply, in this
     * order when reading: the string-value escapes ({@code \\}, {@code \n}, …) and then the quoting
     * rules, where {@code "} {@code `} {@code $} {@code \} need a backslash. So a literal backslash
     * ends up as four, and {@code $} as {@code \\$}. {@code %} introduces a field code anywhere in
     * the line and is doubled.
     */
    static String desktopExecArg(String path) {
        StringBuilder quoted = new StringBuilder();
        for (char c : path.toCharArray()) {
            if (c == '"' || c == '`' || c == '$' || c == '\\') quoted.append('\\');
            quoted.append(c);
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : quoted.toString().toCharArray()) {
            if (c == '%') out.append("%%");
            else out.append(stringEscape(c));
        }
        return out.append('"').toString();
    }

    /** A plain string value (the icon's path): the string-value escapes alone. */
    static String desktopString(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) out.append(stringEscape(c));
        return out.toString();
    }

    private static String stringEscape(char c) {
        return switch (c) {
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\r' -> "\\r";
            default -> String.valueOf(c);
        };
    }

    // ---- macOS ----------------------------------------------------------------------------------

    /**
     * A per-user LaunchAgent that runs the launcher once at login: {@code RunAtLoad}, not a
     * kept-alive daemon, so quitting Torrenta means it stays quit.
     */
    public static String macLaunchAgent(String launcher, boolean minimized) {
        String extra = minimized ? "        <string>" + MINIMIZED + "</string>\n" : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
                "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                %s    </array>
                    <key>RunAtLoad</key>
                    <true/>
                    <key>KeepAlive</key>
                    <false/>
                    <key>ProcessType</key>
                    <string>Interactive</string>
                </dict>
                </plist>
                """.formatted(MAC_LABEL, xmlEscape(launcher), extra);
    }

    static String xmlEscape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // ---- Windows --------------------------------------------------------------------------------

    /** The Run value's data: the launcher quoted, so a space in Program Files still runs one program. */
    public static String windowsCommand(String launcher, boolean minimized) {
        return "\"" + launcher + "\"" + (minimized ? " " + MINIMIZED : "");
    }

    /**
     * A {@code .reg} file that sets the Run value, for {@code reg import}.
     *
     * <p>Not {@code reg add /d "\"C:\…\Torrenta.exe\" --minimized"}: that argument holds a space and
     * quotes of its own, so the JDK's Windows process launcher re-quotes it and reg.exe receives a
     * mangled value (Nux found it). A file sidesteps command-line quoting; its only escapes are a
     * backslash before {@code \} and {@code "}. Lines end in CRLF; the caller writes it as UTF-16LE
     * with a BOM, the encoding regedit itself exports, so a non-ASCII user name in the path
     * survives.
     */
    public static String windowsRunRegFile(String launcher, boolean minimized) {
        return "Windows Registry Editor Version 5.00\r\n"
                + "\r\n"
                + "[" + RUN_KEY.replace("HKCU\\", "HKEY_CURRENT_USER\\") + "]\r\n"
                + "\"" + regEscape(NAME) + "\"=\"" + regEscape(windowsCommand(launcher, minimized)) + "\"\r\n";
    }

    static String regEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Applies a file from {@link #windowsRunRegFile}; a plain path argument, no quotes of its own. */
    public static List<String> windowsImport(String regFile) {
        return List.of("reg", "import", regFile);
    }

    public static List<String> windowsRemove() {
        return List.of("reg", "delete", RUN_KEY, "/v", NAME, "/f");
    }

    public static List<String> windowsQuery() {
        return List.of("reg", "query", RUN_KEY, "/v", NAME);
    }

    public static List<String> windowsApprovedQuery() {
        return List.of("reg", "query", APPROVED_KEY, "/v", NAME);
    }

    /**
     * Forgets a "disabled" from Task Manager, so turning it on here turns it on: with the value
     * gone Windows treats the Run entry as enabled. Its exit is not looked at (none is the usual).
     */
    public static List<String> windowsApprovedRemove() {
        return List.of("reg", "delete", APPROVED_KEY, "/v", NAME, "/f");
    }

    /**
     * The data of {@code reg query}'s one value line ({@code    Torrenta    REG_SZ    "C:\…" --minimized}),
     * or empty when the output has none of ours.
     */
    public static Optional<String> regQueryData(String output) {
        for (String line : output.lines().toList()) {
            String stripped = line.strip();
            if (!stripped.startsWith(NAME)) continue;
            List<String> parts = new ArrayList<>(List.of(stripped.split("\\s{2,}|\\t", 3)));
            if (parts.size() == 3 && parts.get(0).equals(NAME) && parts.get(1).startsWith("REG_")) {
                return Optional.of(parts.get(2).strip());
            }
            if (parts.size() == 2 && parts.get(0).equals(NAME) && parts.get(1).startsWith("REG_")) {
                return Optional.of("");
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a {@code StartupApproved\Run} value says "disabled": its first byte odd ({@code 03},
     * {@code 07}), where {@code 02} and {@code 06} are on. Data that does not read is not a "no".
     */
    public static boolean approvedSaysDisabled(String hexData) {
        String hex = hexData.strip();
        if (hex.length() < 2) return false;
        try {
            return (Integer.parseInt(hex.substring(0, 2), 16) & 1) == 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
