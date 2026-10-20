package com.torrenta.automation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The platform's notifier, chosen by {@code os.name}. Each is a short program run with the title
 * and message as arguments: {@code notify-send} on Linux (and anything else Unix-like),
 * {@code osascript} with {@code display notification} on macOS, and on Windows a PowerShell script
 * of ours that raises a toast through the {@code Windows.UI.Notifications} API and reads its text
 * from its parameters.
 */
public final class Notifiers {

    static final String APP_NAME = "Torrenta";

    /** The toast script, a resource of the module, unpacked once per run. */
    private static final class Script {
        static final Path PATH = unpack();

        private static Path unpack() {
            try (InputStream in = Notifiers.class.getResourceAsStream("toast.ps1")) {
                if (in == null) throw new IOException("toast.ps1 is missing from the application");
                Path file = Files.createTempFile("torrenta-toast", ".ps1");
                Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                file.toFile().deleteOnExit();
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private Notifiers() {}

    /** The notifier for this machine. */
    public static Notifier forPlatform() {
        return forPlatform(System.getProperty("os.name", ""));
    }

    /** The notifier for an {@code os.name}; Windows and macOS have their own, the rest is Linux's. */
    public static Notifier forPlatform(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new ProcessNotifier("PowerShell", (t, m) -> windowsArgv(Script.PATH, t, m));
        if (os.contains("mac") || os.contains("darwin")) return new ProcessNotifier("osascript", Notifiers::macArgv);
        return new ProcessNotifier("notify-send", Notifiers::linuxArgv);
    }

    /** {@code --} ends the options, so a message that starts with a dash is still a message. */
    static List<String> linuxArgv(String title, String message) {
        return List.of("notify-send", "--app-name=" + APP_NAME, "--", title, message);
    }

    /**
     * The script takes its text from {@code argv}: the title and message are arguments of
     * {@code osascript}, never part of the script, so no quoting of theirs can break out of it.
     */
    static List<String> macArgv(String title, String message) {
        return List.of(
                "osascript",
                "-e",
                "on run argv",
                "-e",
                "display notification (item 2 of argv) with title (item 1 of argv)",
                "-e",
                "end run",
                title,
                message);
    }

    /** No profile, no prompt, the text as the script's two parameters. */
    static List<String> windowsArgv(Path script, String title, String message) {
        return List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
                "-Title",
                title,
                "-Message",
                message);
    }
}
