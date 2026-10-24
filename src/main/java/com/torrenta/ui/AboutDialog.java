package com.torrenta.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Window;

import com.torrenta.AppInfo;
import com.torrenta.update.ReleaseInfo;

import static com.torrenta.i18n.Messages.tr;

/**
 * The About panel, Editora's: icon beside name, version and tagline as one block, the copyright,
 * a column of links (licence, homepage, releases, the settings file), Termina's update block (up to
 * date or what is newer, and as of when), a monospace environment block, and "Copy details" that
 * keeps the panel open.
 */
public final class AboutDialog {

    /** The update block's key in the dialog pane's properties, so a check's answer can redraw it. */
    private static final String UPDATE_BLOCK = "torrenta.about.update";

    /** Where the panel was last centred, so a resize re-centres it only if nobody moved it since. */
    private static final String CENTRED_AT = "torrenta.about.centredAt";

    private AboutDialog() {}

    /**
     * What the window knows about updates: a check running now, the newer release (null when none
     * or skipped), the last check's failure this session (null when it answered), and when a check
     * last started (epoch ms, 0 for never).
     */
    public record UpdateStatus(boolean checking, ReleaseInfo update, String error, long lastCheck) {}

    /** Which sentence the update block leads with. */
    enum UpdateState {
        CHECKING,
        AVAILABLE,
        FAILED,
        UP_TO_DATE,
        NOT_CHECKED
    }

    /**
     * The rule: a running check first, then a newer release, then a failure; "up to date" only
     * once some check has run, so a fresh install with the check off claims nothing.
     */
    static UpdateState state(UpdateStatus status) {
        if (status.checking()) return UpdateState.CHECKING;
        if (status.update() != null) return UpdateState.AVAILABLE;
        if (status.error() != null) return UpdateState.FAILED;
        if (status.lastCheck() <= 0) return UpdateState.NOT_CHECKED;
        return UpdateState.UP_TO_DATE;
    }

    /**
     * The top-left corner that puts a {@code width} × {@code height} panel over the middle of
     * {@code owner}, kept inside {@code screen} so a window half off the edge does not take the
     * panel with it.
     */
    static Point2D centred(Rectangle2D owner, double width, double height, Rectangle2D screen) {
        double x = owner.getMinX() + (owner.getWidth() - width) / 2;
        double y = owner.getMinY() + (owner.getHeight() - height) / 2;
        x = Math.max(screen.getMinX(), Math.min(x, screen.getMaxX() - width));
        y = Math.max(screen.getMinY(), Math.min(y, screen.getMaxY() - height));
        return new Point2D(x, y);
    }

