package com.torrenta.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoveConfirmTest {

    @Test
    void aKindNamesItsKeysInCamelCase() {
        assertEquals("feed", RemoveConfirm.Kind.FEED.key());
        assertEquals("watchFolder", RemoveConfirm.Kind.WATCH_FOLDER.key());
        assertEquals("engineProfile", RemoveConfirm.Kind.ENGINE_PROFILE.key());
    }

    /** Every kind asks a question of its own that names the thing, never a bare key. */
    @ParameterizedTest
    @EnumSource(RemoveConfirm.Kind.class)
    void everyKindHasAHeaderAndAQuestionNamingTheThing(RemoveConfirm.Kind kind) {
        String header = RemoveConfirm.header(kind);
        String question = RemoveConfirm.question(kind, "Linux ISOs");
        assertFalse(header.startsWith("confirm."), header);
        assertFalse(question.startsWith("confirm."), question);
        assertTrue(question.contains("Linux ISOs"), question);
        assertTrue(header.startsWith("Remove"), header);
    }
}
