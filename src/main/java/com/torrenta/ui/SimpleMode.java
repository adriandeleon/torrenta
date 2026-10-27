package com.torrenta.ui;

import java.util.List;
import java.util.Set;

import com.torrenta.store.Settings;

/**
 * Simple mode, Editora's Simple UI mode for a torrent client: one switch that leaves the list, the
 * ways a torrent arrives and the few actions a person new to it needs, and hides the rest. The pure
 * rule, so the menus, the toolbar, the palette, the chords and the panes cannot disagree about it.
 *
 * <p>It hides, it never changes a saved preference: the sidebar keeps its place and its choice,
 * the detail pane its tab, the Search and RSS views their state, and turning the mode off brings
 * every one of them back as it was. What runs on its own (RSS rules, watch folders, automation)
 * keeps running; the mode is about what the window offers, not about what the engine does.
 *
 * <p>The saved switch is {@code ui.simple}; {@code --simple} turns it on for one session without
 * touching it, as Editora's flag does.
 */
final class SimpleMode {

    /**
     * The commands the mode keeps: in the menus, on the toolbar, in the palette and on their
     * chords. Every other command is hidden and dead, so a command added later is out of the mode
     * until it is named here, which is the safer way round. The detail pane's buttons and the
     * diagnosis card's remedies run commands by id, so each of theirs is here too (Force Resume,
     * Reannounce, Choose Files, Open folder, Remove); the switch itself is, or the mode would be a
     * one-way door.
     */
    static final Set<String> COMMANDS = Set.of(
            // File
            "add-file",
            "add-magnet",
            "add-clipboard",
            "settings",
            "quit",
            // Torrent
            "undo",
            "pause-resume",
            "force-resume",
            "reannounce",
            "choose-files",
            "rename",
            "copy-name",
            "copy-magnet",
            "open-folder",
            "remove",
            "remove-data",
            // View
            "toggle-toolbar",
            "toggle-detail",
            "toggle-simple",
            "alt-speed",
            "filter",
            "select-all",
            "message-log",
            // Help
            "check-updates",
            "report-problem",
            "about",
            // On no menu: the way back when the engine did not start.
            "retry-engine");

    /**
     * The toolbar's buttons the mode keeps, fewer than its commands: Force Resume is there for the
     * diagnosis card to offer, not a button to reach for. The palette's own button stays too.
     */
    static final Set<String> TOOLBAR =
            Set.of("add-file", "add-magnet", "pause-resume", "remove", "filter", "settings", "toggle-simple");

    /** The detail pane's tabs the mode keeps: what is happening, and which files. */
    static final List<String> DETAIL_TABS = List.of("overview", "files");

    /**
     * The list's columns in the mode, until the person moves them: the name (comfortable rows
     * carry the progress bar and the status sentence inside it), the size, the two rates and the
     * time left. Every other column is hidden, not dropped, so the table's column menu still has
     * it.
     */
    static final String COLUMNS = "name:520,size:80,down:96,up:96,eta:80,-progress,-state,-done,-seeds,-peers,"
            + "-ratio,-added,-completed,-tracker,-category,-tags,-path,-queue";

    private SimpleMode() {}

    /** Whether the mode is on: the saved switch, or {@code --simple} for this session. */
    static boolean active(boolean saved, boolean commandLine) {
        return saved || commandLine;
    }

    /**
     * The saved switch after a toggle from inside the window: the opposite of what showed. A
     * session started with {@code --simple} and the switch off is turned off by one toggle, not
     * by two (Editora flipped the saved value, which was already off, and nothing changed).
     */
    static boolean toggled(boolean active) {
        return !active;
    }

    /** Whether a command is offered: always outside the mode, only the kept ones in it. */
    static boolean keeps(String id, boolean simple) {
        return !simple || COMMANDS.contains(id);
    }

    /** Whether a command's toolbar button shows: always outside the mode, the few in it. */
    static boolean onToolbar(String id, boolean simple) {
        return !simple || TOOLBAR.contains(id);
    }

    /** The commands to offer, in the given order. */
    static List<Command> commands(List<Command> all, boolean simple) {
        return all.stream().filter(c -> keeps(c.id(), simple)).toList();
    }

    /**
     * The rows the list draws: comfortable in the mode, whose name cell says what compact rows
     * need two columns for; the saved density outside it. The saved density is left as it is.
     */
    static Density density(Density saved, boolean simple) {
        return simple ? Density.COMFORTABLE : saved;
    }

    /**
     * The settings key the list's columns are remembered under: the mode's own, else the shown
     * density's, so a column moved in one never moves in the others.
     */
    static String columnsKey(Density shown, boolean simple) {
        if (simple) return Settings.TABLE_COLUMNS_SIMPLE;
        return shown == Density.COMFORTABLE ? Settings.TABLE_COLUMNS_COMFORTABLE : Settings.TABLE_COLUMNS;
    }

    /** Whether a view can show: the transfers always, Search and RSS outside the mode. */
    static boolean viewShown(View view, boolean simple) {
        return !simple || view == View.TRANSFERS;
    }

    /** The detail pane's tabs to show, in {@code all}'s order. */
    static List<String> detailTabs(List<String> all, boolean simple) {
        return simple ? all.stream().filter(DETAIL_TABS::contains).toList() : all;
    }

    /**
     * The sidebar, the view strip, the port and the DHT in the status bar: the chrome the mode
     * takes away. The sidebar's filter is not applied while it is away, so nothing narrows the list
     * that the person cannot see.
     */
    static boolean chrome(boolean simple) {
        return !simple;
    }
}
