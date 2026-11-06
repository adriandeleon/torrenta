package com.torrenta.ui;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Popup;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(FxToolkit.class)
class WindowColorSchemeTest {

    @AfterEach
    void backToTheDefaultTheme() {
        Fx.run(Theme.DEFAULT::apply);
    }

    @Test
    void aDarkThemeIsADarkSchemeWhateverTheDesktopSays() {
        assertEquals(ColorScheme.DARK, WindowColorScheme.scheme(true), "the buttons draw light glyphs");
        assertEquals(ColorScheme.LIGHT, WindowColorScheme.scheme(false));
    }

    @Test
    void everyWindowIsToldTheThemesSchemeAsItOpensAndOnASwitch() {
        Fx.run(() -> WindowColorScheme.install(true));
        Stage first = Fx.call(() -> shown(new Stage()));
        assertEquals(ColorScheme.DARK, schemeOf(first), "a window opened under a dark theme");

        Fx.run(Theme.NORD_LIGHT::apply);
        assertEquals(ColorScheme.LIGHT, schemeOf(first), "a switch reaches a window already open");

        Fx.run(() -> first.setScene(new Scene(new Pane(), 120, 80)));
        assertEquals(ColorScheme.LIGHT, schemeOf(first), "a scene put in later");

        Stage second = Fx.call(() -> shown(new Stage()));
        assertEquals(ColorScheme.LIGHT, schemeOf(second), "a window opened after the switch");

        Fx.run(first::hide);
        Fx.run(Theme.CUPERTINO_DARK::apply);
        Fx.run(first::show);
        assertEquals(ColorScheme.DARK, schemeOf(first), "shown again after a switch it missed");
    }

    @Test
    void aPopupIsLeftToTheDesktop() {
        ColorScheme desktop = Fx.call(() -> Platform.getPreferences().getColorScheme());
        boolean opposite = desktop != ColorScheme.DARK;
        Fx.run(() -> WindowColorScheme.install(opposite));
        Stage owner = Fx.call(() -> shown(new Stage()));
        Popup popup = Fx.call(() -> {
            Popup p = new Popup();
            p.getContent().add(new Pane());
            p.show(owner);
            return p;
        });
        assertEquals(WindowColorScheme.scheme(opposite), schemeOf(owner));
        assertEquals(desktop, Fx.call(() -> popup.getScene().getPreferences().getColorScheme()));
    }

    private static Stage shown(Stage stage) {
        stage.setScene(new Scene(new Pane(), 120, 80));
        stage.show();
        return stage;
    }

    private static ColorScheme schemeOf(Stage stage) {
        return Fx.call(() -> stage.getScene().getPreferences().getColorScheme());
    }
}
