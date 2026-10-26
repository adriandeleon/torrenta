package com.torrenta.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.torrenta.engine.Engine;
import com.torrenta.engine.Following;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowRulesTest {

    private static final String ID = "aa".repeat(20);
    private static final Following AT_3 = new Following("magnet:?xs=urn:btpk:" + "00".repeat(32), 3, "");

    private static Optional<Engine.MutableItem> item(long seq, byte[] ih) {
        return Optional.of(new Engine.MutableItem(seq, Map.of("ih", ih)));
    }

    private static byte[] bytes(int n, int first) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) 0xaa);
        b[0] = (byte) first;
        return b;
    }

    @Test
    void aHigherNumberNamingAnotherTorrentIsANewVersion() {
        Optional<FollowRules.Step> step = FollowRules.step(ID, AT_3, item(4, bytes(20, 0xbb)));
        assertTrue(step.isPresent());
        assertTrue(step.get().adds());
        assertEquals("bb" + "aa".repeat(19), step.get().infoHash());
        assertEquals(4, step.get().seq());
    }

    @Test
    void nothingNewerOrNothingNamedIsNothingToDo() {
        assertEquals(Optional.empty(), FollowRules.step(ID, AT_3, Optional.empty()), "nobody answered");
        assertEquals(Optional.empty(), FollowRules.step(ID, AT_3, item(3, bytes(20, 0xbb))), "the same number");
        assertEquals(Optional.empty(), FollowRules.step(ID, AT_3, item(2, bytes(20, 0xbb))), "an older one");
        assertEquals(
                Optional.empty(),
                FollowRules.step(ID, AT_3, Optional.of(new Engine.MutableItem(9, Map.of("x", 1)))),
                "a value that names no torrent");
    }

    /** A republish of the same torrent moves the number and adds nothing, a v2 hash read as its id. */
    @Test
    void aHigherNumberNamingTheSameTorrentOnlyMovesTheNumber() {
        assertEquals(Optional.of(new FollowRules.Step("", 5)), FollowRules.step(ID, AT_3, item(5, bytes(20, 0xaa))));
        String v2Id = "aa".repeat(20);
        assertEquals(
                Optional.of(new FollowRules.Step("", 5)),
                FollowRules.step(v2Id, AT_3, item(5, bytes(32, 0xaa))),
                "the id is the v2 hash cut to 20 bytes");
        assertFalse(new FollowRules.Step("", 5).adds());
    }

    @Test
    void theIdIsTheHashCutTo20Bytes() {
        assertEquals("ab".repeat(20), FollowRules.idOf("AB".repeat(20)));
        assertEquals("ab".repeat(20), FollowRules.idOf("ab".repeat(32)));
    }

    @Test
    void aNewVersionReplacesTheOldOnceItIsComplete() {
        assertTrue(FollowRules.replaced(torrent(ID, 1.0)));
        assertFalse(FollowRules.replaced(torrent(ID, 0.99)));
        assertFalse(FollowRules.replaced(torrent("url-" + "0".repeat(40), 1.0)), "a link's download replaces nothing");
    }

    static Torrent torrent(String id, double progress) {
        return new Torrent(
                id,
                "name",
                1000,
                (long) (1000 * progress),
                progress,
                progress >= 1 ? TorrentState.SEEDING : TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                "/data/films",
                false,
                false,
                0,
                "Films",
                List.of("hd"),
                "",
                List.of(),
                false,
                false,
                -1,
                false,
                false);
    }
}
