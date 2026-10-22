package com.torrenta.ui;

import java.util.Locale;
import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import static com.torrenta.i18n.Messages.tr;

/**
 * The one question asked before something is removed: the torrent list, the RSS tab and every
 * list in Settings ask it the same way, with a Remove button and Cancel, so nothing goes on a
 * single click anywhere.
 */
final class RemoveConfirm {

    /** What is being removed; each has a header and a question naming it. */
    enum Kind {
        FEED,
        RULE,
        WATCH_FOLDER,
        SEARCH_PROVIDER,
        ENGINE_PROFILE,
        CATEGORY,
        TAG;

        /** The catalogue segment: {@code confirm.remove.<key>}. */
        String key() {
            String[] words = name().toLowerCase(Locale.ROOT).split("_");
            StringBuilder out = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++)
                out.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
            return out.toString();
        }
    }

    private RemoveConfirm() {}

    /** "Remove feed", the dialog's header. */
    static String header(Kind kind) {
        return tr("confirm.remove." + kind.key() + ".header");
    }

    /** "Remove the feed “Linux ISOs” and its articles? …", worded by what the removal does. */
    static String question(Kind kind, String name) {
        return tr("confirm.remove." + kind.key(), name);
    }

    /** FX thread. Asks before removing {@code name}; true when Remove was pressed. */
    static boolean ask(Window owner, Kind kind, String name) {
        return ask(owner, header(kind), question(kind, name), tr("common.remove"));
    }

    /** FX thread. The question itself, for a removal worded its own way; true on {@code button}. */
    static boolean ask(Window owner, String header, String text, String button) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, text);
        confirm.setTitle(header);
        confirm.setHeaderText(header);
        if (owner != null) confirm.initOwner(owner);
        Fonts.installUiFont(confirm.getDialogPane());
        ButtonType yes = new ButtonType(button, ButtonType.OK.getButtonData());
        confirm.getButtonTypes().setAll(yes, StockButtons.cancel());
        Optional<ButtonType> answer = confirm.showAndWait();
        return answer.isPresent() && answer.get() == yes;
    }
}
