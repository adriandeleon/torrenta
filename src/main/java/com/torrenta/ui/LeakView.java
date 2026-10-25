package com.torrenta.ui;

import java.util.List;
import java.util.Locale;

import com.torrenta.net.ExitCheck;
import com.torrenta.net.InterfaceWatch;
import com.torrenta.net.LeakCheck;

import static com.torrenta.i18n.Messages.tr;

/**
 * The leak check as the status bar's shield pill and its card say it: the verdict's word and
 * colour, a sentence of why, and the three addresses it was decided from. Pure; nothing is shown
 * for an unbound session, which has no binding to check.
 *
 * @param verdict the leak check's
 * @param sentence why, in a sentence
 * @param rows the binding's exit, the default route's, and what peers see
 */
record LeakView(LeakCheck.Verdict verdict, String sentence, List<Row> rows) {

    /** One address of the card: what it is, what was found, and whether that is an address (set as one). */
    record Row(String label, String value, boolean address) {}

    static LeakView of(
            LeakCheck.Verdict verdict,
            InterfaceWatch.State bound,
            String swarmSees,
            ExitCheck.Exits exits,
            boolean checkOn) {
        String sentence =
                switch (verdict) {
                    case PROTECTED ->
                        tr("leak.say.protected", bound.label(), exits.bound().address());
                    case EXPOSED -> tr("leak.say.exposed", swarmSees, bound.label());
                    case HALTED -> tr("leak.say.halted", bound.label());
                    case UNKNOWN, UNBOUND -> checkOn ? tr("leak.say.unknown") : tr("leak.say.off", bound.label());
                };
        List<Row> rows = List.of(
                new Row(
                        tr("leak.row.bound", bound.label()),
                        value(exits.bound()),
                        exits.bound().known()),
                new Row(
                        tr("leak.row.around"),
                        value(exits.around()),
                        exits.around().known()),
                new Row(
                        tr("leak.row.swarm"),
                        swarmSees == null || swarmSees.isEmpty() ? tr("leak.value.notYet") : swarmSees,
                        swarmSees != null && !swarmSees.isEmpty()));
        return new LeakView(verdict, sentence, rows);
    }

    private static String value(LeakCheck.Exit exit) {
        if (exit.known()) return exit.address();
        if (!exit.problem().isEmpty()) return tr("leak.value.failed", exit.problem());
        return tr("leak.value.notAsked");
    }

    /** The pill's word. */
    String pill() {
        return switch (verdict) {
            case PROTECTED -> tr("leak.pill.protected");
            case EXPOSED -> tr("leak.pill.exposed");
            case HALTED -> tr("leak.pill.halted");
            case UNKNOWN, UNBOUND -> tr("leak.pill.unknown");
        };
    }

    /** The pill's and the card's colour, as a style class. */
    String styleClass() {
        return "leak-" + kind();
    }

    private String kind() {
        return verdict.name().toLowerCase(Locale.ROOT);
    }
}
