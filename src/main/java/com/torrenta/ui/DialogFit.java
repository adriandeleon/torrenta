package com.torrenta.ui;

import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * A dialog's window is never shorter than its content needs, so the buttons along its bottom stay
 * in view. The dialogs open at a size chosen for their usual content; a longer sentence, a card
 * that appears, another language or a larger font made some taller than that and cut their footer
 * off. {@link #install} grows the window to the content's minimum height when it is shown and
 * whenever the content grows after, never past the screen, and keeps the person from shrinking it
 * below that. It never shrinks a window.
 */
final class DialogFit {

    private DialogFit() {}

    /** Called once the stage has its scene. */
    static void install(Stage stage) {
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> fit(stage));
        Scene scene = stage.getScene();
        if (scene != null)
            scene.addPostLayoutPulseListener(() -> {
                if (stage.isShowing()) fit(stage);
            });
    }

    /** The height the window has to grow to, or 0 when it is tall enough; pure. */
    static double grownHeight(double windowHeight, double sceneHeight, double contentNeeds, double screenHeight) {
        double chrome = Math.max(0, windowHeight - sceneHeight);
        double wanted = Math.min(contentNeeds + chrome, screenHeight);
        return windowHeight + 0.5 < wanted ? Math.ceil(wanted) : 0;
    }

    static void fit(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null || !(scene.getRoot() instanceof Parent root)) return;
        double needs = root.minHeight(scene.getWidth());
        Rectangle2D screen = screenOf(stage);
        double chrome = Math.max(0, stage.getHeight() - scene.getHeight());
        double floor = Math.min(needs + chrome, screen.getHeight());
        if (stage.getMinHeight() < floor) stage.setMinHeight(floor);
        double grown = grownHeight(stage.getHeight(), scene.getHeight(), needs, screen.getHeight());
        if (grown == 0) return;
        stage.setHeight(grown);
        // Grown down past the screen's bottom: moved up, never above its top.
        if (stage.getY() + grown > screen.getMaxY()) stage.setY(Math.max(screen.getMinY(), screen.getMaxY() - grown));
    }

    private static Rectangle2D screenOf(Stage stage) {
        var screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), Math.max(1, stage.getWidth()), 1);
        return (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
    }

    /**
     * For the capture: the scene's height, how far down its lowest shown button reaches, and
     * whether that is inside the window.
     */
    static String describe(Scene scene) {
        double bottom = lowestButton(scene.getRoot());
        return "scene=" + Math.round(scene.getHeight()) + " buttons-to=" + Math.round(bottom) + " fits="
                + (bottom <= scene.getHeight() + 0.5);
    }

    private static double lowestButton(Node node) {
        if (!node.isVisible()) return 0;
        double lowest = 0;
        if (node instanceof ButtonBase) {
            Bounds b = node.localToScene(node.getBoundsInLocal());
            lowest = b.getMaxY();
        }
        // A scrolled page's buttons are reached by scrolling, not by the window's height.
        if (node instanceof Parent p && !(node instanceof javafx.scene.control.ScrollPane))
            for (Node child : p.getChildrenUnmodifiable()) lowest = Math.max(lowest, lowestButton(child));
        return lowest;
    }
}
