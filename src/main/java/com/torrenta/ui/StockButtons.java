package com.torrenta.ui;

import java.util.Optional;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import static com.torrenta.i18n.Messages.tr;

/**
 * OK and Cancel in the catalogue's words.
 *
 * <p>JavaFX labels its stock {@link ButtonType#OK} and {@link ButtonType#CANCEL} from its own
 * resources, in the JVM's default locale and only for the languages it ships; that is not the
 * language chosen in Settings, so a prompt under German menus could still say "Cancel". The
 * replacements keep the stock roles ({@code OK_DONE}, {@code CANCEL_CLOSE}), which is what the
 * stock dialogs' result converters, the default button and Escape go by.
 */
final class StockButtons {

    private StockButtons() {}

    static ButtonType ok() {
        return new ButtonType(tr("common.ok"), ButtonBar.ButtonData.OK_DONE);
    }

    static ButtonType cancel() {
        return new ButtonType(tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    /** Swaps the dialog's stock OK and Cancel for translated ones, in place; any other button stays. */
    static void translate(Dialog<?> dialog) {
        dialog.getDialogPane()
                .getButtonTypes()
                .replaceAll(b -> b == ButtonType.OK ? ok() : b == ButtonType.CANCEL ? cancel() : b);
    }

    /** Whether the answer is the dialog's OK, whichever object that button is. */
    static boolean confirmed(Optional<ButtonType> answer) {
        return answer.map(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .orElse(false);
    }
}
