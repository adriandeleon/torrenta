package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;

import com.torrenta.AppInfo;
import com.torrenta.engine.Inputs;

import static com.torrenta.i18n.Messages.tr;

/**
 * The window before the first torrent (the redesign's board 5): a drop zone with the four keys that
 * matter, the clipboard's magnet or link offered when there is one, and where downloads go. The
 * rules are {@link WelcomeText}; this only lays them out. Nothing here takes focus: the keys are the
 * way in, and a click on a key's row runs it for the mouse.
 */
final class Welcome extends VBox {

    /** One row of the key table: the chord as the menus show it, what it does, and the doing. */
    record Key(String chord, String text, Runnable run) {}

    private static final PseudoClass DRAGGING = PseudoClass.getPseudoClass("dragging");

    private final VBox dropZone;
    private final List<Key> keys;
    private final HBox offerCard = new HBox(12);
    private final HBox offerText = new HBox();
    private final HBox footer = new HBox();
    private final Runnable changeFolder;
    private Optional<WelcomeText.Offer> offer = Optional.empty();
    private String folder = "";

    Welcome(List<Key> keys, Runnable addClipboard, String addChord, Runnable changeFolder) {
        super(24);
        this.keys = List.copyOf(keys);
        this.changeFolder = changeFolder;
        getStyleClass().add("welcome");
        setAlignment(Pos.CENTER);
        setFillWidth(false);

        Label title = new Label(tr("welcome.title"));
        title.getStyleClass().add("welcome-title");
        title.setWrapText(true);
        title.setTextAlignment(TextAlignment.CENTER);
        title.setMinHeight(Region.USE_PREF_SIZE);
        Label hint = new Label(tr("welcome.hint", AppInfo.NAME));
        hint.getStyleClass().add("welcome-hint");
        // A translation runs longer than the English line; a paragraph, never an ellipsis.
        hint.setWrapText(true);
        hint.setTextAlignment(TextAlignment.CENTER);
        // A wrapped label in a VBox is laid out one line tall unless its height is held to its text's.
        hint.setMinHeight(Region.USE_PREF_SIZE);
        hint.setMaxWidth(440);
        VBox.setMargin(hint, new javafx.geometry.Insets(-4, 0, 6, 0));
        dropZone = new VBox(14, icon(), title, hint, keyTable());
        dropZone.setAlignment(Pos.CENTER);
        dropZone.getStyleClass().add("welcome-drop");
        dropZone.setMaxWidth(560);
        dropZone.setPrefWidth(560);

        Node clip = Icons.clipboard().at(18);
        offerText.setAlignment(Pos.CENTER_LEFT);
        offerText.setMinWidth(0);
        HBox.setHgrow(offerText, Priority.ALWAYS);
        Label chord = new Label(addChord);
        chord.getStyleClass().add("welcome-button-chord");
        Button add = new Button(tr("welcome.clipboard.add"), chord);
        add.setContentDisplay(ContentDisplay.RIGHT);
        add.getStyleClass().add("welcome-add");
        add.setFocusTraversable(false);
        add.setMinWidth(Region.USE_PREF_SIZE);
        add.setOnAction(e -> addClipboard.run());
        offerCard.getChildren().addAll(clip, offerText, add);
        offerCard.setAlignment(Pos.CENTER_LEFT);
        offerCard.getStyleClass().add("welcome-offer");
        offerCard.setMaxWidth(560);
        offerCard.setPrefWidth(560);
        offerCard.setVisible(false);
        offerCard.setManaged(false);

        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("welcome-footer");
        footer.setMaxWidth(560);

        getChildren().addAll(dropZone, offerCard, footer);
    }

    /** The clipboard's text as it is now; the card shows for a magnet or a link and hides otherwise. */
    void showClipboard(String text) {
        offer = WelcomeText.offer(text);
        offerText.getChildren().clear();
        offer.ifPresent(o -> {
            boolean magnet = o.kind() == Inputs.Kind.MAGNET;
            Label name = new Label(o.name());
            name.getStyleClass().add("welcome-offer-name");
            name.setMinWidth(0);
            String line;
            if (o.name().isEmpty())
                line = magnet ? tr("welcome.clipboard.magnetUnnamed") : tr("welcome.clipboard.linkUnnamed");
            else
                line = magnet
                        ? tr("welcome.clipboard.magnet", WelcomeText.MARK)
                        : tr("welcome.clipboard.link", WelcomeText.MARK);
            offerText.getChildren().setAll(sentence(line, name));
        });
        offerCard.setVisible(offer.isPresent());
        offerCard.setManaged(offer.isPresent());
    }

