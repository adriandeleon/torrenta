package com.torrenta.os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Registering Torrenta as the program for {@code .torrent} files and {@code magnet:} links.
 *
 * <p>The packages do the declaring: the Linux desktop entry lists both types, the macOS bundle's
 * {@code Info.plist} carries a document type and a URL type, the Windows installer associates the
 * extension. What is left to the running program is the <em>default</em>: on Linux a
 * {@code xdg-mime default} for the two types naming our desktop entry, on Windows the
 * {@code magnet} protocol under {@code HKCU\Software\Classes} (the installer cannot declare a URL
 * scheme) and the extension's default pointing at a ProgID of ours. macOS keeps the choice itself:
 * the bundle is a candidate the moment it is installed, and Finder's Get Info → Open with picks
 * among candidates; nothing is written. Every command is built here as plain argv, so the tests
 * read what would run, and {@link #apply} runs them through a {@link Runner} the tests replace.
 */
public final class Handlers {

    public static final String MIME_TORRENT = "application/x-bittorrent";
    public static final String SCHEME_MAGNET = "x-scheme-handler/magnet";
    /** What the desktop entry says, and what the scan for it looks for. */
    public static final String WM_CLASS = "com.torrenta.App";
    /** jpackage names the installed entry {@code <package>-<launcher>.desktop}. */
    public static final String DEFAULT_DESKTOP_ID = "torrenta-Torrenta.desktop";
    /** The registry ProgID of ours; the installer's own stays beside it. */
    public static final String PROG_ID = "Torrenta.TorrentFile";

    static final String CLASSES = "HKCU\\Software\\Classes";

    public enum Platform {
        LINUX,
        MAC,
        WINDOWS
    }

    /** Runs a command and answers its exit status; the tests record instead. */
    @FunctionalInterface
    public interface Runner {
        int run(List<String> argv) throws IOException, InterruptedException;
    }

    /**
     * What (re)registering or unregistering came to: one sentence for the status bar.
     *
     * @param ok nothing went wrong
     * @param skipped there was nothing to run: a development run with no package, or a platform
     *     where the declaration is the package's alone; said from the Settings page, not at start
     */
    public record Outcome(boolean ok, boolean skipped, String text) {
        static Outcome done(String text) {
            return new Outcome(true, false, text);
        }

        static Outcome skipped(String text) {
            return new Outcome(true, true, text);
        }

        static Outcome failed(String text) {
            return new Outcome(false, false, text);
        }
    }

    private Handlers() {}

    public static Platform platform(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return Platform.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return Platform.MAC;
        return Platform.LINUX;
    }

    // ---- Linux ----------------------------------------------------------------------------------

    /** Where desktop entries live, in lookup order: {@code $XDG_DATA_HOME} then {@code $XDG_DATA_DIRS}. */
    public static List<Path> applicationDirs(Map<String, String> env, String userHome) {
        List<Path> dirs = new ArrayList<>();
        String home = env.getOrDefault("XDG_DATA_HOME", "");
        dirs.add((home.isBlank() ? Path.of(userHome, ".local", "share") : Path.of(home)).resolve("applications"));
        String data = env.getOrDefault("XDG_DATA_DIRS", "");
        if (data.isBlank()) data = "/usr/local/share:/usr/share";
        for (String dir : data.split(":")) {
            if (!dir.isBlank()) dirs.add(Path.of(dir, "applications"));
        }
        return List.copyOf(dirs);
    }

    /**
     * The installed desktop entry's file name, found by its {@code StartupWMClass}: the one thing
     * in it that names this program whatever the package called the file. Empty when Torrenta is
     * not installed from a package (a development run).
     */
    public static Optional<String> desktopId(List<Path> applicationDirs) {
        for (Path dir : applicationDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                Optional<String> found = files.filter(
                                f -> f.getFileName().toString().endsWith(".desktop"))
                        .sorted()
                        .filter(Handlers::isOurs)
                        .map(f -> f.getFileName().toString())
                        .findFirst();
                if (found.isPresent()) return found;
            } catch (IOException e) {
                // an unreadable directory is not ours
            }
        }
        return Optional.empty();
    }

    private static boolean isOurs(Path entry) {
        try {
            return Files.readString(entry, StandardCharsets.UTF_8)
                    .lines()
                    .anyMatch(line -> line.strip().equals("StartupWMClass=" + WM_CLASS));
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code xdg-mime default <entry> application/x-bittorrent x-scheme-handler/magnet}. */
    public static List<String> xdgMimeDefault(String desktopId) {
        return List.of("xdg-mime", "default", desktopId, MIME_TORRENT, SCHEME_MAGNET);
    }

    // ---- Windows --------------------------------------------------------------------------------

    /**
     * The {@code reg add} calls that make {@code launcher} open both: a ProgID for the extension
     * with its icon and open command, and the {@code magnet} protocol key with the
     * {@code URL Protocol} marker Windows requires of a scheme.
     */
    public static List<List<String>> windowsRegister(Path launcher) {
        String exe = launcher.toString();
        String command = "\"" + exe + "\" \"%1\"";
        String icon = exe + ",0";
        return List.of(
                regAdd(CLASSES + "\\.torrent", null, PROG_ID),
                regAdd(CLASSES + "\\" + PROG_ID, null, "BitTorrent file"),
                regAdd(CLASSES + "\\" + PROG_ID + "\\DefaultIcon", null, icon),
                regAdd(CLASSES + "\\" + PROG_ID + "\\shell\\open\\command", null, command),
                regAdd(CLASSES + "\\magnet", null, "URL:Magnet link"),
                regAdd(CLASSES + "\\magnet", "URL Protocol", ""),
                regAdd(CLASSES + "\\magnet\\DefaultIcon", null, icon),
                regAdd(CLASSES + "\\magnet\\shell\\open\\command", null, command));
    }

    /**
     * Takes the registration back: the protocol key and the ProgID go, and the extension's default
     * value with them, so whatever else is registered for {@code .torrent} (the installer's own
     * association, another program's) is the handler again.
     */
    public static List<List<String>> windowsUnregister() {
        return List.of(
                List.of("reg", "delete", CLASSES + "\\magnet", "/f"),
                List.of("reg", "delete", CLASSES + "\\" + PROG_ID, "/f"),
                List.of("reg", "delete", CLASSES + "\\.torrent", "/ve", "/f"));
    }

    private static List<String> regAdd(String key, String valueName, String data) {
        List<String> argv = new ArrayList<>(List.of("reg", "add", key));
        if (valueName == null) argv.add("/ve");
        else argv.addAll(List.of("/v", valueName));
        argv.addAll(List.of("/t", "REG_SZ", "/d", data, "/f"));
        return List.copyOf(argv);
    }

    /**
     * The installed launcher, {@code Torrenta.exe}, when this process is one: jpackage's launcher
     * runs the JVM in its own process, so the command is the launcher's path. A development run's
     * command is {@code java}, and there is nothing to register.
     */
    public static Optional<Path> launcher(Optional<String> command) {
        return command.map(Path::of).filter(Handlers::isLauncher);
    }

    static boolean isLauncher(Path command) {
        String name = command.getFileName() == null
                ? ""
                : command.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("torrenta.exe") || name.equals("torrenta");
    }

    // ---- the decision ---------------------------------------------------------------------------

    /**
     * (Re)registers when {@code on}, unregisters where that means anything when off, and says what
     * happened. May block on the commands: not for the FX thread.
     *
     * @param desktopId the installed desktop entry (Linux), if any
     * @param launcher the installed launcher (Windows), if any
     */
    public static Outcome apply(
            boolean on, Platform platform, Optional<String> desktopId, Optional<Path> launcher, Runner runner) {
        switch (platform) {
            case MAC -> {
                return Outcome.skipped(
                        on
                                ? "The app bundle declares .torrent files and magnet links; macOS offers it in"
                                        + " Finder's Get Info → Open with and as a browser's magnet handler"
                                : "The app bundle still declares both; pick another program in Finder's Get Info"
                                        + " → Open with");
            }
            case LINUX -> {
                if (!on) {
                    return Outcome.skipped(
                            "Torrenta stays a candidate for .torrent files and magnet links; the desktop's"
                                    + " default applications can pick another");
                }
                if (desktopId.isEmpty()) {
                    return Outcome.skipped("Not installed from a package: no desktop entry of Torrenta's to register");
                }
                return run(
                        runner,
                        List.of(xdgMimeDefault(desktopId.get())),
                        "Registered as the handler for .torrent files and magnet links (xdg-mime, " + desktopId.get()
                                + ")",
                        "xdg-mime");
            }
            default -> {
                if (!on) {
                    return run(
                            runner,
                            windowsUnregister(),
                            "Removed the magnet: protocol and the .torrent default from the registry",
                            "reg");
                }
                if (launcher.isEmpty()) {
                    return Outcome.skipped("Not running from the installed Torrenta.exe: nothing to register");
                }
                return run(
                        runner,
                        windowsRegister(launcher.get()),
                        "Registered as the handler for .torrent files and magnet links (registry, " + launcher.get()
                                + ")",
                        "reg");
            }
        }
    }

    private static Outcome run(Runner runner, List<List<String>> commands, String done, String tool) {
        for (List<String> argv : commands) {
            int status;
            try {
                status = runner.run(argv);
            } catch (IOException e) {
                return Outcome.failed("Could not run " + tool + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.failed(tool + " was interrupted");
            }
            if (status != 0) return Outcome.failed(tool + " failed (exit " + status + "): " + String.join(" ", argv));
        }
        return Outcome.done(done);
    }

    /** Runs the command with no console of its own, bounded to ten seconds; the real {@link Runner}. */
    public static int runQuietly(List<String> argv) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(argv)
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException(argv.get(0) + " did not finish in ten seconds");
        }
        return process.exitValue();
    }

    private static java.io.File nullDevice() {
        return new java.io.File(platform(System.getProperty("os.name", "")) == Platform.WINDOWS ? "NUL" : "/dev/null");
    }

    /** This machine's registration, run now; everything resolved from the environment. */
    public static Outcome applyHere(boolean on) {
        Platform platform = platform(System.getProperty("os.name", ""));
        Optional<String> desktopId = platform == Platform.LINUX
                ? desktopId(applicationDirs(System.getenv(), System.getProperty("user.home", "")))
                : Optional.empty();
        Optional<Path> launcher = platform == Platform.WINDOWS
                ? launcher(ProcessHandle.current().info().command())
                : Optional.empty();
        return apply(on, platform, desktopId, launcher, Handlers::runQuietly);
    }

    /** The installed desktop entry on this machine, for the Linux badge's launcher URI. */
    public static String desktopIdHere() {
        return desktopId(applicationDirs(System.getenv(), System.getProperty("user.home", "")))
                .orElse(DEFAULT_DESKTOP_ID);
    }
}
