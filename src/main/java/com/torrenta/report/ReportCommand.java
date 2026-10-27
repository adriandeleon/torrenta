package com.torrenta.report;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.torrenta.AppInfo;
import org.jspecify.annotations.Nullable;

/**
 * {@code torrenta --report}: writes a redacted problem report to a file in the current folder,
 * then opens a new GitHub issue with it filled in and shows the file — for when something went
 * wrong and, above all, when Torrenta will not start far enough to use Help → Report a Problem.
 * Editora's. Sends nothing itself: the user reads the issue and submits it.
 *
 * <p>The torrent list is not loaded here, so the names in it are not known and not replaced; the
 * printed note says to look for them.
 */
public final class ReportCommand {

    private ReportCommand() {}

    /**
     * Writes the report over {@code latestLog} and {@code previousLog} into {@code outDir} (the temp
     * folder when that is not writable), prints where, and — when {@code open} — opens the issue
     * page and the file's folder. Returns the process exit status.
     */
    public static int run(Path latestLog, Path previousLog, String engine, Path outDir, boolean open, PrintStream out) {
        ProblemReport.Assembled report =
                ProblemReport.assemble(latestLog, previousLog, engine, null, Redactor.forThisMachine(List.of()));
        Path file = write(report.text(), outDir, ProblemReport.fileName(LocalDateTime.now()));
        if (file == null) {
            out.println("Could not write the problem report to " + outDir + " or the temp folder.");
            return 1;
        }
        String url = ProblemReport.issueUrl(
                AppInfo.GITHUB_REPO,
                report.systemDetails(),
                report.text(),
                "Attach " + file.getFileName() + " here (drag it into this box), or paste its contents.");
        boolean inline = ProblemReport.fitsInIssueUrl(AppInfo.GITHUB_REPO, report.systemDetails(), report.text());
        out.println("Wrote the problem report to " + file);
        out.println(redactedLine(report.redacted()));
        out.println();
        out.println("Read it before you share it. Then open a new issue"
                + (inline ? " (the report is already filled in)" : " and attach the file") + ":");
        out.println("  " + url);
        if (open) {
            String os = System.getProperty("os.name", "");
            start(openUrlArgv(os, url));
            start(revealArgv(os, file));
        }
        return 0;
    }

    static String redactedLine(int redacted) {
        String names = " Torrent names are not known here: look for any in the paths.";
        return redacted == 0
                ? "Nothing in it looked private, but check it for anything you would rather not post." + names
                : redacted + (redacted == 1 ? " value" : " values")
                        + " (home-folder paths, magnet links, hashes, passkeys, addresses, tokens) were replaced;"
                        + " check the rest." + names;
    }

    /** Writes {@code text} to {@code dir/name}, else to the temp folder; the file written, or null. */
    static @Nullable Path write(String text, Path dir, String name) {
        for (Path candidate : List.of(dir, Path.of(System.getProperty("java.io.tmpdir", ".")))) {
            try {
                return Files.writeString(candidate.toAbsolutePath().resolve(name), text);
            } catch (IOException | RuntimeException e) {
                // the next folder
            }
        }
        return null;
    }

    /**
     * The browser launcher for {@code url}: {@code open} / {@code xdg-open}, and on Windows
     * {@code rundll32 url.dll,FileProtocolHandler} — not {@code cmd /c start}, whose shell would
     * read the {@code &} between query parameters as a command separator.
     */
    static List<String> openUrlArgv(String osName, String url) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return List.of("rundll32", "url.dll,FileProtocolHandler", url);
        if (os.contains("mac")) return List.of("open", url);
        return List.of("xdg-open", url);
    }

    /** The file manager showing {@code file}: selected where the platform can, its folder otherwise. */
    static List<String> revealArgv(String osName, Path file) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return List.of("explorer", "/select," + file);
        if (os.contains("mac")) return List.of("open", "-R", file.toString());
        Path folder = file.toAbsolutePath().getParent();
        return List.of("xdg-open", String.valueOf(folder == null ? file : folder));
    }

    private static void start(List<String> argv) {
        try {
            new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | RuntimeException e) {
            // No desktop (a server over SSH), or no launcher installed: the link is printed above.
        }
    }
}
