package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.torrenta.engine.Inputs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure: no toolkit. */
class WelcomeTextTest {

    private static final String HASH = "a".repeat(40);

    @Test
    void aMagnetIsNamedByItsDisplayName() {
        assertEquals(
                Optional.of(new WelcomeText.Offer(Inputs.Kind.MAGNET, "debian-13.1.0-amd64-netinst.iso")),
                WelcomeText.offer("  magnet:?xt=urn:btih:" + HASH + "&dn=debian-13.1.0-amd64-netinst.iso&tr=x  "));
        assertEquals(
                Optional.of(new WelcomeText.Offer(Inputs.Kind.MAGNET, "Big Buck Bunny")),
                WelcomeText.offer("magnet:?xt=urn:btih:" + HASH + "&dn=Big+Buck%20Bunny"));
    }

    @Test
    void aMagnetWithoutANameHasAnEmptyOne() {
        assertEquals(
                Optional.of(new WelcomeText.Offer(Inputs.Kind.MAGNET, "")),
                WelcomeText.offer("magnet:?xt=urn:btih:" + HASH));
    }

    @Test
    void aLinkIsNamedByItsFileElseItsHost() {
        assertEquals(
                Optional.of(new WelcomeText.Offer(Inputs.Kind.URL, "ubuntu 24.04.torrent")),
                WelcomeText.offer("https://example.org/releases/ubuntu%2024.04.torrent?key=1"));
        assertEquals("example.org", WelcomeText.linkName("https://example.org/?f=x.torrent"));
        assertEquals("", WelcomeText.linkName("https://exa mple.org/x.torrent"));
    }

    @Test
    void nothingAddableIsNoOffer() {
        assertEquals(Optional.empty(), WelcomeText.offer(null));
        assertEquals(Optional.empty(), WelcomeText.offer(""));
        assertEquals(Optional.empty(), WelcomeText.offer("hello"));
        assertEquals(Optional.empty(), WelcomeText.offer("https://example.org/page.html"));
        assertEquals(Optional.empty(), WelcomeText.offer("/home/me/x.torrent"));
        assertEquals(Optional.empty(), WelcomeText.offer("magnet:?xt=urn:btih:" + HASH + "\nsecond line"));
    }

    @Test
    void theFolderIsShortenedUnderHomeWhereThatIsTheCustom() {
        Path home = Path.of("/home/me");
        assertEquals("~/Downloads", WelcomeText.folder(Path.of("/home/me/Downloads"), home, true));
        assertEquals("~/a/b", WelcomeText.folder(Path.of("/home/me/x/../a/b"), home, true));
        assertEquals("~", WelcomeText.folder(home, home, true));
        assertEquals("/home/meat/Downloads", WelcomeText.folder(Path.of("/home/meat/Downloads"), home, true));
        assertEquals("/srv/torrents", WelcomeText.folder(Path.of("/srv/torrents"), home, true));
        assertEquals("/home/me/Downloads", WelcomeText.folder(Path.of("/home/me/Downloads"), home, false));
    }

    @Test
    void aSentenceIsCutAtItsMarks() {
        String m = WelcomeText.MARK;
        assertEquals(List.of("Downloads go to ", " · ", ""), WelcomeText.parts("Downloads go to " + m + " · " + m));
        assertEquals(List.of("", " wohin: ", "."), WelcomeText.parts(m + " wohin: " + m + "."));
        assertEquals(List.of("no marks"), WelcomeText.parts("no marks"));
    }
}
