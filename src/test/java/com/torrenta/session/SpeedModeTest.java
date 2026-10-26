package com.torrenta.session;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.os.Conditions;
import com.torrenta.session.SpeedMode.Reason;
import com.torrenta.session.SpeedMode.Rule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedModeTest {

    /** Monday 2026-10-19 at 12:00 and 23:00; the schedule 22:00–08:00 every day. */
    static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 19, 12, 0);

    static final LocalDateTime NIGHT = LocalDateTime.of(2026, 10, 19, 23, 0);

    static EngineSettings.Speed speed(boolean scheduleOn) {
        return new EngineSettings.Speed(
                1000, 100, 200, 20, false, scheduleOn, 22 * 60, 8 * 60, EnumSet.allOf(DayOfWeek.class), false);
    }

    static final Conditions MAINS = Conditions.NORMAL;
    static final Conditions BATTERY = new Conditions(true, false);
    static final Conditions BOTH = new Conditions(true, true);

    @Test
    void theAutomaticDecisionNamesItsReasonFirstConditionThenSchedule() {
        SpeedMode.Auto quiet = SpeedMode.auto(speed(true), NOON, MAINS, Rule.ALTERNATIVE, Rule.ALTERNATIVE);
        assertEquals(SpeedMode.Auto.NOTHING, quiet);
        assertEquals(
                "schedule",
                SpeedMode.auto(speed(true), NIGHT, MAINS, Rule.NONE, Rule.NONE).key());
        SpeedMode.Auto battery = SpeedMode.auto(speed(true), NIGHT, BOTH, Rule.ALTERNATIVE, Rule.ALTERNATIVE);
        assertEquals(Reason.ON_BATTERY, battery.reason(), "the battery says why before the schedule");
        assertFalse(battery.pause());
        assertEquals(
                Reason.METERED,
                SpeedMode.auto(speed(false), NOON, BOTH, Rule.NONE, Rule.ALTERNATIVE)
                        .reason());
        assertEquals(
                SpeedMode.Auto.NOTHING,
                SpeedMode.auto(speed(false), NOON, BATTERY, Rule.NONE, Rule.ALTERNATIVE),
                "a condition with no rule is nothing");
        SpeedMode.Auto pause = SpeedMode.auto(speed(true), NIGHT, BOTH, Rule.PAUSE, Rule.ALTERNATIVE);
        assertTrue(pause.pause());
        assertEquals(Reason.ON_BATTERY, pause.pauseReason());
        assertEquals(Reason.METERED, pause.reason(), "the pause and the pair are decided apart");
    }

    /** qBittorrent's override: a person's flip holds until the decision changes, then the decision wins. */
    @Test
    void aPersonsFlipHoldsUntilTheDecisionChanges() {
        SpeedMode.Auto night = SpeedMode.auto(speed(true), NIGHT, MAINS, Rule.NONE, Rule.NONE);
        SpeedMode.Auto day = SpeedMode.auto(speed(true), NOON, MAINS, Rule.NONE, Rule.NONE);

        SpeedMode.Step start = SpeedMode.next(false, "none", night);
        assertEquals(new SpeedMode.Step(true, "schedule", true), start, "the schedule starts: on");
        // The person turns it off at 23:00; the next look, still in the window, leaves it off.
        SpeedMode.Step held = SpeedMode.next(false, start.autoWas(), night);
        assertEquals(new SpeedMode.Step(false, "schedule", false), held);
        assertEquals(Reason.NONE, SpeedMode.shown(false, night));
        // The window ends: the decision changes, and it is applied: off (already), the override gone.
        SpeedMode.Step morning = SpeedMode.next(false, held.autoWas(), day);
        assertEquals(new SpeedMode.Step(false, "none", false), morning);
        // The next night it starts again, and so does the pair: yesterday's flip is not remembered.
        assertTrue(SpeedMode.next(false, morning.autoWas(), night).altOn());

        // On by hand in the day: a person's, until the schedule starts (and keeps it on), or ends.
        assertEquals(Reason.MANUAL, SpeedMode.shown(true, day));
        assertEquals(new SpeedMode.Step(true, "none", false), SpeedMode.next(true, "none", day));
        assertEquals(Reason.SCHEDULE, SpeedMode.shown(true, night), "both agree: the schedule's");
    }

    @Test
    void nothingAppliedYetIsNoneSoARunningScheduleIsApplied() {
        SpeedMode.Auto night = SpeedMode.auto(speed(true), NIGHT, MAINS, Rule.NONE, Rule.NONE);
        assertEquals(new SpeedMode.Step(true, "schedule", true), SpeedMode.next(false, "", night));
        assertEquals(new SpeedMode.Step(true, "schedule", true), SpeedMode.next(false, null, night));
        SpeedMode.Auto day = SpeedMode.auto(speed(true), NOON, MAINS, Rule.NONE, Rule.NONE);
        assertEquals(
                new SpeedMode.Step(true, "none", false),
                SpeedMode.next(true, "", day),
                "an upgrade keeps a switch turned on by hand");
    }

    @Test
    void unpluggingIsAChangePluggingInIsAnother() {
        SpeedMode.Auto unplugged = SpeedMode.auto(speed(false), NOON, BATTERY, Rule.ALTERNATIVE, Rule.NONE);
        SpeedMode.Auto plugged = SpeedMode.auto(speed(false), NOON, MAINS, Rule.ALTERNATIVE, Rule.NONE);
        SpeedMode.Step off = SpeedMode.next(false, "none", unplugged);
        assertTrue(off.altOn() && off.changed());
        assertEquals(Reason.ON_BATTERY, SpeedMode.shown(true, unplugged));
        assertEquals(new SpeedMode.Step(false, "none", true), SpeedMode.next(true, off.autoWas(), plugged));
        assertEquals(Rule.PAUSE, Rule.byName(" pause "));
        assertEquals(Rule.NONE, Rule.byName("soon"));
    }

    private static Torrent torrent(String id, boolean paused, boolean direct) {
        return new Torrent(
                direct ? "url-" + "0".repeat(40) + id : id,
                id,
                1000,
                500,
                0.5,
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                500,
                0,
                0,
                "",
                "/data",
                paused,
                false,
                0,
                "",
                List.of(),
                "",
                List.of(),
                false,
                false,
                -1,
                false,
                false);
    }

    @Test
    void aConditionPausesWhatRunsOnceAndResumesExactlyThose() {
        List<Torrent> running =
                List.of(torrent("a", false, false), torrent("b", true, false), torrent("c", false, true));
        ConditionPause.Step start = ConditionPause.next(true, false, running, Set.of());
        assertEquals(List.of("a"), start.pause(), "not the one a person paused, not a link's download");
        assertEquals(Set.of("a"), start.held());
        // While it holds, a torrent a person resumes stays resumed.
        List<Torrent> later = List.of(torrent("a", true, false), torrent("b", false, false), torrent("c", false, true));
        assertTrue(ConditionPause.next(true, true, later, start.held()).nothing());
        // It ends: only the held one, still paused, comes back.
        ConditionPause.Step end = ConditionPause.next(false, true, later, start.held());
        assertEquals(List.of("a"), end.resume());
        assertEquals(Set.of(), end.held());
        assertTrue(
                ConditionPause.next(false, true, List.of(torrent("a", false, false)), Set.of("a"))
                        .resume()
                        .isEmpty(),
                "a held torrent a person resumed meanwhile is left as it is");
    }
}
