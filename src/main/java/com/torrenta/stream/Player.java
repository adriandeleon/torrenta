package com.torrenta.stream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The media player a stream is handed to ({@code stream.player}): the one a person chose, else
 * mpv or VLC wherever this kind of machine keeps them: on the {@code PATH}, in the applications
 * folder as an app bundle (macOS), where the installer put it (Windows, the registry first). It is
 * run with the URL as its one argument and no shell, its output and input to nowhere. The search
 * is pure but for the check that a file is a program, which the tests replace.
 */
public final class Player {

    private Player() {}

    /** The players looked for, in order: mpv first, then VLC. */
    static final List<String> NAMES = List.of("mpv", "vlc");

    /**
     * Where to look, in order, on {@code osName}: {@code PATH}'s folders for each name, then the
     * platform's own places. {@code vlcInstallDir} is VLC's from the Windows registry, or empty.
     */
    static List<Path> candidates(String osName, Map<String, String> env, Path home, String vlcInstallDir) {
        String os = osName.toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        List<Path> out = new ArrayList<>();
        String path = env.getOrDefault(windows ? pathKey(env) : "PATH", "");
        for (String name : NAMES) {
            for (String dir : path.split(windows ? ";" : ":")) {
                if (dir.isBlank()) continue;
                try {
                    out.add(Path.of(dir.trim(), windows ? name + ".exe" : name));
                } catch (InvalidPathException e) {
                    // A PATH entry that is no path: skipped.
                }
            }
        }
        if (os.contains("mac")) {
            for (Path apps : List.of(Path.of("/Applications"), home.resolve("Applications"))) {
                out.add(apps.resolve("mpv.app/Contents/MacOS/mpv"));
                out.add(apps.resolve("VLC.app/Contents/MacOS/VLC"));
            }
        } else if (windows) {
            if (!vlcInstallDir.isBlank()) out.add(Path.of(vlcInstallDir, "vlc.exe"));
            for (String key : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                String dir = env.get(key);
                if (dir != null && !dir.isBlank()) out.add(Path.of(dir, "VideoLAN", "VLC", "vlc.exe"));
            }
            String local = env.get("LOCALAPPDATA");
            if (local != null && !local.isBlank()) out.add(Path.of(local, "Programs", "mpv", "mpv.exe"));
        } else {
            out.add(Path.of("/usr/bin/mpv"));
            out.add(Path.of("/usr/bin/vlc"));
            out.add(Path.of("/var/lib/flatpak/exports/bin/io.mpv.Mpv"));
            out.add(Path.of("/var/lib/flatpak/exports/bin/org.videolan.VLC"));
        }
        return out;
    }

    /** Windows keeps {@code Path}, any case; the environment map is case-sensitive in Java. */
    private static String pathKey(Map<String, String> env) {
        for (String k : env.keySet()) if (k.equalsIgnoreCase("PATH")) return k;
        return "Path";
    }

    /**
     * The player to run: {@code chosen} when it is a program, else the first candidate that is;
     * empty when there is none (the person is told to install one or choose it).
     */
    static Optional<Path> find(String chosen, List<Path> candidates, Predicate<Path> isProgram) {
        if (chosen != null && !chosen.isBlank()) {
            try {
                Path p = Path.of(chosen.trim());
                return isProgram.test(p) ? Optional.of(p) : Optional.empty();
            } catch (InvalidPathException e) {
                return Optional.empty();
            }
        }
        return candidates.stream().filter(isProgram).findFirst();
    }

    /**
     * {@code reg query "HKLM\SOFTWARE\VideoLAN\VLC" /v InstallDir}'s answer: the folder after
     * {@code REG_SZ}, or empty. Pure.
     */
    static String vlcInstallDir(String regOutput) {
        for (String line : regOutput.split("\\R")) {
            int at = line.indexOf("REG_SZ");
            if (line.trim().startsWith("InstallDir") && at >= 0)
                return line.substring(at + "REG_SZ".length()).trim();
        }
        return "";
    }

    /** This machine's player, as {@link #find} decides; {@code chosen} is the setting. */
    public static Optional<Path> forThisMachine(String chosen) {
        String os = System.getProperty("os.name", "");
        String vlc = "";
        if (os.toLowerCase(Locale.ROOT).contains("win"))
            vlc = vlcInstallDir(run(List.of("reg", "query", "HKLM\\SOFTWARE\\VideoLAN\\VLC", "/v", "InstallDir")));
        return find(
                chosen,
                candidates(os, System.getenv(), Path.of(System.getProperty("user.home", ".")), vlc),
                p -> Files.isRegularFile(p) && Files.isExecutable(p));
    }

    /** Runs {@code player} on {@code url}: the URL its one argument, no shell, nothing to read or write. */
    public static void launch(Path player, String url) throws IOException {
        new ProcessBuilder(player.toString(), url)
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** The player's name for the status line: "mpv", "VLC", or the file's own. */
    public static String nameOf(Path player) {
        String n = player.getFileName() == null
                ? player.toString()
                : player.getFileName().toString();
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.contains("mpv")) return "mpv";
        if (lower.contains("vlc")) return "VLC";
        return n;
    }

    private static java.io.File nullDevice() {
        return new java.io.File(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null");
    }

    private static String run(List<String> argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
