/*
 * ReleaseNotes' rules, and the real CHANGELOG.md's next section fitting a release body.
 *
 *   java scripts/release/ReleaseNotesTest.java     (from the project root; exit 1 on a failure)
 *
 * The release workflow's preflight runs it before it builds the notes it will publish.
 */
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ReleaseNotesTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        sectionByHeadingWithOrWithoutBracketsAndDate();
        sectionStopsAtTheNextVersionAndIsEmptyWhenAbsent();
        aSectionThatFitsIsCopiedWhole();
        groupsMergeRepeatedHeadingsAndJoinWrappedLines();
        leadIsTheBoldTextElseTheFirstSentence();
        aSectionTooLongIsSummarisedUnderTheLimit();
        theRealChangelogFits();
        if (failures > 0) {
            System.err.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("ReleaseNotesTest: all passed");
    }

    static void sectionByHeadingWithOrWithoutBracketsAndDate() {
        String log = "# Changelog\n\n## [0.2.0] — 2026-11-01\n\n- **Two.**\n\n## 0.1.0\n\n- **One.**\n";
        check("bracketed with a date", section(log, "0.2.0"), "- **Two.**");
        check("plain", section(log, "0.1.0"), "- **One.**");
        check("a prefix of another version is not it", section("## 0.1.01\n\n- x\n", "0.1.0"), null);
    }

    static void sectionStopsAtTheNextVersionAndIsEmptyWhenAbsent() {
        String log = "## Unreleased\n\n### Added\n\n- **New.**\n\n## 0.1.0\n\n- **Old.**\n";
        check("stops at the next ##", section(log, "Unreleased"), "### Added\n\n- **New.**");
        check("absent", section(log, "0.3.0"), null);
    }

    static void aSectionThatFitsIsCopiedWhole() {
        String body = ReleaseNotes.compose("## Install\n\nRun it.\n", "### Fixed\n\n- **A fix.** Details.", "URL");
        check("whole", body, "## Install\n\nRun it.\n\n## Changes\n\n### Fixed\n\n- **A fix.** Details.\n");
    }

    static void groupsMergeRepeatedHeadingsAndJoinWrappedLines() {
        Map<String, List<String>> groups = ReleaseNotes.groups(
                "### Added\n\n- **One** wrapped\n  over two lines.\n- Two.\n\n### Fixed\n\n- Three.\n\n### Added\n\n- Four.");
        check("headings once, in first order", String.valueOf(groups.keySet()), "[Added, Fixed]");
        check("joined and merged", String.valueOf(groups.get("Added")), "[**One** wrapped over two lines., Two., Four.]");
    }

    static void leadIsTheBoldTextElseTheFirstSentence() {
        check("bold", ReleaseNotes.lead("**The lead.** The rest."), "**The lead.**");
        check("sentence", ReleaseNotes.lead("`Engine.remove(id)`, for later. More."), "`Engine.remove(id)`, for later.");
        check("capped", ReleaseNotes.lead("x".repeat(300)).length() <= 200 ? "ok" : "long", "ok");
    }

    static void aSectionTooLongIsSummarisedUnderTheLimit() {
        StringBuilder section = new StringBuilder("### Added\n\n");
        for (int i = 0; i < 400; i++) {
            section.append("- **Entry ").append(i).append(" with a lead long enough to need a summary.** ")
                    .append("Detail. ".repeat(10))
                    .append('\n');
        }
        String body = ReleaseNotes.compose("## Install\n", section.toString(), "https://example/CHANGELOG.md");
        check("under the limit", body.length() <= ReleaseNotes.LIMIT ? "ok" : "over: " + body.length(), "ok");
        check("leads only", body.contains("Detail.") ? "details" : "ok", "ok");
        check("says how many were left out", body.contains(" more.\n") ? "ok" : "no count", "ok");
        check("links the full text", body.endsWith("[CHANGELOG.md](https://example/CHANGELOG.md).\n") ? "ok" : body, "ok");
    }

    static void theRealChangelogFits() throws Exception {
        Path changelog = Path.of("CHANGELOG.md");
        Path install = Path.of(".github/release-notes.md");
        if (!Files.exists(changelog) || !Files.exists(install)) {
            fail("run from the project root: CHANGELOG.md and .github/release-notes.md not found");
            return;
        }
        String log = Files.readString(changelog);
        Optional<String> next = ReleaseNotes.section(log, "Unreleased");
        if (next.isEmpty()) {
            // Between a release commit and the next change there is no Unreleased section: the
            // newest version's is the one to check.
            var newest = log.lines().filter(l -> l.startsWith("## ")).findFirst();
            next = newest.flatMap(h -> ReleaseNotes.section(log, h.substring(3).split("[\\s\\]]")[0].replace("[", "")));
        }
        if (next.isEmpty()) {
            fail("CHANGELOG.md has no section to check");
            return;
        }
        String body = ReleaseNotes.compose(Files.readString(install), next.get(), "URL");
        check("the real changelog fits", body.length() <= ReleaseNotes.LIMIT ? "ok" : "over: " + body.length(), "ok");
    }

    private static String section(String log, String name) {
        return ReleaseNotes.section(log, name).orElse(null);
    }

    private static void check(String what, String actual, String expected) {
        if (!java.util.Objects.equals(actual, expected)) {
            fail(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void fail(String message) {
        failures++;
        System.err.println("FAIL " + message);
    }
}
