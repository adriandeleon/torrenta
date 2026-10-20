package com.torrenta.automation;

import com.torrenta.engine.EngineEvent;

/**
 * What runs on which event, as stored: one command line per event (empty for none) and a switch
 * per notification. Engine-neutral and pure; {@code Settings.automation()} builds it.
 */
public record AutomationSettings(
        String onAdded,
        String onFinished,
        String onSeedingDone,
        boolean notifyAdded,
        boolean notifyFinished,
        boolean notifySeedingDone,
        boolean notifyError) {

    /** Nothing runs; only a finished download notifies. */
    public static final AutomationSettings DEFAULTS = new AutomationSettings("", "", "", false, true, false, false);

    public AutomationSettings {
        onAdded = onAdded == null ? "" : onAdded.strip();
        onFinished = onFinished == null ? "" : onFinished.strip();
        onSeedingDone = onSeedingDone == null ? "" : onSeedingDone.strip();
    }

    /** The command line for an event, or empty: errors and the rest never run anything. */
    public String command(EngineEvent.Kind kind) {
        return switch (kind) {
            case ADDED -> onAdded;
            case FINISHED -> onFinished;
            case SEEDING_DONE -> onSeedingDone;
            case ERROR, OTHER -> "";
        };
    }

    /** Whether an event of this kind shows a desktop notification. */
    public boolean notifies(EngineEvent.Kind kind) {
        return switch (kind) {
            case ADDED -> notifyAdded;
            case FINISHED -> notifyFinished;
            case SEEDING_DONE -> notifySeedingDone;
            case ERROR -> notifyError;
            case OTHER -> false;
        };
    }

    /** Whether anything at all is switched on. */
    public boolean active() {
        return !onAdded.isEmpty()
                || !onFinished.isEmpty()
                || !onSeedingDone.isEmpty()
                || notifyAdded
                || notifyFinished
                || notifySeedingDone
                || notifyError;
    }
}
