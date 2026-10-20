package com.torrenta.ui;

import java.util.Optional;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.TorrentLimits;

/**
 * The Limits dialog: one torrent's own rate caps, connection counts, piece order and share limits.
 *
 * <p>Shaped as the Add dialog: its own stage, the app's stylesheet and font, Enter applies, Escape
 * cancels. Opened for the first selected torrent and applied to the whole selection, as every
 * other action is. The share limits are either the global ones from Settings or this torrent's
 * own; the window keeps the override beside the settings and applies it from its poll.
 */
final class LimitsDialog {

    /** What Apply hands back: the limits, and the share override or null for the global one. */
    record Result(TorrentLimits limits, ShareLimit shareOverride) {}

    private enum ShareChoice {
        GLOBAL("The global limits"),
        OWN("This torrent's own");

        private final String label;

        ShareChoice(String label) {
            this.label = label;
        }
    }

    private final Stage stage = new Stage();
    private final Spinner<Integer> download = kibSpinner();
    private final Spinner<Integer> upload = kibSpinner();
    private final Spinner<Integer> connections = countSpinner(10_000);
    private final Spinner<Integer> uploadSlots = countSpinner(1_000);
    private final CheckBox firstAndLast = new CheckBox("Download the first and last pieces first");
    private final ChoiceBox<ShareChoice> shareChoice = new ChoiceBox<>();
    private final Spinner<Double> ratio =
            new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1000, 0, 0.1));
    private final Spinner<Integer> minutes = countSpinner(366 * 24 * 60);
    private final ChoiceBox<ShareLimit.Action> action = new ChoiceBox<>();
    private final Button apply = new Button("Apply");
    private Result result;

    /**
     * @param subject the torrent's name, or what the selection is ("3 torrents")
     * @param current the first selected torrent's limits now
     * @param override its own share limit, or null when the global one applies
     * @param global the global share limit, shown so the choice is an informed one
     * @param hasMetadata whether piece order can be set yet; a magnet without metadata cannot
     */
    LimitsDialog(
            Window owner,
            String subject,
            TorrentLimits current,
            ShareLimit override,
            ShareLimit global,
            boolean hasMetadata) {
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Limits: " + subject);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        form.addRow(row++, muted("Download"), unit(download, "KiB/s, 0 for none"));
        form.addRow(row++, muted("Upload"), unit(upload, "KiB/s, 0 for none"));
        form.addRow(row++, muted("Connections"), unit(connections, "peers at once, 0 for the global limit"));
        form.addRow(row++, muted("Upload slots"), unit(uploadSlots, "peers unchoked at once, 0 for the global limit"));
        form.addRow(row++, muted("Pieces"), firstAndLast);
        if (!hasMetadata) {
            firstAndLast.setDisable(true);
            form.add(hint("Available once the metadata has arrived."), 1, row++);
        }

        shareChoice.getItems().addAll(ShareChoice.values());
        shareChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(ShareChoice c) {
                return c == null ? "" : c.label;
            }

            @Override
            public ShareChoice fromString(String s) {
                return null;
            }
        });
        form.addRow(row++, muted("Share limits"), shareChoice);
        Label globalHint = hint("Global: " + global + " (Settings → Queue and share limits)");
        globalHint.setMaxWidth(Double.MAX_VALUE);
        form.add(globalHint, 1, row++);
        ratio.setEditable(true);
        ratio.setPrefWidth(110);
        commitOnBlur(ratio);
        form.addRow(row++, muted("Ratio"), unit(ratio, "uploaded over downloaded, 0 for none"));
        form.addRow(row++, muted("Seeding time"), unit(minutes, "minutes, 0 for none"));
        action.getItems().addAll(ShareLimit.Action.values());
        action.setConverter(new StringConverter<>() {
            @Override
            public String toString(ShareLimit.Action a) {
                return a == null ? "" : a.label();
            }

            @Override
            public ShareLimit.Action fromString(String s) {
                return null;
            }
        });
        form.addRow(row++, muted("When reached"), action);
        shareChoice.valueProperty().addListener((o, was, now) -> {
            boolean own = now == ShareChoice.OWN;
            ratio.setDisable(!own);
            minutes.setDisable(!own);
            action.setDisable(!own);
        });

        download.getValueFactory().setValue(current.downloadLimit() / 1024);
        upload.getValueFactory().setValue(current.uploadLimit() / 1024);
        connections.getValueFactory().setValue(current.maxConnections());
        uploadSlots.getValueFactory().setValue(current.maxUploads());
        firstAndLast.setSelected(current.firstAndLastFirst());
        ShareLimit shown = override != null ? override : global;
        ratio.getValueFactory().setValue(shown.ratio());
        minutes.getValueFactory().setValue(shown.seedingMinutes());
        action.setValue(shown.action());
        shareChoice.setValue(override != null ? ShareChoice.OWN : ShareChoice.GLOBAL);

        apply.setDefaultButton(true);
        apply.setOnAction(e -> confirm());
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, apply);

        VBox content = new VBox(12, form, buttons);
        content.setPadding(new Insets(14));
        Scene scene = new Scene(content, 560, hasMetadata ? 400 : 420);
        scene.getStylesheets()
                .add(LimitsDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<Result> showAndWait() {
        download.requestFocus();
        stage.showAndWait();
        return Optional.ofNullable(result);
    }

    Scene scene() {
        return stage.getScene();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    String title() {
        return stage.getTitle();
    }

    /** Closes without applying, as Escape would. */
    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Apply. Typed values are committed first, or a number typed and not entered is lost. */
    void confirm() {
        for (Spinner<?> s : java.util.List.of(download, upload, connections, uploadSlots, minutes, ratio))
            s.increment(0);
        TorrentLimits limits = new TorrentLimits(
                download.getValue() * 1024,
                upload.getValue() * 1024,
                connections.getValue(),
                uploadSlots.getValue(),
                firstAndLast.isSelected());
        ShareLimit override = shareChoice.getValue() == ShareChoice.OWN
                ? new ShareLimit(ratio.getValue(), minutes.getValue(), action.getValue())
                : null;
        result = new Result(limits, override);
        stage.hide();
    }

    private static Spinner<Integer> kibSpinner() {
        return countSpinner(10_000_000);
    }

    private static Spinner<Integer> countSpinner(int max) {
        Spinner<Integer> spinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, max, 0));
        spinner.setEditable(true);
        spinner.setPrefWidth(110);
        commitOnBlur(spinner);
        return spinner;
    }

    /** A typed value commits on focus loss as well as Enter, or a number typed before Tab is lost. */
    private static void commitOnBlur(Spinner<?> spinner) {
        spinner.focusedProperty().addListener((o, was, now) -> {
            if (!now) spinner.increment(0);
        });
    }

    private static HBox unit(Region control, String text) {
        HBox box = new HBox(8, control, hint(text));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint");
        label.setWrapText(true);
        return label;
    }

    /** The label column never shrinks: a clipped "Conne…" tells nobody what the field is. */
    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }
}
