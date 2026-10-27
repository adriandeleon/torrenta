package com.torrenta.ui;

import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The dialogs a test opens: a modal one runs a nested event loop, so it is opened with
 * {@link #later} (never inside {@link Fx#run}, which would wait for it to close), found with
 * {@link #awaitPane} or {@link #awaitStage} and answered from the test's thread.
 */
final class Dialogs {

    private Dialogs() {}

    /** Runs on the FX thread without waiting: for an action that shows a modal dialog. */
    static void later(Runnable action) {
        Platform.runLater(action);
    }

    /** The showing JavaFX {@code Dialog}'s pane whose title is {@code title}, or any when null. */
    static DialogPane awaitPane(String what) {
        Fx.waitUntil(what, () -> pane().isPresent());
        return Fx.call(() -> pane().orElseThrow());
    }

    private static Optional<DialogPane> pane() {
        for (Window w : List.copyOf(Window.getWindows()))
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p)
                return Optional.of(p);
        return Optional.empty();
    }

    /** The showing stage, other than {@code except}, whose title is {@code title}. */
    static Stage awaitStage(String title) {
        Fx.waitUntil("a window titled " + title, () -> stage(title).isPresent());
        return Fx.call(() -> stage(title).orElseThrow());
    }

    static Optional<Stage> stage(String title) {
        for (Window w : List.copyOf(Window.getWindows()))
            if (w instanceof Stage s && s.isShowing() && title.equals(s.getTitle())) return Optional.of(s);
        return Optional.empty();
    }

    /** Whether no dialog pane shows any more. */
    static boolean noPane() {
        return pane().isEmpty();
    }

    /** Types into the dialog's first text field and presses its OK. */
    static void answer(DialogPane pane, String text) {
        Fx.run(() -> {
            ((TextField) pane.lookup(".text-field")).setText(text);
            press(pane, ButtonBar.ButtonData.OK_DONE);
        });
        Fx.waitUntil("the dialog to close", Dialogs::noPane);
    }

    /** Presses the button with that role; FX thread. */
    static void press(DialogPane pane, ButtonBar.ButtonData data) {
        for (ButtonType b : pane.getButtonTypes())
            if (b.getButtonData() == data) {
                ((Button) pane.lookupButton(b)).fire();
                return;
            }
        throw new AssertionError("no " + data + " button in " + pane.getButtonTypes());
    }

    /** Every node under {@code root} of that type, depth first; FX thread. */
    static <T extends Node> List<T> all(Parent root, Class<T> type) {
        List<T> found = new java.util.ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Node> void collect(Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node)) found.add(type.cast(node));
        if (node instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null)
            collect(sp.getContent(), type, found);
        if (node instanceof javafx.scene.control.TabPane tp)
            for (var t : tp.getTabs()) if (t.getContent() != null) collect(t.getContent(), type, found);
        if (node instanceof javafx.scene.control.TitledPane tp && tp.getContent() != null)
            collect(tp.getContent(), type, found);
        if (node instanceof Parent p) for (Node child : p.getChildrenUnmodifiable()) collect(child, type, found);
    }

    /** The first labeled control under {@code root} whose text is {@code text}; FX thread. */
    static <T extends Labeled> T labeled(Parent root, Class<T> type, String text) {
        return all(root, type).stream()
                .filter(l -> text.equals(l.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " \"" + text + "\""));
    }
}
