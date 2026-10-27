package com.torrenta.ui;

import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the capture hook drives the Settings window with, and the small pieces its pages share:
 * a spinner that keeps a number typed before the focus left, a removal answered without a dialog.
 */
@ExtendWith(FxToolkit.class)
class SettingsCaptureFxTest {

    @Test
    void theLayoutRowsAreChosenByIdAndWriteThrough(@TempDir Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        Fx.run(() -> f.window.chooseLayoutForCapture("right", "bottom"));
        assertEquals("right", f.settings.sidebarSide());
        assertEquals("bottom", f.settings.detailSide());
        Fx.run(() -> f.window.chooseLayoutForCapture("hidden", "hidden"));
        assertFalse(f.settings.sidebar());
        assertFalse(f.settings.detailVisible());
        assertTrue(f.told.contains("appearance"));
        Fx.run(f.window::hideForCapture);
        assertFalse(Fx.call(f.window::isShowing));
    }

    @Test
    void theWatchDialogOpensFromTheCaptureAndCloses(@TempDir Path dir) throws Exception {
        SettingsFixture f = SettingsFixture.open(dir);
        Fx.run(f.window::openWatchDialogForCapture);
        Modals.await(tr("dialog.watchFolder.title.add"));
        assertEquals(tr("dialog.watchFolder.title.add"), Fx.call(f.window::dialogTitle));
        assertTrue(Fx.call(() -> f.window.dialogScene() != null));
        Fx.run(f.window::closeDialogsForCapture);
        Modals.gone(tr("dialog.watchFolder.title.add"));
        assertEquals("", Fx.call(f.window::dialogTitle));
        assertNull(Fx.call(f.window::dialogScene));
        assertTrue(f.watch.entries().isEmpty());
    }

    @Test
    void aNumberTypedIntoASpinnerIsKeptWhenTheFocusLeaves() {
        Spinner<Integer> spinner = Fx.call(() -> Spinners.commitOnBlur(new Spinner<>(0, 100, 5)));
        TextField other = Fx.call(TextField::new);
        Fx.run(() -> {
            spinner.setEditable(true);
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(spinner, other)));
            stage.show();
            spinner.requestFocus();
        });
        Fx.pulse();
        Fx.run(() -> spinner.getEditor().setText("42"));
        Fx.run(other::requestFocus);
        Fx.pulse();
        assertEquals(42, Fx.call(spinner::getValue));

        Fx.run(spinner::requestFocus);
        Fx.pulse();
        Fx.run(() -> spinner.getEditor().setText("forty"));
        Fx.run(other::requestFocus);
        Fx.pulse();
        assertEquals(42, Fx.call(spinner::getValue), "text that is not a number puts the value back");
        assertEquals("42", Fx.call(() -> spinner.getEditor().getText()));
    }

    @Test
    void aCaptureAnswersTheNextRemovalOnceWithoutAQuestion() {
        Stage owner = Fx.call(Stage::new);
        RemoveConfirm.answerForCapture = true;
        assertTrue(Fx.call(() -> RemoveConfirm.ask(owner, RemoveConfirm.Kind.TAG, "keep")));
        assertNull(RemoveConfirm.answerForCapture, "used once");
        RemoveConfirm.answerForCapture = false;
        assertFalse(Fx.call(() -> RemoveConfirm.ask(owner, RemoveConfirm.Kind.TAG, "keep")));
    }
}
