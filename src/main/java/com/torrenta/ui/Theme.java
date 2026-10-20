package com.torrenta.ui;

/**
 * A theme: the control stylesheet for the application's windows.
 *
 * <p>Both are "Caret &amp; Ink", carried over from Editora and Termina (same author) — Caret teal on
 * Ink navy. The stylesheets are AtlantaFX-derived and self-contained (their only external references
 * are modena assets that resolve out of {@code javafx.controls}), so they are applied with
 * {@code Application.setUserAgentStylesheet} directly and AtlantaFX itself is not a dependency.
 */
public enum Theme {
    EDITORA_DARK("editora-dark", "Editora Dark", true),
    EDITORA_LIGHT("editora-light", "Editora Light", false);

    private final String id;
    private final String displayName;
    private final boolean dark;

    Theme(String id, String displayName, boolean dark) {
        this.id = id;
        this.displayName = displayName;
        this.dark = dark;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isDark() {
        return dark;
    }

    /** The user-agent stylesheet URL, as {@code Application.setUserAgentStylesheet} wants it. */
    public String stylesheet() {
        var url = Theme.class.getResource("/com/torrenta/styles/" + id + ".css");
        if (url == null) throw new IllegalStateException("missing stylesheet for theme " + id);
        return url.toExternalForm();
    }

    public static Theme byId(String id, Theme fallback) {
        for (Theme theme : values()) {
            if (theme.id.equals(id)) return theme;
        }
        return fallback;
    }
}
