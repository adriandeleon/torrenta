package com.torrenta.ui;

import java.util.Optional;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.torrenta.search.SearchException;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.search.TorznabProvider;

import static com.torrenta.i18n.Messages.tr;

/**
 * One Torznab endpoint, added or edited: name, URL, API key, enabled, and a Test that asks the
 * server who it is.
 *
 * <p>Shaped as the Add dialog: its own stage, the app's stylesheet and font, Enter saves, Escape
 * cancels. Save is held back while the URL is not an http(s) URL ({@link TorznabEndpoint#problem}).
 * Test runs {@code t=caps} on a virtual thread and reports on the line above the buttons; a blank
 * name takes the title it finds.
 */
final class SearchProviderDialog {

    /** Asks the server; swapped for a fake in a capture or a test. */
    interface Tester {
        TorznabProvider.Capabilities test(TorznabEndpoint endpoint) throws SearchException;
    }

    private final Stage stage = new Stage();
    private final TextField name = new TextField();
    private final TextField url = new TextField();
    private final PasswordField apiKey = new PasswordField();
    private final TextField apiKeyShown = new TextField();
    private final CheckBox show = new CheckBox(tr("dialog.provider.show"));
    private final CheckBox enabled = new CheckBox(tr("dialog.provider.enabled"));
    private final Label problem = new Label();
    private final Label status = new Label();
    private final Button test = new Button(tr("dialog.provider.test"));
    private final Button save = new Button(tr("common.save"));
    private final Tester tester;
    private int categories;
    /** The edited entry waits for a key that another machine has; it keeps waiting while the field is blank. */
    private boolean keyNeeded;

    private TorznabEndpoint result;

    /** @param existing the entry being edited, or null for a new one */
    SearchProviderDialog(Window owner, TorznabEndpoint existing) {
        this(owner, existing, endpoint -> new TorznabProvider(endpoint).capabilities());
    }

    SearchProviderDialog(Window owner, TorznabEndpoint existing, Tester tester) {
        this.tester = tester;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(existing == null ? tr("dialog.provider.title.add") : tr("dialog.provider.title.edit"));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        name.setPromptText(tr("dialog.provider.name.prompt"));
        form.addRow(row++, muted(tr("common.name")), name);
        url.setPromptText("http://localhost:9696/1/api");
        form.addRow(row++, muted(tr("dialog.common.url")), url);
        apiKey.setPromptText(tr("dialog.provider.apiKey.prompt"));
        apiKeyShown.setPromptText(apiKey.getPromptText());
        apiKeyShown.textProperty().bindBidirectional(apiKey.textProperty());
        apiKeyShown.visibleProperty().bind(show.selectedProperty());
        apiKeyShown.managedProperty().bind(show.selectedProperty());
        apiKey.visibleProperty().bind(show.selectedProperty().not());
        apiKey.managedProperty().bind(show.selectedProperty().not());
        StackPane keys = new StackPane(apiKey, apiKeyShown);
        HBox.setHgrow(keys, Priority.ALWAYS);
        form.addRow(row++, muted(tr("dialog.provider.apiKey")), new HBox(8, keys, show));
        form.addRow(row++, new Label(), enabled);

        problem.getStyleClass().add("dialog-hint");
        problem.setWrapText(true);
        problem.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> url.getText().isBlank()
                                ? ""
                                : TorznabEndpoint.problem(url.getText()).orElse(""),
                        url.textProperty()));
        form.add(problem, 1, row++);

        Label hint = hint(tr("dialog.provider.hint"));
        hint.setMaxWidth(Double.MAX_VALUE);

        status.getStyleClass().add("dialog-hint");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);

        test.setOnAction(e -> runTest());
        test.disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> TorznabEndpoint.problem(url.getText()).isPresent(), url.textProperty()));
        save.setDefaultButton(true);
        save.setOnAction(e -> confirm());
        save.disableProperty().bind(test.disableProperty());
        Button cancel = new Button(tr("common.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, test, status, spacer, cancel, save);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox content = new VBox(12, form, hint, buttons);
        content.setPadding(new Insets(14));
        // Tall enough for the hint to wrap: a VBox short of room gives a wrapping label one line.
        Scene scene = new Scene(content, 600, 360);
        scene.getStylesheets()
                .add(SearchProviderDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);
        DialogFit.install(stage);

        if (existing == null) {
            enabled.setSelected(true);
            categories = -1;
        } else {
            name.setText(existing.name());
            url.setText(existing.url());
            apiKey.setText(existing.apiKey());
            enabled.setSelected(existing.enabled());
            categories = existing.categories();
            keyNeeded = existing.keyNeeded();
            if (categories >= 0) status.setText(categoriesLine(existing.name(), categories));
            if (keyNeeded) {
                apiKey.setPromptText(tr("dialog.provider.apiKey.needed"));
                apiKeyShown.setPromptText(apiKey.getPromptText());
            }
        }
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<TorznabEndpoint> showAndWait() {
        (url.getText().isBlank() ? url : keyNeeded ? apiKey : name).requestFocus();
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

    /** Types a URL into a new dialog, as a person would before pressing Test; for a capture. */
    void prefillUrl(String text) {
        url.setText(text);
    }

    /** The Test line as it reads now, for a capture. */
    String statusText() {
        return status.getText();
    }

    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Save. */
    void confirm() {
        if (save.isDisabled()) return;
        result = endpoint();
        stage.hide();
    }

    /** Presses Test. */
    void runTest() {
        if (test.isDisabled()) return;
        TorznabEndpoint endpoint = endpoint();
        status.setText(tr("dialog.provider.testing"));
        test.disableProperty().unbind();
        test.setDisable(true);
        Consumer<String> done = text -> Platform.runLater(() -> {
            status.setText(text);
            test.disableProperty()
                    .bind(Bindings.createBooleanBinding(
                            () -> TorznabEndpoint.problem(url.getText()).isPresent(), url.textProperty()));
        });
        Thread.ofVirtual().name("torznab-test").start(() -> {
            try {
                TorznabProvider.Capabilities caps = tester.test(endpoint);
                Platform.runLater(() -> {
                    if (name.getText().isBlank() && !caps.title().isEmpty()) name.setText(caps.title());
                    categories = caps.categories().size();
                });
                String who = caps.title().isEmpty() ? endpoint.name() : caps.title();
                done.accept(categoriesLine(who, caps.categories().size()));
            } catch (SearchException e) {
                done.accept(e.getMessage());
            } catch (RuntimeException e) {
                done.accept(tr("dialog.common.failed", e.toString()));
            }
        });
    }

    /** "Prowlarr · 42 categories", the line Test and the page both show. */
    static String categoriesLine(String title, int categories) {
        String count = tr("dialog.provider.categories", categories);
        return title.isEmpty() ? count : tr("dialog.provider.categoriesLine", title, count);
    }

    private TorznabEndpoint endpoint() {
        return new TorznabEndpoint(
                name.getText(), url.getText(), apiKey.getText(), enabled.isSelected(), categories, keyNeeded);
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
