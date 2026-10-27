package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Modal windows in a test: a press that opens one runs {@link #later} (a nested event loop must
 * not start inside {@link Fx#call}, which would wait for it to end), {@link #await} finds it by
 * title once it shows, and {@link #answer} or {@link #press} closes it as a hand would. Nodes are
 * found by walking the scene graph, since the windows keep their controls private.
 */
public final class Modals {

    private Modals() {}

    /** Runs on the FX thread after this call returns: for anything that ends in {@code showAndWait}. */
    public static void later(Runnable action) {
        Platform.runLater(action);
    }

    /** The showing stage titled {@code title}, waited for. */
    public static Stage await(String title) {
        Fx.waitUntil("a window titled \"" + title + "\"", () -> find(title).isPresent());
        return Fx.call(() -> find(title).orElseThrow());
    }

    /** Whether a stage titled {@code title} shows now. FX thread or not. */
    public static boolean showing(String title) {
        return Fx.call(() -> find(title).isPresent());
    }

    private static Optional<Stage> find(String title) {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.isShowing() && title.equals(s.getTitle())) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** Waits until no stage titled {@code title} shows. */
    public static void gone(String title) {
        Fx.waitUntil("\"" + title + "\" to close", () -> find(title).isEmpty());
    }

    /**
     * Answers the alert titled {@code title}: its OK or Yes button when {@code yes}, else its
     * cancel; then waits for it to close.
     */
    public static void answer(String title, boolean yes) {
        Fx.waitUntil("an alert titled \"" + title + "\"", () -> alert(title).isPresent());
        Stage stage = Fx.call(() -> alert(title).orElseThrow());
        Fx.run(() -> {
            DialogPane pane = (DialogPane) stage.getScene().getRoot();
            for (ButtonType type : pane.getButtonTypes()) {
                ButtonBar.ButtonData data = type.getButtonData();
                boolean accepts = data == ButtonBar.ButtonData.OK_DONE || data == ButtonBar.ButtonData.YES;
                boolean declines = data == ButtonBar.ButtonData.CANCEL_CLOSE || data == ButtonBar.ButtonData.NO;
                if (yes ? accepts : declines) {
                    ((Button) pane.lookupButton(type)).fire();
                    return;
                }
            }
            throw new AssertionError("no " + (yes ? "yes" : "no") + " button on " + title);
        });
        Fx.waitUntil("\"" + title + "\" to close", () -> !stage.isShowing());
    }

    /** Whether an alert titled {@code title} shows now; the Settings window can share its title. */
    public static boolean alertShowing(String title) {
        return Fx.call(() -> alert(title).isPresent());
    }

    private static Optional<Stage> alert(String title) {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s
                    && s.isShowing()
                    && title.equals(s.getTitle())
                    && s.getScene() != null
                    && s.getScene().getRoot() instanceof DialogPane) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** Fires the button labelled {@code text} in {@code window}'s scene. FX thread or not. */
    public static void press(Window window, String text) {
        Fx.run(() -> button(window.getScene().getRoot(), text).fire());
    }

    /** The button labelled {@code text} under {@code root}. */
    public static Button button(Node root, String text) {
        return first(root, Button.class, b -> text.equals(b.getText()))
                .orElseThrow(() -> new AssertionError("no button \"" + text + "\""));
    }

    /** Every node of {@code type} under {@code root}, in document order, matching {@code test}. */
    public static <T> List<T> all(Node root, Class<T> type, Predicate<? super T> test) {
        List<T> out = new ArrayList<>();
        collect(root, type, test, out, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return out;
    }

    public static <T> Optional<T> first(Node root, Class<T> type, Predicate<? super T> test) {
        List<T> found = all(root, type, test);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /** The {@code n}-th text field or area under {@code root}, counting from 0. */
    public static TextInputControl field(Node root, int n) {
        return all(root, TextInputControl.class, f -> true).get(n);
    }

    /** The labelled nodes' texts under {@code root}, for an assertion about what a window says. */
    public static List<String> texts(Node root) {
        return all(root, Labeled.class, l -> l.getText() != null && !l.getText().isEmpty()).stream()
                .map(Labeled::getText)
                .toList();
    }

    /** Also into a scroll pane's content and a split pane's items before they have a skin, once each. */
    private static <T> void collect(
            Node node, Class<T> type, Predicate<? super T> test, List<T> out, java.util.Set<Node> seen) {
        if (!seen.add(node)) return;
        if (type.isInstance(node) && test.test(type.cast(node))) out.add(type.cast(node));
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null)
            collect(scroll.getContent(), type, test, out, seen);
        if (node instanceof javafx.scene.control.SplitPane split)
            for (Node item : split.getItems()) collect(item, type, test, out, seen);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, type, test, out, seen);
        }
    }
}
