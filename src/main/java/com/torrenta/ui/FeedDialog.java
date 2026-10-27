package com.torrenta.ui;

import java.util.Optional;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
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

import com.torrenta.rss.Feed;
import com.torrenta.rss.FeedException;
import com.torrenta.rss.FeedSource;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * One feed, added or edited: URL, an optional name, and its own refresh interval or the global
 * one. Shaped as the Add dialog: its own stage, the app's stylesheet and font, Enter saves,
 * Escape cancels. Save fetches a new (or re-pointed) URL once on a virtual thread, so a feed that
 * cannot be read is never added: a failure is said on the line above the buttons; a success
 * closes the dialog, with a blank name filled in from the feed's title. The RSS tab and Settings
 * → RSS share it.
 */
final class FeedDialog {

    private final Stage stage = new Stage();
    private final TextField url = new TextField();
    private final TextField name = new TextField();
    private final CheckBox ownInterval = new CheckBox(tr("dialog.feed.ownInterval"));
    private final Spinner<Integer> minutes = new Spinner<>();
    private final Label problem = new Label();
    private final Label status = new Label();
    private final Button save = new Button(tr("common.save"));
    private final FeedSource source;
    private final @Nullable Feed existing;
    private @Nullable Feed result;
    private boolean fetching;

    /**
     * @param existing the feed being edited, or null for a new one
     * @param source fetches the feed to check it; the service's, or a stub in a capture
     * @param globalMinutes the interval in force when the feed has none of its own, for the hint
     */
    FeedDialog(Window owner, @Nullable Feed existing, FeedSource source, int globalMinutes) {
        this.existing = existing;
        this.source = source;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(existing == null ? tr("dialog.feed.title.add") : tr("dialog.feed.title.edit"));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        url.setPromptText("https://tracker.example/rss?passkey=…");
        form.addRow(row++, muted(tr("dialog.common.url")), url);
        name.setPromptText(tr("dialog.feed.name.prompt"));
        form.addRow(row++, muted(tr("common.name")), name);
        minutes.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                Feed.MIN_MINUTES, Feed.MAX_MINUTES, globalMinutes, 5));
        minutes.setEditable(true);
        Spinners.commitOnBlur(minutes);
        minutes.setPrefWidth(90);
        minutes.disableProperty().bind(ownInterval.selectedProperty().not());
        Label every = new Label(tr("dialog.feed.every", Integer.toString(globalMinutes)));
        every.getStyleClass().add("dialog-hint");
        HBox interval = new HBox(8, ownInterval, minutes, every);
        interval.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        form.addRow(row++, muted(tr("dialog.feed.refresh")), interval);

        problem.getStyleClass().add("dialog-hint");
        problem.setWrapText(true);
        problem.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> url.getText().isBlank()
                                ? ""
                                : Feed.problem(url.getText()).orElse(""),
                        url.textProperty()));
        form.add(problem, 1, row++);

        Label hint = hint(tr("dialog.feed.hint"));
        hint.setMaxWidth(Double.MAX_VALUE);

        status.getStyleClass().add("dialog-hint");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);

        save.setDefaultButton(true);
        save.setOnAction(e -> confirm());
        save.disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> Feed.problem(url.getText()).isPresent(), url.textProperty()));
        Button cancel = new Button(tr("common.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, status, spacer, cancel, save);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox content = new VBox(12, form, hint, buttons);
        content.setPadding(new Insets(14));
        Scene scene = new Scene(content, 600, 320);
        scene.getStylesheets()
                .add(FeedDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);
        DialogFit.install(stage);

        if (existing != null) {
            url.setText(existing.url());
            name.setText(existing.name());
            ownInterval.setSelected(existing.refreshMinutes() > 0);
            if (existing.refreshMinutes() > 0) minutes.getValueFactory().setValue(existing.refreshMinutes());
        }
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<Feed> showAndWait() {
        (url.getText().isBlank() ? url : name).requestFocus();
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

    /** Types a URL into a new dialog, as a person would; for a capture. */
    void prefillUrl(String text) {
        url.setText(text);
    }

    /** The line above the buttons as it reads now, for a capture. */
    String statusText() {
        return status.getText();
    }

    void cancel() {
        result = null;
        stage.hide();
    }

    /**
     * Presses Save: a feed whose URL is unchanged is saved at once; a new URL is fetched first, on
     * a virtual thread, and the dialog closes only when it read.
     */
    void confirm() {
        if (save.isDisabled() || fetching) return;
        Feed candidate = feed();
        if (existing != null && existing.url().equals(candidate.url())) {
            result = candidate;
            stage.hide();
            return;
        }
        fetching = true;
        status.setText(tr("dialog.feed.fetching"));
        Thread.ofVirtual().name("feed-check").start(() -> {
            try {
                FeedSource.Result r = source.fetch(candidate, "", "");
                Platform.runLater(() -> {
                    fetching = false;
                    if (name.getText().isBlank() && !r.title().isEmpty()) name.setText(r.title());
                    result = feed();
                    stage.hide();
                });
            } catch (FeedException e) {
                Platform.runLater(() -> {
                    fetching = false;
                    status.setText(e.getMessage());
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    fetching = false;
                    status.setText(tr("dialog.common.failed", e.toString()));
                });
            }
        });
    }

    private Feed feed() {
        int own = ownInterval.isSelected() ? minutes.getValue() : 0;
        String id = existing == null ? Feed.newId() : existing.id();
        return new Feed(id, url.getText(), name.getText(), own);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint");
        label.setWrapText(true);
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }
}
