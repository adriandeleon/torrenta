package com.torrenta.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.torrenta.engine.TorrentLabels;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Edit Tags dialog: a checkbox per tag that exists, and a field for new ones.
 *
 * <p>Shaped as the Add dialog: its own stage, the app's stylesheet and font, Enter applies, Escape
 * cancels. Opened for a whole selection: a tag every selected torrent has is checked, one some
 * have is indeterminate and stays as it is unless touched, one none has is clear. Apply hands back
 * what to add and what to remove, and the window applies that to each torrent's own tags.
 */
final class TagsDialog {

    /** Tags to put on every selected torrent, and tags to take off every one. */
    record Result(List<String> add, List<String> remove) {}

    private final Stage stage = new Stage();
    private final Map<String, CheckBox> boxes = new LinkedHashMap<>();
    private final TextField newTags = new TextField();
    private final Button apply = new Button(tr("dialog.common.apply"));
    private @Nullable Result result;

    /**
     * @param subject the torrent's name, or what the selection is ("3 torrents")
     * @param existing the tags that exist, from the store
     * @param selected each selected torrent's tags
     */
    TagsDialog(Window owner, String subject, List<String> existing, List<List<String>> selected) {
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(tr("dialog.tags.title", subject));

        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        all.addAll(existing);
        for (List<String> tags : selected) all.addAll(tags);
        VBox list = new VBox(6);
        for (String tag : all) {
            CheckBox box = new CheckBox(tag);
            long holders = selected.stream().filter(tags -> tags.contains(tag)).count();
            if (holders == selected.size()) box.setSelected(true);
            else if (holders > 0) {
                box.setAllowIndeterminate(true);
                box.setIndeterminate(true);
            }
            boxes.put(tag, box);
            list.getChildren().add(box);
        }
        if (all.isEmpty()) list.getChildren().add(hint(tr("dialog.tags.noTags")));
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(Math.min(260, 28 * Math.max(1, all.size()) + 16));
        scroll.getStyleClass().add("tags-list");
        list.setPadding(new Insets(8));

        newTags.setPromptText(tr("dialog.tags.newTags.prompt"));
        HBox.setHgrow(newTags, Priority.ALWAYS);
        HBox newRow = new HBox(8, muted(tr("common.add")), newTags);
        newRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        apply.setDefaultButton(true);
        apply.setOnAction(e -> confirm());
        Button cancel = new Button(tr("common.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, apply);

        VBox content = new VBox(12, muted(tr("dialog.common.tags")), scroll, newRow, buttons);
        content.setPadding(new Insets(14));
        Scene scene = new Scene(content, 420, 150 + scroll.getPrefHeight());
        scene.getStylesheets()
                .add(TagsDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);
        DialogFit.install(stage);
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<Result> showAndWait() {
        newTags.requestFocus();
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

    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Apply: checked adds, clear removes, indeterminate leaves alone; the field adds. */
    void confirm() {
        List<String> add = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        boxes.forEach((tag, box) -> {
            if (box.isIndeterminate()) return;
            if (box.isSelected()) add.add(tag);
            else remove.add(tag);
        });
        add.addAll(TorrentLabels.normalizeTags(List.of(newTags.getText())));
        result = new Result(TorrentLabels.normalizeTags(add), TorrentLabels.normalizeTags(remove));
        stage.hide();
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
