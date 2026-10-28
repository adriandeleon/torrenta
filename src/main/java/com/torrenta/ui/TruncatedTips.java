package com.torrenta.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;

import org.jspecify.annotations.Nullable;

/**
 * The full text of a label cut short for space, as a tooltip, everywhere: a torrent's name in the
 * list, a sidebar row, a tab, a column header, a status bar entry, a dialog's path. One mouse
 * filter per scene finds the {@link Labeled} under the pointer and, while JavaFX says its text is
 * truncated ({@link Labeled#isTextTruncated}), lends it the scene's one tooltip; a node that has
 * a tooltip of its own keeps it. Installed with the font ({@link Fonts#installUiFont}), which
 * every window and dialog already calls, so a new one cannot be left out.
 */
final class TruncatedTips {

    /** Where {@link Tooltip#install} keeps a node's tooltip, set too by {@code Control.setTooltip}. */
    private static final String TOOLTIP_KEY = "javafx.scene.control.Tooltip";

    private static final String INSTALLED = "torrenta.truncatedTips";

    private final Tooltip tip = new Tooltip();
    private @Nullable Labeled lent;

    private TruncatedTips() {
        tip.getStyleClass().add("truncated-tip");
        // A row's text moves on with the poll while the pointer rests on it.
        tip.setOnShowing(e -> {
            if (lent != null) tip.setText(lent.getText());
        });
    }

    /** FX thread. Once per scene; a second call is a no-op. */
    static void install(Scene scene) {
        if (scene.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) return;
        TruncatedTips tips = new TruncatedTips();
        scene.addEventFilter(
                MouseEvent.MOUSE_MOVED,
                e -> tips.hover(tips.labeledAt(e.getPickResult().getIntersectedNode())));
        scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> {
            if (e.getTarget() == scene) tips.hover(null);
        });
    }

    /** The same for a dialog's pane, whose scene comes when it is shown. */
    static void install(DialogPane pane) {
        if (pane.getScene() != null) install(pane.getScene());
        pane.sceneProperty().addListener((o, was, now) -> {
            if (now != null) install(now);
        });
    }

    private void hover(@Nullable Labeled labeled) {
        if (labeled == lent) return;
        if (lent != null) Tooltip.uninstall(lent, tip);
        lent = null;
        if (labeled == null) return;
        String text = tipText(labeled.isTextTruncated(), labeled.getText());
        if (text == null) return;
        tip.setText(text);
        Tooltip.install(labeled, tip);
        lent = labeled;
    }

    /**
     * The nearest {@link Labeled} at or above {@code node} (a cell's inner label before the cell),
     * or null when a node on the way has a tooltip of its own (the link glyph inside a name), which
     * then speaks for itself.
     */
    @Nullable
    Labeled labeledAt(@Nullable Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            Object own = n.getProperties().get(TOOLTIP_KEY);
            if (own != null && own != tip) return null;
            if (n instanceof Labeled l) return l;
        }
        return null;
    }

    /** What the tooltip says: the whole text, when it was cut. */
    static @Nullable String tipText(boolean truncated, @Nullable String text) {
        if (!truncated || text == null || text.isBlank()) return null;
        return text;
    }
}
