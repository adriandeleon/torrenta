package com.torrenta.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.stage.Window;

import com.torrenta.AppInfo;
import com.torrenta.update.ReleaseInfo;

import static com.torrenta.i18n.Messages.tr;

/**
 * The About panel, Editora's: icon beside name, version and tagline as one block, the copyright,
 * a column of links (licence, homepage, releases, the settings file, and the update when one is
 * known), a monospace environment block, and "Copy details" that keeps the panel open.
 */
public final class AboutDialog {

    private AboutDialog() {}

    /** The line under the name; read at call time, as the language is chosen after this class loads. */
    public static String tagline() {
        return tr("dialog.about.tagline");
    }

    /**
     * Builds the panel; the caller shows it. Non-modal and shown without waiting, so the capture
     * hook can reach it and quitting while it is open never exits from inside a nested event loop.
     */
    public static Alert create(Window owner, Path settingsFile, Consumer<String> openUrl, ReleaseInfo update) {
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
        if (update != null) {
            String url = update.url() == null || update.url().isBlank() ? AppInfo.RELEASES_PAGE : update.url();
            Hyperlink updateLink = link(tr("dialog.about.updateAvailable", update.version()), () -> {
                alert.close();
                openUrl.accept(url);
            });
            updateLink.getStyleClass().add("about-update");
            links.getChildren().add(updateLink);
        }

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

        VBox content = new VBox(14, header, copyright, links, env, footer);
        content.getStyleClass().add("about-content");
        alert.getDialogPane().setContent(content);
        alert.getButtonTypes().setAll(new ButtonType(tr("common.close"), ButtonBar.ButtonData.OK_DONE));
        return alert;
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
