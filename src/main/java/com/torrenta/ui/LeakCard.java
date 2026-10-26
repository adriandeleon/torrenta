package com.torrenta.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * The leak check's card over the status bar, opened from the shield pill: the verdict and why,
 * the three addresses it was decided from, and what the check cannot see (tracker names resolve
 * through the system unless a proxy is set). Check now asks the exit server at once; Settings opens
 * the Connection page. In the window's own scene, as the {@link MessageLogPopup} is, in its chrome.
 */
final class LeakCard {

    static final double WIDTH = 460;

    private final StackPane host;
    private final Node anchor;
    private final Runnable onHidden;
    private final VBox card = new VBox(8);
    private final Region backdrop = new Region();
    private final Label sentence = new Label();
    private final GridPane rows = new GridPane();
    private final Button checkNow = new Button(tr("leak.checkNow"));
    private @Nullable LeakView shown;

    /**
     * @param anchor the status bar, which the card sits just above, at the left where the pill is
     * @param checkNow asks the exit server now
     * @param settings opens the Connection page
     */
    LeakCard(StackPane host, Node anchor, Runnable onHidden, Runnable checkNow, Runnable settings) {
        this.host = host;
        this.anchor = anchor;
        this.onHidden = onHidden;
        backdrop.getStyleClass().add("palette-backdrop");
        backdrop.setOnMousePressed(e -> hide());

        Label header = new Label(tr("leak.card.title"));
        header.getStyleClass().add("message-log-header");
        sentence.getStyleClass().add("leak-sentence");
        sentence.setWrapText(true);
        sentence.setMinHeight(Region.USE_PREF_SIZE);
        rows.setHgap(14);
        rows.setVgap(4);
        rows.getStyleClass().add("leak-rows");
        Label dns = new Label(tr("leak.dns"));
        dns.getStyleClass().add("message-log-hint");
        dns.setWrapText(true);
        dns.setMinHeight(Region.USE_PREF_SIZE);

        this.checkNow.getStyleClass().add("flat");
        this.checkNow.setFocusTraversable(false);
        this.checkNow.setOnAction(e -> {
            this.checkNow.setText(tr("leak.checking"));
            this.checkNow.setDisable(true);
            checkNow.run();
        });
        Button open = new Button(tr("command.settings"));
        open.getStyleClass().add("flat");
        open.setFocusTraversable(false);
        open.setOnAction(e -> {
            hide();
            settings.run();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, this.checkNow, open, spacer);
        footer.getStyleClass().add("message-log-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        card.getStyleClass().addAll("message-log", "leak-card");
        card.getChildren().addAll(header, sentence, rows, dns, footer);
        card.setPrefWidth(WIDTH);
        card.setMaxSize(WIDTH, Region.USE_PREF_SIZE);
        StackPane.setAlignment(card, Pos.BOTTOM_LEFT);
        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            }
        });
    }

    boolean isShowing() {
        return host.getChildren().contains(card);
    }

    void toggle() {
        if (isShowing()) hide();
        else show();
    }

    void show() {
        if (isShowing() || shown == null) return;
        StackPane.setMargin(card, new Insets(0, 0, anchor.getLayoutBounds().getHeight() + 6, 10));
        host.getChildren().addAll(backdrop, card);
        card.requestFocus();
    }

    void hide() {
        if (!isShowing()) return;
        host.getChildren().removeAll(backdrop, card);
        onHidden.run();
    }

    /** FX thread, every poll: what the card says, whether or not it shows. A finished check frees its button. */
    void update(LeakView view, boolean checking) {
        shown = view;
        sentence.setText(view.sentence());
        sentence.getStyleClass().removeIf(c -> c.startsWith("leak-") && !c.equals("leak-sentence"));
        sentence.getStyleClass().add(view.styleClass());
        rows.getChildren().clear();
        for (int i = 0; i < view.rows().size(); i++) {
            Label label = new Label(view.rows().get(i).label());
            label.getStyleClass().add("message-log-time");
            Label value = new Label(view.rows().get(i).value());
            value.getStyleClass().add("leak-value");
            if (view.rows().get(i).address()) value.getStyleClass().add("leak-address");
            rows.addRow(i, label, value);
        }
        if (!checking) {
            checkNow.setText(tr("leak.checkNow"));
            checkNow.setDisable(false);
        }
    }

    /** The card's words, for the capture hook. */
    String textForCapture() {
        if (shown == null) return "";
        StringBuilder out = new StringBuilder(shown.sentence());
        for (LeakView.Row row : shown.rows())
            out.append(" | ").append(row.label()).append(": ").append(row.value());
        return out.toString();
    }
}
