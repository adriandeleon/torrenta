package com.torrenta.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code torrenta --report}, with nothing opened. */
class ReportCommandTest {

    @TempDir
    Path dir;

    @Test
    void theReportIsWrittenToTheFolderAndTheLinkPrinted() throws IOException {
        Path latest = Files.writeString(dir.resolve("torrenta-session.log"), "12:00  WARNING x — y\n");
        Path out = Files.createDirectory(dir.resolve("out"));
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        int status = ReportCommand.run(
                latest,
                dir.resolve("torrenta-session.prev.log"),
                "libtorrent (local)",
                out,
                false,
                new PrintStream(printed, true, StandardCharsets.UTF_8));
        assertEquals(0, status);
        List<Path> written;
        try (Stream<Path> files = Files.list(out)) {
            written = files.toList();
        }
        assertEquals(1, written.size());
        assertTrue(written.getFirst().getFileName().toString().matches("torrenta-report-\\d{8}-\\d{6}\\.txt"));
        assertTrue(Files.readString(written.getFirst()).contains("12:00  WARNING x — y"));
        String text = printed.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Wrote the problem report to " + written.getFirst()), text);
        assertTrue(text.contains("https://github.com/adriandeleon/torrenta/issues/new?template=bug_report.yml"), text);
        assertTrue(text.contains("already filled in"), text);
    }

    @Test
    void anUnwritableFolderFallsBackToTheTempFolder() throws IOException {
        Path notAFolder = Files.writeString(dir.resolve("file"), "");
        Path written = ReportCommand.write("text", notAFolder, "torrenta-report-x.txt");
        try {
            assertEquals(
                    Path.of(System.getProperty("java.io.tmpdir"))
                            .toAbsolutePath()
                            .resolve("torrenta-report-x.txt"),
                    written);
        } finally {
            Files.deleteIfExists(written);
        }
    }

    @Test
    void theLaunchersNeverGoThroughAShell() {
        String url = "https://github.com/o/r/issues/new?a=1&b=2";
        assertEquals(List.of("xdg-open", url), ReportCommand.openUrlArgv("Linux", url));
        assertEquals(List.of("open", url), ReportCommand.openUrlArgv("Mac OS X", url));
        assertEquals(
                List.of("rundll32", "url.dll,FileProtocolHandler", url), ReportCommand.openUrlArgv("Windows 11", url));
        Path file = Path.of("/tmp/r/report.txt");
        assertEquals(List.of("xdg-open", "/tmp/r"), ReportCommand.revealArgv("Linux", file));
        assertEquals(List.of("open", "-R", file.toString()), ReportCommand.revealArgv("Mac OS X", file));
        assertEquals(List.of("explorer", "/select," + file), ReportCommand.revealArgv("Windows 11", file));
    }

    @Test
    void theRedactionLineCounts() {
        assertTrue(ReportCommand.redactedLine(0).startsWith("Nothing in it looked private"));
        assertTrue(ReportCommand.redactedLine(1).startsWith("1 value "));
        assertTrue(ReportCommand.redactedLine(3).startsWith("3 values "));
    }
}
