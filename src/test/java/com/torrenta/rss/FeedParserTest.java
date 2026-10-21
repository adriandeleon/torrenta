package com.torrenta.rss;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.xml.stream.XMLStreamException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedParserTest {

    private static FeedParser.Parsed fixture(String name) throws IOException, XMLStreamException {
        try (InputStream in = FeedParserTest.class.getResourceAsStream("/com/torrenta/rss/" + name)) {
            if (in == null) throw new IOException("no fixture " + name);
            return FeedParser.parse(in, "feed1");
        }
    }

    @Test
    void rssTwoTitleAndItemsWithTheTorrentFoundWhereverItIs() throws Exception {
        FeedParser.Parsed parsed = fixture("rss2.xml");
        assertEquals("Example Tracker & Friends", parsed.title(), "the channel's title, not an item's");
        List<Article> a = parsed.articles();
        assertEquals(7, a.size());
        for (Article x : a) {
            assertEquals("feed1", x.feedId());
            assertFalse(x.read());
        }

        Article netinst = a.get(0);
        assertEquals("Debian 12.7.0 amd64 netinst", netinst.title());
        assertEquals("tracker-42", netinst.guid());
        assertEquals("g:tracker-42", netinst.identity());
        assertEquals("https://tracker.example/torrents/42", netinst.link());
        assertEquals("https://tracker.example/download/42.torrent?passkey=abc", netinst.torrentLink(), "the enclosure");
        assertFalse(netinst.isMagnet());
        assertEquals(Instant.parse("2026-10-18T09:12:00Z"), netinst.published());
        assertEquals("The netinst image & nothing more.\n\nSize: 661 MiB\nArch: amd64", netinst.description());

        Article dvd = a.get(1);
        assertEquals(
                "magnet:?xt=urn:btih:D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B7C8D9E0&dn=debian-12.7.0-arm64-DVD-1.iso",
                dvd.torrentLink(),
                "the link is the magnet");
        assertTrue(dvd.isMagnet());
        assertEquals("", dvd.link(), "a magnet is not a page");
        assertNull(dvd.published());
        assertEquals("t:Debian 12.7.0 arm64 DVD\n", dvd.identity(), "no guid: title and link (none, it was a magnet)");

        Article ubuntu = a.get(2);
        assertEquals("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=ubuntu", ubuntu.torrentLink());
        assertEquals(Instant.parse("2026-10-17T20:30:00Z"), ubuntu.published(), "an ISO pubDate is taken too");

        Article fedora = a.get(3);
        assertEquals(
                "magnet:?xt=urn:btih:89abcdef0123456789abcdef0123456789abcdef&dn=fedora",
                fedora.torrentLink(),
                "the magnet in the description, its entities decoded");
        assertEquals(Instant.parse("2026-10-17T08:00:00Z"), fedora.published(), "no weekday");
        assertEquals("Grab it: magnet today.", fedora.description());

        Article news = a.get(4);
        assertEquals("", news.torrentLink(), "a jpeg enclosure is not a torrent");
        assertFalse(news.hasTorrent());
        assertEquals("https://tracker.example/news/7", news.link());

        Article arch = a.get(5);
        assertEquals(
                "magnet:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98&dn=arch", arch.torrentLink(), "the guid");
        assertNull(arch.published(), "an unreadable date is no date, not a failure");
        assertEquals("Only content:encoded, no description.", arch.description());

        Article alpine = a.get(6);
        assertEquals(
                "https://tracker.example/files/alpine-3.20.torrent", alpine.torrentLink(), "a link ending in .torrent");
        assertEquals("https://tracker.example/files/alpine-3.20.torrent", alpine.link());
        assertEquals(Instant.parse("2026-10-15T04:00:00Z"), alpine.published());
        assertEquals("", alpine.description());
    }

    @Test
    void atomEntriesLinksSummariesAndDates() throws Exception {
        FeedParser.Parsed parsed = fixture("atom.xml");
        assertEquals("Atom Releases", parsed.title());
        List<Article> a = parsed.articles();
        assertEquals(3, a.size());

        Article one = a.get(0);
        assertEquals("Release 1.0", one.title());
        assertEquals("urn:release:1.0", one.guid());
        assertEquals("https://atom.example/releases/1.0", one.link(), "rel=alternate");
        assertEquals(
                "https://atom.example/releases/1.0.torrent", one.torrentLink(), "rel=enclosure of the torrent type");
        assertEquals(Instant.parse("2026-10-18T07:00:00Z"), one.published(), "published wins over updated");
        assertEquals("First & final.", one.description(), "escaped HTML, decoded twice");

        Article two = a.get(1);
        assertEquals("https://atom.example/releases/0.9", two.link(), "a link with no rel is the alternate");
        assertEquals(
                "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", two.torrentLink(), "in the content");
        assertEquals(Instant.parse("2026-10-10T10:00:00Z"), two.published(), "updated when there is no published");
        assertEquals("Magnet inside: here", two.description());

        Article notes = a.get(2);
        assertEquals("", notes.torrentLink());
        assertNull(notes.published());
        assertEquals(
                "Nested xhtml content.\n\nSecond paragraph.", notes.description(), "xhtml content, elements walked");
    }

    @Test
    void notXmlIsAFailureButAnEmptyChannelIsNot() throws Exception {
        assertThrows(
                XMLStreamException.class,
                () -> FeedParser.parse(new ByteArrayInputStream("<html>nope".getBytes(StandardCharsets.UTF_8)), "f"));
        FeedParser.Parsed empty = FeedParser.parse(
                new ByteArrayInputStream("<rss><channel></channel></rss>".getBytes(StandardCharsets.UTF_8)), "f");
        assertEquals("", empty.title());
        assertEquals(List.of(), empty.articles());
    }

    @Test
    void entitiesAndDtdsAreNotFollowed() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>"
                + "<rss><channel><title>&x;</title></channel></rss>";
        assertThrows(
                XMLStreamException.class,
                () -> FeedParser.parse(new ByteArrayInputStream(evil.getBytes(StandardCharsets.UTF_8)), "f"));
    }

    @Test
    void theTorrentRuleInOrder() {
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
        assertEquals(magnet, FeedParser.torrentLink(magnet, "http://x/a.torrent", "", "", "", "", "", ""));
        assertEquals(magnet, FeedParser.torrentLink("", magnet, "", "http://x/a.torrent", "", "", "", ""));
        assertEquals(magnet, FeedParser.torrentLink("", "http://x/p", magnet, "", "", "", "", ""));
        assertEquals(
                "http://x/dl?id=1",
                FeedParser.torrentLink(
                        "", "http://x/p", "", "http://x/dl?id=1", "application/x-bittorrent", "", "", ""));
        assertEquals(
                "http://x/a.torrent",
                FeedParser.torrentLink("", "http://x/p", "", "http://x/a.torrent", "", "", "", ""));
        assertEquals("", FeedParser.torrentLink("", "http://x/p", "", "http://x/a.mp3", "audio/mpeg", "", "", ""));
        assertEquals(
                "http://x/a.torrent",
                FeedParser.torrentLink("", "http://x/a.torrent?k=1", "", "", "", "", "", "")
                        .substring(0, "http://x/a.torrent".length()));
        assertEquals(magnet, FeedParser.torrentLink("", "", "", "", "", "see " + magnet + " now", "", ""));
        assertEquals(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=A+b",
                FeedParser.torrentLink("", "", "", "", "", "", "0123456789ABCDEF0123456789abcdef01234567", "A b"),
                "an info-hash alone becomes a magnet");
        assertEquals("", FeedParser.torrentLink("", "", "", "", "", "", "", ""));
    }

    @Test
    void plainTextStripsTagsDecodesEntitiesAndFoldsWhitespace() {
        assertEquals("", FeedParser.plainText(null));
        assertEquals("a & b < c", FeedParser.plainText("a &amp; b &lt; c"));
        assertEquals("one\ntwo", FeedParser.plainText("one<br/>two"));
        assertEquals("one\n\ntwo", FeedParser.plainText("<p>one</p>\n\n\n<p>two</p>"));
        assertEquals("x y", FeedParser.plainText("x&nbsp;&#32;&#x20;y"));
        assertEquals("© 2026 “q”", FeedParser.plainText("&copy; 2026 &ldquo;q&rdquo;"));
        assertEquals("&unknown; stays", FeedParser.plainText("&unknown; stays"));
        assertEquals("link", FeedParser.plainText("<a href=\"http://x\" title=\"a > b\">link</a>"));
    }

    @Test
    void datesInTheShapesFeedsUse() {
        assertEquals(Instant.parse("2026-10-18T09:12:00Z"), FeedParser.parseDate("Sun, 18 Oct 2026 09:12:00 +0000"));
        assertEquals(Instant.parse("2026-10-18T09:12:00Z"), FeedParser.parseDate("Sun, 18 Oct 2026 09:12:00 GMT"));
        assertEquals(Instant.parse("2026-10-18T09:12:00Z"), FeedParser.parseDate("18 Oct 2026 09:12:00 GMT"));
        assertEquals(Instant.parse("2026-10-18T16:12:00Z"), FeedParser.parseDate("Sun, 18 Oct 2026 09:12:00 PDT"));
        assertEquals(Instant.parse("2026-10-18T09:12:00Z"), FeedParser.parseDate("2026-10-18T09:12:00Z"));
        assertEquals(Instant.parse("2026-10-18T07:12:00Z"), FeedParser.parseDate("2026-10-18T09:12:00+02:00"));
        assertEquals(Instant.parse("2026-10-18T09:12:00Z"), FeedParser.parseDate("2026-10-18T09:12:00"));
        assertEquals(Instant.parse("2026-10-18T00:00:00Z"), FeedParser.parseDate("2026-10-18"));
        assertNull(FeedParser.parseDate(""));
        assertNull(FeedParser.parseDate("yesterday"));
    }
}
