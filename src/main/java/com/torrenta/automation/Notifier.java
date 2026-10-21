package com.torrenta.automation;

import java.util.Optional;

/**
 * Shows a desktop notification through whatever the platform has for it: {@code notify-send},
 * {@code osascript}, a PowerShell toast. Blocking and bounded to a few seconds; never on the FX
 * thread.
 */
public interface Notifier {

    /** What the platform's facility is called, for the log ("notify-send"). */
    String name();

    /** Shows the notification. Empty when it was shown, else one line saying why it was not. */
    Optional<String> show(String title, String message);

    /** For a platform without one. */
    static Notifier none(String why) {
        return new Notifier() {
            @Override
            public String name() {
                return "none";
            }

            @Override
            public Optional<String> show(String title, String message) {
                return Optional.of(why);
            }
        };
    }
}
