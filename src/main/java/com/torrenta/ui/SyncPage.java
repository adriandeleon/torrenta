package com.torrenta.ui;

import com.torrenta.sync.SyncEngine;

/**
 * The Sync page's rules. The switch unlocks the page: every control waits on it. Connecting is a
 * separate step, taken once the switch is on and the URL is one git can use; the setting
 * {@code sync.enabled} records that it succeeded, not that the box is ticked.
 */
final class SyncPage {

    /** What committing the URL (the switch, Enter, leaving the field, a created repository) does. */
    enum Connect {
        /** Nothing to connect to yet: ask for a URL or a repository on GitHub. */
        NEED_URL,
        /** A password or token in the URL: refused, git signs in by itself. */
        HAS_SECRET,
        CONNECT
    }

    private SyncPage() {}

    static Connect connect(String url) {
        String u = url == null ? "" : url.trim();
        if (!SyncEngine.isUsableUrl(u)) return Connect.NEED_URL;
        if (SyncEngine.hasCredentials(u)) return Connect.HAS_SECRET;
        return Connect.CONNECT;
    }

    /** Create on GitHub: with the switch on, no repository named yet and none being created. */
    static boolean canCreate(boolean on, String url, boolean creating) {
        return on && (url == null || url.isBlank()) && !creating;
    }
}
