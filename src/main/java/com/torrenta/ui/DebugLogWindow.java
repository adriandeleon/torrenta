package com.torrenta.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.torrenta.AppInfo;

/**
 * Shows what {@link DebugLog} has captured, with a button to copy it into a bug report. Termina's.
 * Non-modal and reusable, like the event log's window: closing hides it, the next opening shows it
 * again, refreshed.
 */
final class DebugLogWindow {

    private final Stage stage = new Stage();
    private final TextArea text = new TextArea();
    private final Label path = new Label();

    DebugLogWindow(Window owner) {
        text.setEditable(false);
        text.setWrapText(false);
        text.getStyleClass().add("debug-log-text");
        VBox.setVgrow(text, Priority.ALWAYS);

        path.getStyleClass().add("debug-log-file");
        path.setWrapText(true);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            DebugLog.clear();
            refresh();
        });
        Button close = new Button("Close");
        close.setOnAction(e -> stage.hide());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, refresh, copy, clear, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, text, path, buttons);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 820, 460);
        scene.getStylesheets()
                .add(DebugLogWindow.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
        });

        stage.setTitle(AppInfo.NAME + " — Debug Log");
        stage.setScene(scene);
        // Owned but not modal: the point is to watch it while provoking whatever is going wrong.
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
    }

    private void refresh() {
        // As text, not a prompt: the area takes the focus, and a focused area shows no prompt.
        String captured = DebugLog.text();
        text.setText(captured.isEmpty() ? "Nothing has been logged this session" : captured);
        // Scrolled to the end: the newest line is the one being looked for.
        text.positionCaret(text.getText().length());
        text.setScrollTop(Double.MAX_VALUE);
        path.setText(DebugLog.file() == null ? "" : "Log file: " + DebugLog.file());
    }

    /** Shows, refreshed, or brings to the front; hidden by its Close button or Esc. */
    void show() {
        refresh();
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
        } else {
            stage.show();
        }
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    /** For a capture of the window itself. */
    Scene scene() {
        return stage.getScene();
    }
}