    /** Where downloads go, as {@link WelcomeText#folder} wrote it. */
    void showFolder(String text) {
        folder = text;
        footer.setVisible(true);
        footer.setManaged(true);
        Hyperlink change = new Hyperlink(tr("welcome.folder.change"));
        change.setFocusTraversable(false);
        change.setMinWidth(Region.USE_PREF_SIZE);
        change.setOnAction(e -> changeFolder.run());
        footer.getChildren()
                .setAll(sentence(tr("welcome.folder", WelcomeText.MARK, WelcomeText.MARK), folderLabel(text), change));
    }

    /** The path in the footer: a long one gives way from its start, so the folder's own name stays. */
    private static Label folderLabel(String text) {
        Label path = new Label(text);
        path.getStyleClass().addAll("mono", "welcome-path");
        path.setMinWidth(0);
        path.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        path.setTooltip(new javafx.scene.control.Tooltip(text));
        return path;
    }

    /** A panel under the drop zone (the window's "engine did not start" and its ways out). */
    void setProblem(Node problem) {
        getChildren().add(1, problem);
    }

    /** A folder on the engine's own machine: named, with no "change" (that is the daemon's setting). */
    void showRemoteFolder(String text) {
        folder = text;
        footer.setVisible(!text.isEmpty());
        footer.setManaged(!text.isEmpty());
        Label path = folderLabel(text);
        footer.getChildren().setAll(sentence(tr("welcome.folderRemote", WelcomeText.MARK), path));
    }

    /** Lights the drop zone while something that can be added is dragged over the window. */
    void setDragging(boolean on) {
        dropZone.pseudoClassStateChanged(DRAGGING, on);
    }

    /** One line for the development capture. */
    String describeForCapture() {
        String clip = offer.map(o -> o.kind() + ":" + o.name()).orElse("none");
        String chords = keys.stream().map(Key::chord).collect(Collectors.joining(","));
        return "shown=" + isVisible() + " keys=" + chords + " clipboard=" + clip + " folder=" + folder;
    }

    /** A translated sentence with its marks replaced by the nodes, in the translation's order. */
    private static List<Node> sentence(String formatted, Node... nodes) {
        List<String> parts = WelcomeText.parts(formatted);
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (!parts.get(i).isEmpty()) {
                Label text = new Label(parts.get(i));
                text.setMinWidth(Region.USE_PREF_SIZE);
                out.add(text);
            }
            if (i < nodes.length && i < parts.size() - 1) out.add(nodes[i]);
        }
        return out;
    }

    private GridPane keyTable() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);
        ColumnConstraints chords = new ColumnConstraints();
        chords.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().addAll(chords, new ColumnConstraints());
        int row = 0;
        for (Key key : keys) {
            Label chord = new Label(key.chord());
            chord.getStyleClass().addAll("welcome-key", "welcome-action");
            Label text = new Label(key.text());
            text.getStyleClass().addAll("welcome-key-text", "welcome-action");
            chord.setOnMouseClicked(e -> key.run().run());
            text.setOnMouseClicked(e -> key.run().run());
            grid.addRow(row++, chord, text);
        }
        return grid;
    }

    /** An arrow down into a tray, in a rounded tile: the arrow in the accent, the tray in the keys' hue. */
    private static Node icon() {
        SVGPath arrow = new SVGPath();
        arrow.setContent("M8 2v7.6M4.9 6.7L8 9.8l3.1-3.1");
        arrow.getStyleClass().add("welcome-arrow");
        SVGPath tray = new SVGPath();
        tray.setContent("M2.5 9.5v2.6a1.4 1.4 0 0 0 1.4 1.4h8.2a1.4 1.4 0 0 0 1.4-1.4V9.5");
        tray.getStyleClass().add("welcome-tray");
        Group drawn = new Group(arrow, tray);
        drawn.setScaleX(2.1);
        drawn.setScaleY(2.1);
        StackPane tile = new StackPane(new Group(drawn));
        tile.getStyleClass().add("welcome-icon");
        tile.setMinSize(56, 56);
        tile.setPrefSize(56, 56);
        tile.setMaxSize(56, 56);
        return tile;
    }
}