    /** The last check as a local date and time, or null when there was none. */
    static String lastChecked(long epochMs, ZoneId zone, Locale locale) {
        if (epochMs <= 0) return null;
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    /** The line under the name; read at call time, as the language is chosen after this class loads. */
    public static String tagline() {
        return tr("dialog.about.tagline");
    }

    /**
     * Builds the panel; the caller shows it. Non-modal and shown without waiting, so the capture
     * hook can reach it and quitting while it is open never exits from inside a nested event loop.
     */
    public static Alert create(
            Window owner, Path settingsFile, Consumer<String> openUrl, UpdateStatus updateStatus, Runnable checkNow) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle(tr("dialog.about.title", AppInfo.NAME));
        // The whole dialog is the content: no header band or graphic, so the icon, name, version
        // and tagline read as one block the way a native About panel does.
        alert.setHeaderText(null);
        alert.setGraphic(null);
        alert.getDialogPane().getStyleClass().add("about-dialog");
        alert.getDialogPane()
                .getStylesheets()
                .add(AboutDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(alert.getDialogPane());

        Label name = new Label(AppInfo.NAME);
        name.getStyleClass().add("about-name");
        Label version = new Label(tr("dialog.about.version", AppInfo.VERSION));
        version.getStyleClass().add("about-version");
        Label tagline = new Label(tagline());
        tagline.getStyleClass().add("about-tagline");
        tagline.setWrapText(true);
        VBox titleBox = new VBox(2, name, version, tagline);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(16, titleBox);
        var icon = AboutDialog.class.getResourceAsStream("/com/torrenta/icons/icon-128.png");
        if (icon != null) {
            ImageView logo = new ImageView(new Image(icon));
            logo.setFitWidth(72);
            logo.setFitHeight(72);
            logo.setPreserveRatio(true);
            header.getChildren().add(0, logo);
        }
        header.setAlignment(Pos.TOP_LEFT);

        Label copyright = new Label(AppInfo.COPYRIGHT);
        copyright.getStyleClass().add("about-copyright");

        VBox links = new VBox(2);
        links.getChildren()
                .add(link(tr("dialog.about.license"), () -> openUrl.accept(AppInfo.HOMEPAGE + "/blob/master/LICENSE")));
        links.getChildren().add(link(tr("dialog.about.homepage"), () -> openUrl.accept(AppInfo.HOMEPAGE)));
        links.getChildren().add(link(tr("dialog.about.releases"), () -> openUrl.accept(AppInfo.RELEASES_PAGE)));
        Hyperlink settingsLink = link(tr("dialog.about.settingsFile", settingsFile.toString()), () -> {
            alert.close();
            openUrl.accept(settingsFile.getParent().toUri().toString());
        });
        settingsLink.setTooltip(new Tooltip(tr("dialog.about.settingsFile.tooltip")));
        links.getChildren().add(settingsLink);

        VBox updateBlock = new VBox(2);
        updateBlock.getStyleClass().add("about-update-block");
        alert.getDialogPane().getProperties().put(UPDATE_BLOCK, (Consumer<UpdateStatus>)
                status -> fillUpdateBlock(updateBlock, status, alert, openUrl, checkNow));
        fillUpdateBlock(updateBlock, updateStatus, alert, openUrl, checkNow);

        String details = environmentDetails(settingsFile);
        Label env = new Label(details);
        env.getStyleClass().add("about-env");

        Button copyDetails = new Button(tr("dialog.about.copyDetails"));
        copyDetails.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(details);
            Clipboard.getSystemClipboard().setContent(content);
            copyDetails.setText(tr("dialog.about.copied"));
        });
        HBox footer = new HBox(copyDetails);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(14, header, copyright, links, updateBlock, env, footer);
        content.getStyleClass().add("about-content");
        alert.getDialogPane().setContent(content);
        alert.getButtonTypes().setAll(new ButtonType(tr("common.close"), ButtonBar.ButtonData.OK_DONE));
        // Over the middle of the window, every time: the window manager's own placement put it
        // wherever it liked.
        // Over the middle of the window, every time, once the first layout has settled (the
        // panel's first size is an estimate the dialog corrects on the next pulse).
        alert.setOnShown(e -> Platform.runLater(() -> centreOnOwner(alert)));
        return alert;
    }

    /** Redraws an open panel's update block; a panel built before this check answered shows it. */
    @SuppressWarnings("unchecked")
    public static void showUpdate(Alert alert, UpdateStatus status) {
        if (alert.getDialogPane().getProperties().get(UPDATE_BLOCK) instanceof Consumer<?> fill)
            ((Consumer<UpdateStatus>) fill).accept(status);
        // The block grows from one line to three when a release turns up; the panel was sized
        // for the old one and would cut the environment block off at the bottom.
        if (alert.getDialogPane().getScene() == null) return;
        Window panel = alert.getDialogPane().getScene().getWindow();
        if (panel == null) return;
        // Re-centred only while it is still where it was put: a panel someone moved stays put.
        boolean unmoved = alert.getDialogPane().getProperties().get(CENTRED_AT) instanceof Point2D at
                && at.distance(panel.getX(), panel.getY()) < 1.5;
        panel.sizeToScene();
        if (unmoved) Platform.runLater(() -> centreOnOwner(alert));
    }

    private static void centreOnOwner(Alert alert) {
        Window owner = alert.getOwner();
        Window panel = alert.getDialogPane().getScene().getWindow();
        if (owner == null || !owner.isShowing() || panel == null) return;
        Rectangle2D bounds = new Rectangle2D(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
        Rectangle2D screen = Screen.getScreensForRectangle(bounds).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
        Point2D at = centred(bounds, panel.getWidth(), panel.getHeight(), screen);
        panel.setX(at.getX());
        panel.setY(at.getY());
        alert.getDialogPane().getProperties().put(CENTRED_AT, at);
    }

    private static void fillUpdateBlock(
            VBox block, UpdateStatus status, Alert alert, Consumer<String> openUrl, Runnable checkNow) {
        block.getChildren().clear();
        UpdateState state = state(status);
        Label headline = new Label(
                switch (state) {
                    case CHECKING -> tr("status.checkingUpdates");
                    case AVAILABLE ->
                        tr("dialog.about.updateHeadline", status.update().version());
                    case FAILED -> tr("status.updateFailed", status.error());
                    case UP_TO_DATE -> tr("dialog.about.upToDate");
                    case NOT_CHECKED -> tr("dialog.about.notChecked");
                });
        headline.setWrapText(true);
        headline.getStyleClass()
                .add(
                        switch (state) {
                            case AVAILABLE -> "about-update";
                            case UP_TO_DATE -> "about-uptodate";
                            default -> "about-update-muted";
                        });
        block.getChildren().add(headline);
        if (state == UpdateState.AVAILABLE) {
            ReleaseInfo update = status.update();
            String url = update.url() == null || update.url().isBlank() ? AppInfo.RELEASES_PAGE : update.url();
            block.getChildren().add(link(tr("dialog.about.openReleasePage"), () -> {
                alert.close();
                openUrl.accept(url);
            }));
        }
        // As of when, so "up to date" reads as a claim with a date on it.
        String checked =
                lastChecked(status.lastCheck(), ZoneId.systemDefault(), Locale.getDefault(Locale.Category.FORMAT));
        HBox when = new HBox(6);
        when.setAlignment(Pos.BASELINE_LEFT);
        if (checked != null) {
            Label at = new Label(tr("dialog.about.lastChecked", checked));
            at.getStyleClass().add("about-update-muted");
            when.getChildren().add(at);
        }
        if (state != UpdateState.CHECKING) when.getChildren().add(link(tr("dialog.about.checkNow"), checkNow));
        if (!when.getChildren().isEmpty()) block.getChildren().add(when);
    }

    /** The monospace environment block, also what "Copy details" puts on the clipboard. */
    static String environmentDetails(Path settingsFile) {
        StringBuilder sb = new StringBuilder();
        sb.append(AppInfo.NAME).append(' ').append(AppInfo.VERSION);
        if (!AppInfo.BUILD_TIME.isBlank())
            sb.append(" (built ").append(AppInfo.BUILD_TIME).append(')');
        sb.append('\n');
        sb.append("Engine: libtorrent4j ").append(AppInfo.ENGINE_VERSION).append('\n');
        sb.append("Java: ")
                .append(System.getProperty("java.version", "?"))
                .append(" (")
                .append(System.getProperty("java.vendor", "?"))
                .append(")\n");
        sb.append("JavaFX: ")
                .append(System.getProperty("javafx.runtime.version", "?"))
                .append('\n');
        sb.append("OS: ")
                .append(System.getProperty("os.name", "?"))
                .append(' ')
                .append(System.getProperty("os.version", "?"))
                .append(" (")
                .append(System.getProperty("os.arch", "?"))
                .append(")\n");
        if (settingsFile != null && settingsFile.getParent() != null) {
            sb.append("Config: ").append(settingsFile.getParent());
        }
        return sb.toString();
    }

    /** A borderless link row. */
    private static Hyperlink link(String text, Runnable onClick) {
        Hyperlink link = new Hyperlink(text);
        link.setPadding(Insets.EMPTY);
        link.setOnAction(e -> onClick.run());
        return link;
    }
}
