package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncPageTest {

    @Test
    void anEmptyUrlAsksForOne() {
        assertEquals(SyncPage.Connect.NEED_URL, SyncPage.connect(""));
        assertEquals(SyncPage.Connect.NEED_URL, SyncPage.connect("   "));
        assertEquals(SyncPage.Connect.NEED_URL, SyncPage.connect(null));
    }

    @Test
    void aUrlThatLooksLikeAnOptionIsNotOne() {
        assertEquals(SyncPage.Connect.NEED_URL, SyncPage.connect("--upload-pack=x"));
    }

    @Test
    void aTokenInTheUrlIsRefused() {
        assertEquals(SyncPage.Connect.HAS_SECRET, SyncPage.connect("https://me:secret@github.com/me/s.git"));
    }

    @Test
    void aPlainUrlConnectsTrimmed() {
        assertEquals(SyncPage.Connect.CONNECT, SyncPage.connect(" https://github.com/me/s.git "));
        assertEquals(SyncPage.Connect.CONNECT, SyncPage.connect("git@github.com:me/s.git"));
    }

    @Test
    void createWantsTheSwitchOnAndNoRepositoryYet() {
        assertTrue(SyncPage.canCreate(true, "", false));
        assertTrue(SyncPage.canCreate(true, "  ", false));
        assertFalse(SyncPage.canCreate(false, "", false), "the switch is off");
        assertFalse(SyncPage.canCreate(true, "https://github.com/me/s.git", false), "a repository is named");
        assertFalse(SyncPage.canCreate(true, "", true), "one is being created");
    }
}
