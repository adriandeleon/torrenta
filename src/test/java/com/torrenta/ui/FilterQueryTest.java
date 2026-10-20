package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterQueryTest {

    private static final long NOW = 1_800_000_000L;
    private static final long DAY = 86_400L;
    private static final long GIB = 1L << 30;

    /** A torrent with every field at a dull default; each call returns a copy with one changed. */
    private static final class T implements Cloneable {
        String id = "abcdef0123";
        String name;
        long size;
        TorrentState state = TorrentState.DOWNLOADING;
        long uploaded;
        long downloaded;
        long added;
        String tracker = "";
        boolean paused;
        boolean sequential;
        String category = "";
        List<String> tags = List.of();
        String error = "";
        List<String> trackers = List.of();

        static T of(String name) {
            T t = new T();
            t.name = name;
            return t;
        }

        private T copy(java.util.function.Consumer<T> change) {
            try {
                T t = (T) clone();
                change.accept(t);
                return t;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        T id(String v) {
            return copy(t -> t.id = v);
        }

        T size(long v) {
            return copy(t -> t.size = v);
        }

        T state(TorrentState v) {
            return copy(t -> t.state = v);
        }

        T transfer(long up, long down) {
            return copy(t -> {
                t.uploaded = up;
                t.downloaded = down;
            });
        }

        T added(long v) {
            return copy(t -> t.added = v);
        }

        T tracker(String v) {
            return copy(t -> t.tracker = v);
        }

        T trackers(String... hosts) {
            return copy(t -> t.trackers = List.of(hosts));
        }

        T pausedByUser() {
            return copy(t -> t.paused = true);
        }

        T inOrder() {
            return copy(t -> t.sequential = true);
        }

        T category(String v) {
            return copy(t -> t.category = v);
        }

        T tags(String... v) {
            return copy(t -> t.tags = List.of(v));
        }

        T error(String v) {
            return copy(t -> t.error = v);
        }

        Torrent torrent() {
            return new Torrent(
                    id,
                    name,
                    size,
                    0,
                    0,
                    state,
                    0,
                    0,
                    0,
                    0,
                    uploaded,
                    downloaded,
                    added,
                    0,
                    tracker,
                    "",
                    paused,
                    sequential,
                    0,
                    category,
                    tags,
                    error,
                    trackers,
                    false);
        }
    }

    private static boolean matches(String query, T t) {
        return FilterQuery.parse(query, NOW).matches(t.torrent());
    }

    private static List<FilterQuery.Chip> chips(String query) {
        return FilterQuery.parse(query, NOW).chips();
    }

    private static FilterQuery.Chip chip(String query) {
        List<FilterQuery.Chip> chips = chips(query);
        assertEquals(1, chips.size(), () -> "chips of " + query + ": " + chips);
        return chips.getFirst();
    }

    private static final T UBUNTU = T.of("Ubuntu 26.04 Desktop");

    // ---- plain words

    @Test
    void blankMatchesEverythingAndHasNoChips() {
        for (String q : new String[] {null, "", "   "}) {
            assertTrue(matches(q, UBUNTU));
            assertTrue(FilterQuery.parse(q, NOW).isEmpty());
            assertTrue(FilterQuery.parse(q, NOW).chips().isEmpty());
        }
    }

    @Test
    void aWordIsACaseInsensitiveSubstringOfTheName() {
        assertTrue(matches("UBUNTU", UBUNTU));
        assertTrue(matches("  desktop ", UBUNTU));
        assertTrue(matches("26.0", UBUNTU));
        assertFalse(matches("fedora", UBUNTU));
        assertTrue(chips("ubuntu desktop").isEmpty(), "plain words are not chips");
    }

    @Test
    void everyWordMustMatchInAnyOrder() {
        assertTrue(matches("ubuntu 26.04", UBUNTU), "what used to be one substring still matches");
        assertTrue(matches("desktop ubuntu", UBUNTU));
        assertFalse(matches("ubuntu server", UBUNTU));
    }

    @Test
    void quotesKeepAPhraseTogether() {
        assertTrue(matches("\"26.04 desktop\"", UBUNTU));
        assertFalse(matches("\"desktop 26.04\"", UBUNTU));
        assertTrue(matches("\"26.04 desk", UBUNTU), "an unclosed quote runs to the end");
        assertTrue(matches("\"\" ubuntu", UBUNTU), "empty quotes are nothing");
    }

    @Test
    void aMinusNegatesAWord() {
        assertFalse(matches("-desktop", UBUNTU));
        assertTrue(matches("-server", UBUNTU));
        assertFalse(matches("-\"26.04 desktop\"", UBUNTU));
        assertTrue(matches("-", T.of("a - b")), "a lone minus is just text");
        assertFalse(matches("-", UBUNTU));
        assertTrue(matches("x-men", T.of("X-Men 1080p")), "a minus inside a word is text");
    }

    // ---- state

    @Test
    void stateFollowsTheStateColumn() {
        T downloading = T.of("a");
        T seeding = T.of("a").state(TorrentState.SEEDING);
        T pausedSeed = seeding.pausedByUser();
        assertTrue(matches("state:downloading", downloading));
        assertFalse(matches("state:downloading", seeding));
        assertTrue(matches("state:seeding", seeding));
        assertFalse(matches("state:seeding", pausedSeed), "a user pause outranks the engine's state");
        assertTrue(matches("state:paused", pausedSeed));
        assertFalse(matches("state:paused", seeding));
        assertTrue(matches("state:checking", T.of("a").state(TorrentState.CHECKING)));
        assertTrue(matches("state:metadata", T.of("a").state(TorrentState.FETCHING_METADATA)));
        assertTrue(matches("state:finished", T.of("a").state(TorrentState.FINISHED)));
        assertTrue(matches("STATE:Seeding", seeding), "key and value ignore case");
        T broken = seeding.pausedByUser().error("disk full");
        assertTrue(matches("state:error", broken));
        assertTrue(matches("state:errored", broken));
        assertFalse(matches("state:paused", broken), "an error outranks a pause, as in the column");
        assertFalse(matches("state:seeding", broken));
        assertFalse(matches("state:error", seeding));
    }

    @Test
    void stateChipsSayTheColumnsWord() {
        assertEquals(new FilterQuery.Chip("state:seeding", "state is Seeding", true), chip("state:seeding"));
        assertEquals("state is Paused", chip("state:paused").label());
        assertEquals("state is Fetching metadata", chip("state:metadata").label());
        assertEquals("state is Error", chip("state:errored").label());
        assertEquals("not state is Seeding", chip("-state:seeding").label());
    }

    // ---- labels

    @Test
    void categoryIsExactIgnoringCase() {
        T linux = T.of("a").category("Linux ISOs");
        assertTrue(matches("cat:\"linux isos\"", linux));
        assertTrue(matches("category:\"Linux ISOs\"", linux));
        assertFalse(matches("cat:linux", linux), "not a prefix");
        assertFalse(matches("cat:linux", T.of("a")));
        assertEquals("category Linux ISOs", chip("cat:\"Linux ISOs\"").label());
        assertEquals("cat:\"Linux ISOs\"", chip("cat:\"Linux ISOs\"").term(), "the term as typed");
    }

    @Test
    void tagIsOneOfTheTags() {
        T kept = T.of("a").tags("hd", "keep");
        assertTrue(matches("tag:keep", kept));
        assertTrue(matches("tag:KEEP", kept));
        assertFalse(matches("tag:kee", kept));
        assertFalse(matches("-tag:keep", kept));
        assertTrue(matches("-tag:keep", T.of("a").tags("hd")));
        assertEquals("not tag keep", chip("-tag:keep").label());
        assertEquals("-tag:keep", chip("-tag:keep").term());
    }

    @Test
    void trackerIsPartOfTheHost() {
        T t = T.of("a").tracker("udp://tracker.OpenBitTorrent.com:6969/announce");
        assertTrue(matches("tracker:openbittorrent", t));
        assertFalse(matches("tracker:announce", t), "the path is not the host");
        assertFalse(matches("tracker:6969", t), "nor the port");
        assertFalse(matches("tracker:open", T.of("a")), "no tracker matches nothing");
        T many = T.of("a").trackers("tracker.example.org", "udp.opentrackr.org");
        assertTrue(matches("tracker:opentrackr", many), "any of its trackers, not just the current one");
        assertTrue(matches("tracker:EXAMPLE", many));
        assertFalse(matches("tracker:demonii", many));
        assertEquals("tracker openbittorrent", chip("tracker:openbittorrent").label());
    }

    @Test
    void hashIsAPrefixOfTheId() {
        T t = T.of("a").id("abcdef0123");
        assertTrue(matches("hash:abc", t));
        assertTrue(matches("hash:ABCDEF", t));
        assertFalse(matches("hash:bcd", t));
        assertEquals("hash abc…", chip("hash:abc").label());
        assertFalse(chip("hash:xyz").valid());
    }

    @Test
    void isSequential() {
        assertTrue(matches("is:sequential", T.of("a").inOrder()));
        assertFalse(matches("is:sequential", T.of("a")));
        assertTrue(matches("-is:sequential", T.of("a")));
        assertEquals("sequential", chip("is:sequential").label());
        assertEquals("not sequential", chip("-is:sequential").label());
        assertFalse(chip("is:private").valid(), "the snapshot does not carry it");
    }

    // ---- comparisons

    @Test
    void ratioCompares() {
        T half = T.of("a").transfer(50, 100);
        T two = T.of("a").transfer(200, 100);
        T fresh = T.of("a");
        T onlyUp = T.of("a").transfer(10, 0);
        assertTrue(matches("ratio<1", half));
        assertFalse(matches("ratio<1", two));
        assertTrue(matches("ratio>=2", two));
        assertFalse(matches("ratio>2", two));
        assertTrue(matches("ratio<=0.5", half));
        assertTrue(matches("ratio=2", two));
        assertTrue(matches("ratio:>1.5", two), "the operator may follow a colon");
        assertTrue(matches("ratio:2", two), "a bare colon is equal");
        assertTrue(matches("ratio<1", fresh), "nothing moved counts as 0");
        assertTrue(matches("ratio>100", onlyUp), "only up is infinite");
        assertEquals("ratio under 1", chip("ratio<1").label());
        assertEquals("ratio at least 2", chip("ratio>=2").label());
        assertEquals("ratio over 1.5", chip("ratio:>1.5").label());
        assertEquals("ratio at most 1", chip("ratio<=1").label());
        assertEquals("ratio is 2", chip("ratio=2").label());
    }

    @Test
    void sizeTakesBinaryUnits() {
        T five = T.of("a").size(5 * GIB);
        assertTrue(matches("size>4g", five));
        assertFalse(matches("size>5g", five));
        assertTrue(matches("size>=5g", five));
        assertTrue(matches("size<6G", five));
        assertTrue(matches("size<6GiB", five));
        assertTrue(matches("size<6gb", five));
        assertTrue(matches("size<0.005t", five));
        assertTrue(matches("size>5000m", five));
        assertTrue(matches("size>5000000k", five));
        assertTrue(matches("size=" + 5 * GIB, five), "no suffix is bytes");
        assertTrue(matches("size>4.5g", five));
        assertEquals("size over 4.0 GiB", chip("size>4g").label());
        assertEquals("size under 512 B", chip("size<512").label());
        assertEquals("size at least 1.5 MiB", chip("size>=1.5m").label());
    }

    @Test
    void addedCountsBackFromNow() {
        T today = T.of("a").added(NOW - 3600);
        T lastWeek = T.of("a").added(NOW - 8 * DAY);
        T unknown = T.of("a").added(0);
        assertTrue(matches("added<7d", today));
        assertFalse(matches("added<7d", lastWeek));
        assertTrue(matches("added>7d", lastWeek));
        assertFalse(matches("added>7d", today));
        assertTrue(matches("added<2h", today));
        assertFalse(matches("added<1h", today), "exactly an hour is not under one");
        assertTrue(matches("added<=1h", today));
        assertTrue(matches("added>=1w", lastWeek));
        assertTrue(matches("added:<2w", lastWeek));
        assertFalse(matches("added<7d", unknown), "an unknown time matches neither way");
        assertFalse(matches("added>7d", unknown));
        assertEquals("added in the last 7 days", chip("added<7d").label());
        assertEquals("added more than 1 week ago", chip("added>1w").label());
        assertEquals("added at most 3 hours ago", chip("added<=3h").label());
        assertEquals("added at least 2 days ago", chip("added>=2d").label());
    }

    // ---- combining

    @Test
    void termsAndTogether() {
        T t = T.of("Ubuntu 26.04").state(TorrentState.SEEDING).category("Linux").tags("keep");
        assertTrue(matches("ubuntu state:seeding cat:linux tag:keep", t));
        assertFalse(matches("ubuntu state:seeding cat:movies", t));
        assertFalse(matches("fedora state:seeding", t));
        assertEquals(
                List.of("state is Seeding", "category linux", "tag keep"),
                chips("ubuntu state:seeding cat:linux tag:keep").stream()
                        .map(FilterQuery.Chip::label)
                        .toList());
    }

    // ---- what does not read

    @Test
    void anUnknownKeyIsNameTextAndAnInvalidChip() {
        T starWars = T.of("Star Wars: Episode IV");
        assertTrue(matches("wars:", starWars), "a colon in a name is still findable");
        assertTrue(matches("Wars: episode", starWars));
        assertFalse(matches("foo:bar", starWars));
        assertTrue(matches("foo:bar", T.of("a foo:bar b")));
        FilterQuery.Chip c = chip("foo:bar");
        assertFalse(c.valid());
        assertEquals("foo:bar", c.term());
        assertEquals("unknown filter foo", c.label());
    }

    @Test
    void aMalformedValueIsNameTextAndAnInvalidChip() {
        for (String q : List.of(
                "state:se",
                "state:",
                "state<seeding",
                "cat:",
                "tag:",
                "tracker:",
                "hash:",
                "is:",
                "is:fast",
                "ratio<",
                "ratio<abc",
                "ratio<-1",
                "size>4x",
                "size>g",
                "size>",
                "added<7",
                "added<7y",
                "added=7d",
                "added<99999999999999999999d",
                "added<9999999999999999w",
                "size>>4g",
                "ratio<1<2")) {
            FilterQuery query = FilterQuery.parse(q, NOW);
            assertEquals(1, query.chips().size(), q);
            assertFalse(query.chips().getFirst().valid(), q);
            assertTrue(query.matches(T.of("x " + q + " y").torrent()), q + " is matched as name text");
            assertFalse(query.matches(T.of("other").torrent()), q);
        }
    }

    @Test
    void aNegatedInvalidTermIsNegatedNameText() {
        assertTrue(matches("-foo:bar", UBUNTU));
        assertFalse(matches("-foo:bar", T.of("a foo:bar")));
        assertFalse(chip("-foo:bar").valid());
    }

    @Test
    void aQuotedKeyIsAPhrase() {
        assertTrue(matches("\"state:seeding\"", T.of("why state:seeding")));
        assertFalse(matches("\"state:seeding\"", T.of("a").state(TorrentState.SEEDING)));
        assertTrue(chips("\"state:seeding\"").isEmpty());
    }

    @Test
    void neverThrowsOnOddInput() {
        for (String q : List.of(
                ":", "::", "<", ">=", "=", "\"", "-\"", "-:", "a:b:c", "state::seeding", "-- -", "size>1e9", "\t\n")) {
            FilterQuery.parse(q, NOW).matches(UBUNTU.torrent());
        }
        assertTrue(matches("state::seeding", T.of("state::seeding")), "an odd value is name text");
    }
}
