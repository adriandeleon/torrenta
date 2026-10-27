package com.torrenta.report;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a problem report keeps and what it replaces. */
class RedactorTest {

    private static final Redactor PLAIN = new Redactor("/home/alice", "alice", List.of());

    private static String redact(String text) {
        return PLAIN.redact(text).text();
    }

    @Test
    void theHomeFolderBecomesATildeAndTheUserNameAPlaceholder() {
        assertEquals(
                "Could not read ~/.torrenta/settings.properties",
                redact("Could not read /home/alice/.torrenta/settings.properties"));
        assertEquals("~", redact("/home/alice"));
        // A neighbour whose name starts the same is somebody else's folder, and is left.
        assertEquals("/home/alice2/x", redact("/home/alice2/x"));
        assertEquals("owner <user> on host", redact("owner alice on host"));
    }

    @Test
    void aWindowsHomeGoesWithEitherSeparator() {
        Redactor windows = new Redactor("C:\\Users\\Bob\\", "Bob", List.of());
        assertEquals("~\\Downloads", windows.redact("C:\\Users\\Bob\\Downloads").text());
        assertEquals(
                "file:/~/Downloads",
                windows.redact("file:/C:/Users/Bob/Downloads").text());
    }

    @Test
    void aShortUserNameIsNotReplacedEverywhere() {
        Redactor shortName = new Redactor("/home/al", "al", List.of());
        assertEquals("all along", shortName.redact("all along").text());
    }

    @Test
    void credentialsInAUrlGoAndTheHostStays() {
        assertEquals(
                "http://<redacted>@nas.local:9091/transmission/rpc",
                redact("http://admin:hunter2@nas.local:9091/transmission/rpc"));
    }

    @Test
    void aTrackersPasskeyGoesFromThePathAndTheQuery() {
        assertEquals(
                "https://tracker.example/<redacted>/announce",
                redact("https://tracker.example/0123456789abcdef0123456789abcdef/announce"));
        assertEquals(
                "https://tracker.example/announce/<redacted>",
                redact("https://tracker.example/announce/a1B2c3D4e5F6g7H8i9J0"));
        assertEquals(
                "https://tracker.example/announce.php?passkey=<redacted>&info=1",
                redact("https://tracker.example/announce.php?passkey=s3cr3tvalue&info=1"));
        assertEquals(
                "https://feeds.example/rss?rsskey=<redacted>", redact("https://feeds.example/rss?rsskey=abcdefgh"));
        // A public tracker has nothing to hide.
        assertEquals(
                "udp://tracker.opentrackr.org:1337/announce", redact("udp://tracker.opentrackr.org:1337/announce"));
    }

    @Test
    void aMagnetLinkGoesWholeAndAnInfoHashOnItsOwn() {
        assertEquals(
                "Could not add magnet:<redacted> (bad link)",
                redact("Could not add magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Secret"
                        + "&tr=https://t.example/abcdefabcdefabcdefabcdefabcdefab/announce (bad link)"));
        assertEquals(
                "Ignoring <hash>.fastresume: not a torrent of ours",
                redact("Ignoring 0123456789ABCDEF0123456789abcdef01234567.fastresume: not a torrent of ours"));
        String v2 = "a".repeat(64);
        assertEquals("id <hash>", redact("id " + v2));
        // Shorter hex is an ordinary value (an address, a colour, a short commit).
        assertEquals("at 0x7ffe4a2b10", redact("at 0x7ffe4a2b10"));
    }

    @Test
    void theListsNamesAreNumberedSoTwoLinesStillMatch() {
        Redactor named =
                new Redactor("/home/alice", "alice", List.of("Some.Show.S01E02.1080p", "Some.Show", "tv", "Linux ISO"));
        Redactor.Result result = named.redact(
                "Could not move /home/alice/Downloads/Some.Show.S01E02.1080p\nthen Some.Show and Linux ISO, on tv");
        assertEquals("Could not move ~/Downloads/<torrent 1>\nthen <torrent 2> and <torrent 3>, on tv", result.text());
        assertEquals(4, result.replaced());
    }

    @Test
    void aPublicAddressGoesAndThisNetworksStay() {
        assertEquals("peer <ip>:6881 refused", redact("peer 203.0.113.9:6881 refused"));
        for (String kept : List.of(
                "127.0.0.1",
                "0.0.0.0",
                "10.1.2.3",
                "172.16.0.1",
                "172.31.255.255",
                "192.168.1.20",
                "169.254.10.10",
                "100.64.0.1",
                "224.0.0.251",
                "999.1.1.1")) {
            assertEquals("on " + kept, redact("on " + kept), kept);
        }
        assertEquals("on <ip>", redact("on 172.32.0.1"));
        // A version with more parts is not an address.
        assertEquals("libtorrent 2.1.0.39.1", redact("libtorrent 2.1.0.39.1"));
    }

    @Test
    void anEmailGoesAndAGitRemoteStays() {
        assertEquals("by <email>", redact("by alice.smith+x@example.co.uk"));
        assertEquals("git@github.com:owner/repo", redact("git@github.com:owner/repo"));
    }

    @Test
    void headersAndTokenShapesGo() {
        assertEquals("12:00  Authorization: <redacted>", redact("12:00  Authorization: Basic YWRtaW46aHVudGVy"));
        assertEquals("X-Transmission-Session-Id: abc", redact("X-Transmission-Session-Id: abc"));
        assertEquals("token <redacted>", redact("token ghp_abcdefghijklmnopqrstuvwxyz0123"));
        assertEquals("password=<redacted>", redact("password=hunter22"));
        // A bare "key" is what an unknown setting is called, which is what a report is for.
        assertEquals("unknown key: ui.theme", redact("unknown key: ui.theme"));
    }

    @Test
    void nothingPrivateIsNothingReplaced() {
        Redactor.Result result = PLAIN.redact("12:00:01.000  WARNING Settings — ui.theme is not a theme");
        assertEquals(0, result.replaced());
        assertEquals("12:00:01.000  WARNING Settings — ui.theme is not a theme", result.text());
        assertEquals("", PLAIN.redact("").text());
        assertEquals("", PLAIN.redact(null).text());
    }

    @Test
    void aLiteralDollarInTheTextSurvives() {
        String out = redact("cost $1 for /home/alice/x");
        assertTrue(out.contains("$1"), out);
        assertFalse(out.contains("/home/alice"), out);
    }
}
