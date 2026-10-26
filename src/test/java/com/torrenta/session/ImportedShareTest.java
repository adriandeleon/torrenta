package com.torrenta.session;

import java.util.List;

import com.torrenta.engine.ShareLimit;
import com.torrenta.migrate.ImportCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Another client's own share limit for a torrent, as Torrenta's. */
class ImportedShareTest {

    private static ImportCandidate limited(double ratio, long minutes) {
        return new ImportCandidate(
                "qBittorrent",
                "a",
                "a",
                "",
                new byte[0],
                null,
                "/x",
                "",
                "",
                List.of(),
                false,
                List.of(),
                0,
                0,
                ratio,
                minutes);
    }

    private static final ShareLimit GLOBAL = new ShareLimit(3, 600, ShareLimit.Action.REMOVE);

    @Test
    void theGlobalLimitIsNoLimitOfItsOwn() {
        assertNull(Session.importedShare(limited(-2, -2), GLOBAL));
    }

    @Test
    void itsOwnHalvesAndTheGlobalsForTheRest() {
        assertEquals(new ShareLimit(2, 600, ShareLimit.Action.REMOVE), Session.importedShare(limited(2, -2), GLOBAL));
        assertEquals(
                new ShareLimit(0, 90, ShareLimit.Action.REMOVE),
                Session.importedShare(limited(-1, 90), GLOBAL),
                "-1: none of that kind");
    }
}
