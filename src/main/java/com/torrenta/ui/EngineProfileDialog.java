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

import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.transmission.TransmissionEngine;

import static com.torrenta.i18n.Messages.tr;

/**
 * One remote engine profile, added or edited: name, the daemon's URL, username, password, and a
 * Test connection that asks the daemon its version and RPC version.
 *
 * <p>Shaped as the search-provider dialog: its own stage, the app's stylesheet and font, Enter
 * saves, Escape cancels. Save is held back while the URL is not an http(s) URL with a host
 * ({@link EngineProfile#problem}). Test runs {@code session-get} on a virtual thread and reports
 * on the line beside the buttons; a blank name takes the host.
 */
final class EngineProfileDialog {

    /** Asks the daemon; swapped for a fake in a capture or a test. */
    interface Tester {
        TransmissionEngine.Daemon test(EngineProfile profile) throws EngineException;
    }

    private final Stage stage = new Stage();
    private final TextField name = new TextField();
    private final TextField url = new TextField();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField passwordShown = new TextField();
    private final CheckBox show = new CheckBox(tr("dialog.profile.show"));
    private final Label problem = new Label();
    private final Label status = new Label();
    private final Button test = new Button(tr("dialog.profile.test"));
    private final Button save = new Button(tr("common.save"));
    private final Tester tester;
    private final String id;
    private EngineProfile result;

    /** @param existing the profile being edited, or null for a new one */
    EngineProfileDialog(Window owner, EngineProfile existing, Tester tester) {
        this.tester = tester;
        this.id = existing == null ? EngineProfile.newId() : existing.id();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(existing == null ? tr("dialog.profile.title.add") : tr("dialog.profile.title.edit"));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        name.setPromptText(tr("dialog.profile.name.prompt"));
        form.addRow(row++, muted(tr("common.name")), name);
        url.setPromptText("http://seedbox.local:9091");
        form.addRow(row++, muted(tr("dialog.common.url")), url);
        username.setPromptText(tr("dialog.profile.username.prompt"));
        form.addRow(row++, muted(tr("dialog.profile.username")), username);
        passwordShown.textProperty().bindBidirectional(password.textProperty());
        passwordShown.visibleProperty().bind(show.selectedProperty());
        passwordShown.managedProperty().bind(show.selectedProperty());
        password.visibleProperty().bind(show.selectedProperty().not());
        password.managedProperty().bind(show.selectedProperty().not());
        StackPane secrets = new StackPane(password, passwordShown);
        HBox.setHgrow(secrets, Priority.ALWAYS);
        form.addRow(row++, muted(tr("dialog.profile.password")), new HBox(8, secrets, show));

        problem.getStyleClass().add("dialog-hint");
        problem.setWrapText(true);
        problem.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> url.getText().isBlank()
                                ? ""
                                : EngineProfile.problem(url.getText()).orElse(""),
                        url.textProperty()));
        form.add(problem, 1, row++);

        Label hint = hint(tr("dialog.profile.hint"));
        hint.setMaxWidth(Double.MAX_VALUE);

        status.getStyleClass().add("dialog-hint");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);

        test.setOnAction(e -> runTest());
        test.disableProperty().bind(invalidUrl());
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
        Scene scene = new Scene(content, 620, 380);
        scene.getStylesheets()
                .add(EngineProfileDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);
        DialogFit.install(stage);

        if (existing != null) {
            name.setText(existing.name());
            url.setText(existing.url());
            username.setText(existing.username());
            password.setText(existing.password());
        }
    }

    private javafx.beans.binding.BooleanBinding invalidUrl() {
        return Bindings.createBooleanBinding(
                () -> EngineProfile.problem(url.getText()).isPresent(), url.textProperty());
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<EngineProfile> showAndWait() {
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
        result = profile();
        stage.hide();
    }

    /** Presses Test. */
    void runTest() {
        if (test.isDisabled()) return;
        EngineProfile profile = profile();
        status.setText(tr("dialog.profile.testing"));
        test.disableProperty().unbind();
        test.setDisable(true);
        Consumer<String> done = text -> Platform.runLater(() -> {
            status.setText(text);
            test.disableProperty().bind(invalidUrl());
        });
        Thread.ofVirtual().name("profile-test").start(() -> {
            try {
                TransmissionEngine.Daemon daemon = tester.test(profile);
                Platform.runLater(() -> {
                    if (name.getText().isBlank()) name.setText(profile.hostLine());
                });
                done.accept(daemonLine(daemon));
            } catch (EngineException e) {
                done.accept(e.getMessage());
            } catch (RuntimeException e) {
                done.accept(tr("dialog.common.failed", e.toString()));
            }
        });
    }

    /** "Transmission 4.1.0 · RPC 18 · downloads to /srv", the line Test and the page both show. */
    static String daemonLine(TransmissionEngine.Daemon daemon) {
        return tr(
                "dialog.profile.daemonLine",
                daemon.version(),
                daemon.rpcVersion(),
                daemon.downloadDir().isEmpty() ? 0 : 1,
                daemon.downloadDir());
    }

    private EngineProfile profile() {
        return new EngineProfile(
                id, name.getText(), EngineProfile.Kind.REMOTE, url.getText(), username.getText(), password.getText());
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
