package com.torrenta.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(FxToolkit.class)
class TruncatedTipsTest {

    private static final String LONG = "Fight.Club.1999.REMASTERED.1080p.BluRay.x264-SOMETHING";

    @AfterEach
    void close() {
        Fx.closeAll();
    }

    @Test
    void onlyACutTextIsSaid() {
        assertEquals(LONG, TruncatedTips.tipText(true, LONG));
        assertNull(TruncatedTips.tipText(false, LONG), "the label shows it all already");
        assertNull(TruncatedTips.tipText(true, null));
        assertNull(TruncatedTips.tipText(true, "  "));
    }

    @Test
    void aCutLabelUnderThePointerGetsItsWholeText() {
        Label cut = new Label(LONG);
        cut.setMaxWidth(80);
        Label whole = new Label("Short");
        show(new VBox(cut, whole));

        hover(cut);
        Tooltip tip = tooltipOf(cut);
        assertTrue(Fx.call(cut::isTextTruncated), "the test's premise");
        assertEquals(LONG, Fx.call(tip::getText));

        hover(whole);
        assertNull(tooltipOf(cut), "lent, then taken back when the pointer moves on");
        assertNull(tooltipOf(whole), "nothing to add to a label shown whole");
    }

    @Test
    void theTooltipShowsAfterTheUsualPause() {
        Label cut = new Label(LONG);
        cut.setMaxWidth(80);
        show(new VBox(cut));

        // Lent during the move's capture phase, the label's own handler still hears that move.
        hover(cut);
        Tooltip tip = tooltipOf(cut);
        Fx.waitUntil("the tooltip shows", () -> Fx.call(tip::isShowing));
    }

    @Test
    void theInnerLabelOfAGraphicIsTheOneThatSpeaks() {
        Label inner = new Label(LONG);
        inner.setMinWidth(0);
        Label cell = new Label(null, new HBox(inner));
        cell.setMaxWidth(80);
        show(new VBox(cell));

        hover(inner.lookup(".text"));
        assertEquals(LONG, Fx.call(() -> tooltipOf(inner).getText()));
        assertNull(tooltipOf(cell));
    }

    @Test
    void aTooltipOfItsOwnIsKept() {
        Label cut = new Label(LONG);
        cut.setMaxWidth(80);
        Tooltip own = new Tooltip("Its own words");
        cut.setTooltip(own);
        Node glyph = new Label("·");
        Tooltip.install(glyph, new Tooltip("The glyph's"));
        Label name = new Label(LONG, glyph);
        name.setMaxWidth(80);
        show(new VBox(cut, name));

        hover(cut);
        assertSame(own, tooltipOf(cut));
        hover(glyph);
        assertNull(tooltipOf(name), "the glyph inside the name explains itself");
    }

    private static void show(VBox root) {
        Fx.run(() -> {
            Stage stage = new Stage();
            Scene scene = new Scene(root, 300, 200);
            Fonts.installUiFont(scene);
            stage.setScene(scene);
            stage.show();
        });
        Fx.pulse();
    }

    /** What the scene's filter sees when the pointer moves over {@code node}. */
    private static void hover(Node node) {
        Fx.run(() -> node.fireEvent(new MouseEvent(
                MouseEvent.MOUSE_MOVED,
                1,
                1,
                1,
                1,
                MouseButton.NONE,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                new PickResult(node, 1, 1))));
    }

    private static Tooltip tooltipOf(Node node) {
        return Fx.call(() -> (Tooltip) node.getProperties().get("javafx.scene.control.Tooltip"));
    }
}
