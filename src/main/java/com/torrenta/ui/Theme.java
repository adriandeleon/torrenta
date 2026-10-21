package com.torrenta.ui;

import java.util.function.Supplier;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;

import static com.torrenta.i18n.Messages.tr;

/**
 * A theme: the control stylesheet for the application's windows, applied with
 * {@code Application.setUserAgentStylesheet} and switched live from Settings.
 *
 * <p>The Editora pair is "Caret &amp; Ink", carried over from Editora and Termina (same author):
 * Caret teal on Ink navy, authored on AtlantaFX's variables. Nord and Cupertino are AtlantaFX's own,
 * from the dependency, the same ones Editora offers. Every stylesheet is self-contained (the only
 * external references are modena assets that resolve out of {@code javafx.controls}).
 */
public enum Theme {
    EDITORA_LIGHT("editora-light", false, null),
    EDITORA_DARK("editora-dark", true, null),
    NORD_LIGHT("nord-light", false, NordLight::new),
    NORD_DARK("nord-dark", true, NordDark::new),
    CUPERTINO_LIGHT("cupertino-light", false, CupertinoLight::new),
    CUPERTINO_DARK("cupertino-dark", true, CupertinoDark::new);

    /** The first-run theme, and the fallback for a name the settings file holds that is not one. */
    public static final Theme DEFAULT = EDITORA_DARK;

    private final String id;
    private final boolean dark;
    private final Supplier<atlantafx.base.theme.Theme> atlantafx;

    Theme(String id, boolean dark, Supplier<atlantafx.base.theme.Theme> atlantafx) {
        this.id = id;
        this.dark = dark;
        this.atlantafx = atlantafx;
    }

    public String id() {
        return id;
    }

    /** The name the ChoiceBox shows, in the UI language ({@code theme.<id>}); read at call time, never cached. */
    public String displayName() {
        return tr("theme." + id);
    }

    public boolean isDark() {
        return dark;
    }

    /** The user-agent stylesheet URL, as {@code Application.setUserAgentStylesheet} wants it. */
    public String stylesheet() {
        if (atlantafx == null) return resolve(Theme.class, "/com/torrenta/styles/" + id + ".css");
        // AtlantaFX answers with a class-path *path*. Applied at startup JavaFX loads that itself,
        // but a switch at runtime goes through a context class loader that is null on the FX
        // thread and fails; a full URL works both times.
        String sheet = atlantafx.get().getUserAgentStylesheet();
        return sheet.contains(":") ? sheet : resolve(atlantafx.get().getClass(), sheet);
    }

    private String resolve(Class<?> owner, String path) {
        var url = owner.getResource(path);
        if (url == null) throw new IllegalStateException("missing stylesheet for theme " + id + ": " + path);
        return url.toExternalForm();
    }

    public static Theme byId(String id, Theme fallback) {
        for (Theme theme : values()) {
            if (theme.id.equals(id)) return theme;
        }
        return fallback;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
