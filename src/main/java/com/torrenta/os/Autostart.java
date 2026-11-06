package com.torrenta.os;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Start Torrenta at login, per person and per platform: an XDG autostart entry on Linux (GNOME,
 * KDE and the rest read {@code ~/.config/autostart}), a LaunchAgent on macOS, the {@code HKCU} Run
 * value on Windows. Nux's design: the installers never do it (they run for every account), and the
 * entry itself is the one truth, no setting beside it to drift: a person who turns it off in GNOME
 * Tweaks, KDE's Autostart page or Task Manager finds the box unticked here. Only "start in the tray"
 * is a setting ({@code os.autostartInTray}), written into the entry as {@code --minimized}.
 *
 * <p>Only an installed package can do it: jpackage's launcher sets {@code jpackage.app-path}, and a
 * development run has nothing worth starting at login. Every call may block on the file system or
 * {@code reg.exe}: never on the FX thread.
 */
public final class Autostart {

    /** What a change or a look came to; the window turns it into words. */
    public enum Result {
        /** The entry is there and runs. */
        ON,
        /** No entry, or one turned off by the person. */
        OFF,
        /** Not an installed package: nothing to start. */
        NOT_INSTALLED,
        /** The file or {@code reg} failed; the log has why. */
        FAILED
    }

    /** Runs a command and answers its exit status and output; the tests answer instead. */
    @FunctionalInterface
    public interface Exec {
        Answer run(List<String> argv) throws IOException, InterruptedException;
    }

    public record Answer(int exit, String output) {}

    private static final System.Logger LOG = System.getLogger(Autostart.class.getName());

    private final Handlers.Platform platform;
    private final Optional<String> launcher;
    private final Path home;
    private final Map<String, String> env;
    private final Exec exec;

    public Autostart(
            Handlers.Platform platform, Optional<String> launcher, Path home, Map<String, String> env, Exec exec) {
        this.platform = platform;
        this.launcher = launcher.filter(path -> !path.isBlank());
        this.home = home;
        this.env = env;
        this.exec = exec;
    }

    /** This machine: the OS, the launcher this process came from, the real home and environment. */
    public static Autostart here() {
        return new Autostart(
                Handlers.platform(System.getProperty("os.name", "")),
                Optional.ofNullable(System.getProperty("jpackage.app-path")),
                Path.of(System.getProperty("user.home", ".")),
                System.getenv(),
                Autostart::runQuietly);
    }

    /** Whether there is anything to start: an installed launcher, the window's (never the headless one). */
    public boolean available() {
        return launcher.isPresent() && !com.torrenta.cli.CommandLine.parse().headless(launcher.get());
    }

    /** The entry as it stands: {@link Result#ON}, {@link Result#OFF} or {@link Result#NOT_INSTALLED}. */
    public Result state() {
        if (!available()) return Result.NOT_INSTALLED;
        try {
            return runs() ? Result.ON : Result.OFF;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the start-at-login entry", e);
            return Result.OFF;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.OFF;
        }
    }

