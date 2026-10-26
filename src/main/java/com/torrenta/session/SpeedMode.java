package com.torrenta.session;

import java.time.LocalDateTime;
import java.util.Locale;

import com.torrenta.engine.EngineSettings;
import com.torrenta.os.Conditions;

import static com.torrenta.i18n.Messages.tr;

/**
 * Which speed limits are in force, and whether the torrents are held paused, as rules. Pure.
 *
 * <p>The alternative pair is one switch, {@code speed.altOn}, as qBittorrent has it: a person
 * flips it (the status bar's pill, the menu, Settings, the web API), and so does the automatic
 * decision, but only when that decision changes: the schedule starting or ending, the machine
 * going on battery or off it, the network becoming metered or not. So a person's flip wins until
 * the next such change, and a change made while the app was closed is caught up at the next
 * start, since the decision last applied is kept ({@code speed.autoWas}).
 *
 * <p>A condition's rule can ask for more than the alternative pair: to pause. That is the stronger
 * choice and is not the switch's: {@link ConditionPause} holds the running torrents for as long as
 * a condition asks it.
 */
public final class SpeedMode {

    private SpeedMode() {}

    /** What a condition (on battery, on a metered network) does while it holds. */
    public enum Rule {
        /** Nothing. */
        NONE,
        /** The alternative speed limits. */
        ALTERNATIVE,
        /** Every running torrent paused, resumed when it ends. */
        PAUSE;

        public String label() {
            return tr("speedRule." + name().toLowerCase(Locale.ROOT));
        }

        /** The label, so a ChoiceBox shows it; read at call time, never cached. */
        @Override
        public String toString() {
            return label();
        }

        /** Its name in the settings file; unknown text is {@link #NONE}. */
        public static Rule byName(String name) {
            for (Rule r : values()) if (r.name().equalsIgnoreCase(name == null ? "" : name.trim())) return r;
            return NONE;
        }
    }

    /** Why the alternative pair is in force, for the pill: first of these that holds. */
    public enum Reason {
        /** Not in force. */
        NONE,
        /** A person turned it on. */
        MANUAL,
        ON_BATTERY,
        METERED,
        SCHEDULE;

        /** The reason in a word or two: "on battery", "scheduled", "on" for a person's, "off" for none. */
        public String pill() {
            return tr("speedReason." + name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * What the schedule and the conditions ask for now.
     *
     * @param alternative the alternative pair
     * @param reason why, or {@link Reason#NONE}
     * @param pause the torrents held paused
     * @param pauseReason why they are, or {@link Reason#NONE}
     */
    public record Auto(boolean alternative, Reason reason, boolean pause, Reason pauseReason) {
        public static final Auto NOTHING = new Auto(false, Reason.NONE, false, Reason.NONE);

        /** What {@code speed.autoWas} keeps: the pair's decision and its reason, never the pause. */
        public String key() {
            return alternative ? reason.name().toLowerCase(Locale.ROOT) : "none";
        }
    }

    public static Auto auto(
            EngineSettings.Speed speed, LocalDateTime now, Conditions conditions, Rule onBattery, Rule onMetered) {
        boolean battery = conditions.onBattery();
        boolean metered = conditions.metered();
        Reason alt = Reason.NONE;
        if (battery && onBattery == Rule.ALTERNATIVE) alt = Reason.ON_BATTERY;
        else if (metered && onMetered == Rule.ALTERNATIVE) alt = Reason.METERED;
        else if (speed.scheduleActive(now)) alt = Reason.SCHEDULE;
        Reason pause = Reason.NONE;
        if (battery && onBattery == Rule.PAUSE) pause = Reason.ON_BATTERY;
        else if (metered && onMetered == Rule.PAUSE) pause = Reason.METERED;
        return new Auto(alt != Reason.NONE, alt, pause != Reason.NONE, pause);
    }

    /**
     * The switch after this look: flipped to the automatic decision when that decision differs
     * from the one last applied ({@code autoWas}), else left as a person last set it. Nothing
     * applied yet (a first start) counts as "none", so a schedule already running is applied.
     *
     * @param changed the switch was set by this step: say so in the log
     */
    public record Step(boolean altOn, String autoWas, boolean changed) {}

    public static Step next(boolean altOn, String autoWas, Auto now) {
        String was = autoWas == null || autoWas.isBlank() ? "none" : autoWas.trim();
        if (now.key().equals(was)) return new Step(altOn, was, false);
        return new Step(now.alternative(), now.key(), now.alternative() != altOn);
    }

    /** Why the switch is on, for the pill: the decision's reason while they agree, else a person's. */
    public static Reason shown(boolean altOn, Auto auto) {
        if (!altOn) return Reason.NONE;
        return auto.alternative() ? auto.reason() : Reason.MANUAL;
    }
}
