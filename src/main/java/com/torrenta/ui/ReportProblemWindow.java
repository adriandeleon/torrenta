package com.torrenta.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
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
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.torrenta.AppInfo;
import com.torrenta.report.ProblemReport;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * Help → Report a Problem ({@code report-problem}): the redacted problem report in an editable
 * area, for the user to read and trim, then Open GitHub Issue — which copies it and opens a new
 * issue with it filled in (or, when it is too long for a link, says to paste it). Copy and Save…
 * are there for any other channel. Nothing is sent from here: the user submits the issue on
 * GitHub. Editora's.
 */
final class ReportProblemWindow {

    /** Shown in the issue's report field when the report did not fit in the link (GitHub's form is English). */
    static final String PASTE_HINT = "Paste the report here: " + AppInfo.NAME
            + " copied it to your clipboard. Or drag a saved copy into this box.";

    private final Supplier<ProblemReport.Assembled> source;
    private final Consumer<String> openUrl;

    private final Stage stage = new Stage();
    private final TextArea text = new TextArea();
    private final Label summary = new Label();
    private final Label status = new Label();
    private final Button openIssue = new Button(tr("report.openIssue"));

    private String systemDetails = "";
    private ProblemReport.@Nullable Assembled prepared;
    /** Bumped by each show(): a report still being prepared for an earlier one is dropped. */
    private int generation;

    /**
     * @param source builds the report; called off the FX thread (it reads and redacts two log files)
     * @param openUrl opens the new-issue link in the browser
     */
    ReportProblemWindow(Window owner, Supplier<ProblemReport.Assembled> source, Consumer<String> openUrl) {
        this.source = source;
        this.openUrl = openUrl;

        Label intro = new Label(tr("report.intro", AppInfo.NAME));
        intro.setWrapText(true);
        summary.getStyleClass().add("debug-log-file");
        summary.setWrapText(true);

        text.setWrapText(false);
        text.getStyleClass().add("debug-log-text");
        VBox.setVgrow(text, Priority.ALWAYS);

        status.getStyleClass().add("debug-log-file");
        status.setWrapText(true);

        openIssue.setDefaultButton(true);
        openIssue.setOnAction(e -> openIssue());
        Button copy = new Button(tr("common.copy"));
        copy.setOnAction(e -> {
            copyToClipboard(text.getText());
            status.setText(tr("report.copied"));
        });
        Button save = new Button(tr("report.save"));
        save.setOnAction(e -> save());
        Button close = new Button(tr("common.close"));
        close.setCancelButton(true);
        close.setOnAction(e -> stage.hide());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, openIssue, copy, save, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, intro, summary, text, status, buttons);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 820, 620);
        scene.getStylesheets()
                .add(ReportProblemWindow.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
        });

        stage.setTitle(tr("report.title", AppInfo.NAME));
        stage.setScene(scene);
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
    }

    /** Shows with the report built afresh (the logs have grown since), or brings it to the front. */
    void show() {
        prepare();
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

    /** What the capture prints: values replaced, whether the link carries it, its length and first lines. */
    String summaryForCapture() {
        ProblemReport.Assembled report = prepared;
        if (report == null) return "preparing";
        String body = text.getText();
        String url = ProblemReport.issueUrl(AppInfo.GITHUB_REPO, systemDetails, body, PASTE_HINT);
        return "redacted=" + report.redacted()
                + " inline=" + ProblemReport.fitsInIssueUrl(AppInfo.GITHUB_REPO, systemDetails, body)
                + " url-length=" + url.length()
                + " chars=" + body.length();
    }

    /** The report as it now reads, for the capture. */
    String textForCapture() {
        return text.getText();
    }

    private void prepare() {
        int mine = ++generation;
        prepared = null;
        text.setEditable(false);
        text.setText(tr("report.preparing"));
        summary.setText("");
        status.setText("");
        openIssue.setDisable(true);
        Thread.ofVirtual().name("problem-report").start(() -> {
            ProblemReport.Assembled report;
            try {
                report = source.get();
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (mine == generation) text.setText(tr("report.failed", String.valueOf(e.getMessage())));
                });
                return;
            }
            Platform.runLater(() -> {
                if (mine != generation) return;
                prepared = report;
                systemDetails = report.systemDetails();
                text.setText(report.text());
                text.setEditable(true);
                text.positionCaret(0);
                text.setScrollTop(0);
                summary.setText(
                        report.redacted() == 0 ? tr("report.redactedNone") : tr("report.redacted", report.redacted()));
                openIssue.setDisable(false);
            });
        });
    }

    /** Copies the report as it now reads (the user may have trimmed it) and opens the new-issue page. */
    private void openIssue() {
        String report = text.getText();
        copyToClipboard(report);
        openUrl.accept(ProblemReport.issueUrl(AppInfo.GITHUB_REPO, systemDetails, report, PASTE_HINT));
        status.setText(
                ProblemReport.fitsInIssueUrl(AppInfo.GITHUB_REPO, systemDetails, report)
                        ? tr("report.openedInline")
                        : tr("report.openedPaste"));
    }

    private void save() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("report.save"));
        chooser.setInitialFileName(ProblemReport.fileName(LocalDateTime.now()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("report.textFiles"), "*.txt"));
        File target = chooser.showSaveDialog(stage);
        if (target == null) return;
        try {
            writeAtomically(target.toPath(), text.getText());
            status.setText(tr("report.saved", target.toPath()));
        } catch (IOException e) {
            status.setText(tr("report.saveFailed", String.valueOf(e.getMessage())));
        }
    }

    /** Through a temporary file beside it, so a failed write never leaves half a report under the name. */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(dir, ".torrenta-report", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void copyToClipboard(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