    /** Writes or removes the entry and answers what is true afterwards. */
    public Result set(boolean on, boolean minimized) {
        if (!available()) return Result.NOT_INSTALLED;
        String path = launcher.orElseThrow();
        try {
            switch (platform) {
                case LINUX -> {
                    if (on) write(linuxEntry(), AutostartSpec.linuxDesktopEntry(path, minimized, linuxIcon(path)));
                    else Files.deleteIfExists(linuxEntry());
                }
                // No launchctl: a load would start a second Torrenta now (RunAtLoad), and the
                // agent is read at the next login anyway, which is the whole point.
                case MAC -> {
                    if (on) write(macEntry(), AutostartSpec.macLaunchAgent(path, minimized));
                    else Files.deleteIfExists(macEntry());
                }
                case WINDOWS -> {
                    if (on) {
                        if (!importReg(AutostartSpec.windowsRunRegFile(path, minimized))) return Result.FAILED;
                        exec.run(AutostartSpec.windowsApprovedRemove());
                    } else {
                        Answer removed = exec.run(AutostartSpec.windowsRemove());
                        // reg.exe answers 1 for a value that is not there, which is "off" too.
                        if (removed.exit() != 0
                                && exec.run(AutostartSpec.windowsQuery()).exit() == 0) {
                            return Result.FAILED;
                        }
                    }
                }
            }
            Result now = runs() ? Result.ON : Result.OFF;
            return now == (on ? Result.ON : Result.OFF) ? now : Result.FAILED;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not change the start-at-login entry", e);
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }

    /**
     * At start: an entry that runs but names another launcher (the app moved, a new version in
     * another folder) or the other {@code --minimized} is written again; one turned off is left
     * off. Answers whether it wrote.
     */
    public boolean refresh(boolean minimized) {
        if (!available()) return false;
        String path = launcher.orElseThrow();
        try {
            if (!runs()) return false;
            return switch (platform) {
                case LINUX -> rewrite(linuxEntry(), AutostartSpec.linuxDesktopEntry(path, minimized, linuxIcon(path)));
                case MAC -> rewrite(macEntry(), AutostartSpec.macLaunchAgent(path, minimized));
                case WINDOWS -> {
                    String wanted = AutostartSpec.windowsCommand(path, minimized);
                    Optional<String> data = AutostartSpec.regQueryData(
                            exec.run(AutostartSpec.windowsQuery()).output());
                    yield data.isPresent()
                            && !data.get().equals(wanted)
                            && importReg(AutostartSpec.windowsRunRegFile(path, minimized));
                }
            };
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not refresh the start-at-login entry", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Where the entry lives here, for the capture and the log. */
    public String where() {
        return switch (platform) {
            case LINUX -> linuxEntry().toString();
            case MAC -> macEntry().toString();
            case WINDOWS -> AutostartSpec.RUN_KEY + "\\" + AutostartSpec.NAME;
        };
    }

    private boolean runs() throws IOException, InterruptedException {
        return switch (platform) {
            case LINUX ->
                Files.isRegularFile(linuxEntry())
                        && AutostartSpec.linuxEntryRuns(Files.readString(linuxEntry(), StandardCharsets.UTF_8));
            case MAC -> Files.isRegularFile(macEntry());
            case WINDOWS -> {
                if (exec.run(AutostartSpec.windowsQuery()).exit() != 0) yield false;
                Answer approved = exec.run(AutostartSpec.windowsApprovedQuery());
                yield approved.exit() != 0
                        || !AutostartSpec.approvedSaysDisabled(
                                AutostartSpec.regQueryData(approved.output()).orElse(""));
            }
        };
    }

    Path linuxEntry() {
        String base = env.getOrDefault("XDG_CONFIG_HOME", "");
        Path config = base.isBlank() ? home.resolve(".config") : Path.of(base);
        return config.resolve("autostart").resolve(AutostartSpec.LINUX_FILE);
    }

    Path macEntry() {
        return home.resolve("Library").resolve("LaunchAgents").resolve(AutostartSpec.MAC_LABEL + ".plist");
    }

    /** jpackage puts the icon beside the launcher's folder: {@code /opt/torrenta/lib/Torrenta.png}. */
    static Optional<String> linuxIcon(String launcher) {
        Path bin = Path.of(launcher).getParent();
        if (bin == null || bin.getParent() == null) return Optional.empty();
        Path icon = bin.getParent().resolve("lib").resolve(AutostartSpec.NAME + ".png");
        return Files.isRegularFile(icon) ? Optional.of(icon.toString()) : Optional.empty();
    }

    private static boolean rewrite(Path file, String content) throws IOException {
        if (Files.readString(file, StandardCharsets.UTF_8).equals(content)) return false;
        write(file, content);
        return true;
    }

    /** Written beside and moved over, so a crash never leaves half an entry the desktop then reads. */
    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** UTF-16LE with a BOM, the encoding regedit exports and reg.exe reads unambiguously. */
    private boolean importReg(String content) throws IOException, InterruptedException {
        Path file = Files.createTempFile("torrenta-autostart", ".reg");
        try {
            Files.writeString(file, "﻿" + content, StandardCharsets.UTF_16LE);
            return exec.run(AutostartSpec.windowsImport(file.toString())).exit() == 0;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** The real {@link Exec}: no console, stdin from the null device, bounded to ten seconds. */
    static Answer runQuietly(List<String> argv) throws IOException, InterruptedException {
        boolean windows = Handlers.platform(System.getProperty("os.name", "")) == Handlers.Platform.WINDOWS;
        Process process = new ProcessBuilder(argv)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(windows ? "NUL" : "/dev/null")))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException(argv.get(0) + " did not finish in ten seconds");
        }
        byte[] out;
        // reg.exe's answer is a few lines, well inside the pipe's buffer, so it is read after the exit.
        try (InputStream in = process.getInputStream()) {
            out = in.readNBytes(64 * 1024);
        }
        // reg.exe writes the console's code page; the lines read are ASCII but for the path.
        Charset charset = windows
                ? Charset.forName(System.getProperty("native.encoding", "UTF-8"), StandardCharsets.UTF_8)
                : StandardCharsets.UTF_8;
        return new Answer(process.exitValue(), new String(out, charset));
    }
}
