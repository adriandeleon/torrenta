package com.torrenta.session;

import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import com.torrenta.engine.Engine;
import com.torrenta.engine.MutableKey;
import com.torrenta.engine.Publication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublishRulesTest {

    private static final String NOW = "aa".repeat(20);
    private static final String BEFORE = "bb".repeat(20);

    private static Publication at(long seq, String infoHash) {
        return new Publication("p", new MutableKey(new byte[32], new byte[64]), null, infoHash, seq, "");
    }

    private static Optional<Engine.MutableItem> found(long seq, String infoHash) {
        return Optional.of(
                new Engine.MutableItem(seq, Map.of("ih", HexFormat.of().parseHex(infoHash))));
    }

    @Test
    void theFirstVersionIsPutOnce() {
        assertEquals(1, PublishRules.putsNeeded(at(0, NOW), Optional.empty()));
    }

    @Test
    void aVersionStillHeldIsNotPutAgain() {
        assertEquals(0, PublishRules.putsNeeded(at(4, NOW), found(4, NOW)));
        assertEquals(0, PublishRules.putsNeeded(at(4, NOW), found(6, NOW)), "a higher number of the same");
        assertEquals(0, PublishRules.putsNeeded(at(0, ""), Optional.empty()), "nothing to publish yet");
    }

    @Test
    void aNewVersionGoesOneAboveWhatTheDhtHas() {
        assertEquals(1, PublishRules.putsNeeded(at(4, NOW), found(4, BEFORE)));
        assertEquals(1, PublishRules.putsNeeded(at(4, NOW), found(9, BEFORE)), "another copy of the key went higher");
    }

    /** Followers have seen 5: from nothing found, six puts; from 2 found, four; at most a round's worth. */
    @Test
    void aForgottenItemClimbsBackAboveTheRecord() {
        assertEquals(6, PublishRules.putsNeeded(at(5, NOW), Optional.empty()));
        assertEquals(4, PublishRules.putsNeeded(at(5, NOW), found(2, NOW)));
        assertEquals(PublishRules.CLIMB, PublishRules.putsNeeded(at(1000, NOW), Optional.empty()));
    }

    @Test
    void theV1HashIsPublishedWhenThereIsOne() {
        assertEquals(NOW, PublishRules.infoHashOf(NOW, "cc".repeat(32)));
        assertEquals("cc".repeat(32), PublishRules.infoHashOf("", "cc".repeat(32)));
        assertEquals(
                "d2:ih20:",
                new String(PublishRules.item(at(0, NOW)), 0, 8, java.nio.charset.StandardCharsets.US_ASCII));
    }
}
