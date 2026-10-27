package com.torrenta.report;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report's text, its file name, and the issue link. */
class ProblemReportTest {

    @TempDir
    Path dir;

    @Test
    void theReportHasTheSystemBlockAndBothLogsRedacted() throws IOException {
        Path latest =
                Files.writeString(dir.resolve("torrenta-session.log"), "12:00  WARNING Settings — /home/alice/x\n");
        Path previous = Files.writeString(
                dir.resolve("torrenta-session.prev.log"), "11:00  UNCAUGHT on poll: boom in Linux ISO\n");
        ProblemReport.Assembled report = ProblemReport.assemble(
                latest,
                previous,
                "libtorrent (local)",
                "unused",
                new Redactor("/home/alice", "alice", List.of("Linux ISO")));
        String text = report.text();
        assertTrue(text.startsWith("Torrenta problem report\n=======================\n\n"), text);
        assertTrue(text.contains("Engine: libtorrent (local), libtorrent4j "), text);
        assertTrue(text.contains("Java: "), text);
        assertTrue(
                text.contains("--- torrenta-session.log (the latest session) ---\n12:00  WARNING Settings — ~/x\n"),
                text);
        assertTrue(
                text.contains(
                        "--- torrenta-session.prev.log (the session before it) ---\n11:00  UNCAUGHT on poll: boom in <torrent 1>\n"),
                text);
        assertEquals(2, report.redacted());
        // The JVM's own properties, as they are: a four-part Java version is not an address.
        assertEquals(ProblemReport.systemDetails("libtorrent (local)"), report.systemDetails());
        assertTrue(report.systemDetails().startsWith("Torrenta "), report.systemDetails());
    }

    @Test
    void aMissingFileIsSaidAndTheInMemoryCaptureStandsInForThisSessions() {
        ProblemReport.Assembled report = ProblemReport.assemble(
                dir.resolve("torrenta-session.log"),
                dir.resolve("torrenta-session.prev.log"),
                "Transmission (remote)",
                "from memory",
                new Redactor(null, null, List.of()));
        assertTrue(report.text().contains("(the latest session) ---\nfrom memory\n"), report.text());
        assertTrue(report.text().contains("(the session before it) ---\n(not found)\n"), report.text());
        assertTrue(ProblemReport.render("x", List.of(new ProblemReport.Log("h", " ")))
                .contains("--- h ---\n(empty)\n"));
    }

    @Test
    void aLongLogKeepsItsEndFromALineStart() {
        String text = "aaaa\nbbbb\ncccc\n";
        String tail = ProblemReport.tail(text, 7);
        assertEquals("(… 10 earlier characters left out …)\ncccc\n", tail);
        assertEquals(text, ProblemReport.tail(text, 100));
        assertNull(ProblemReport.readLog(dir.resolve("absent")));
        assertNull(ProblemReport.readLog(null));
    }

    @Test
    void theFileNameSortsByTime() {
        assertEquals(
                "torrenta-report-20261026-153012.txt",
                ProblemReport.fileName(LocalDateTime.of(2026, 10, 26, 15, 30, 12)));
    }

    @Test
    void howItRuns() {
        assertEquals("installed app", ProblemReport.installKind("/opt/torrenta/bin/Torrenta", true));
        assertEquals("development build", ProblemReport.installKind(null, true));
        assertEquals("jar", ProblemReport.installKind(" ", false));
    }

    @Test
    void aShortReportGoesInTheLinkAndALongOneLeavesTheHint() {
        String details = "Torrenta 0.1.1\nOS: Linux";
        String shortReport = "a report & more";
        String url = ProblemReport.issueUrl("owner/repo", details, shortReport, "HINT");
        assertTrue(
                url.startsWith("https://github.com/owner/repo/issues/new?template=bug_report.yml&environment="), url);
        assertFalse(url.contains("+"), url);
        assertTrue(URLDecoder.decode(url, StandardCharsets.UTF_8).endsWith("&report=a report & more"), url);
        assertTrue(ProblemReport.fitsInIssueUrl("owner/repo", details, shortReport));

        String longReport = "x".repeat(ProblemReport.MAX_URL_LENGTH);
        assertFalse(ProblemReport.fitsInIssueUrl("owner/repo", details, longReport));
        String hinted = ProblemReport.issueUrl("owner/repo", details, longReport, "HINT");
        assertTrue(hinted.endsWith("&report=HINT"), hinted);
        assertTrue(hinted.length() <= ProblemReport.MAX_URL_LENGTH);
    }
}
