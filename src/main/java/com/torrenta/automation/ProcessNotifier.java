package com.torrenta.automation;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import static com.torrenta.i18n.Messages.tr;

/**
 * A notifier that runs one program per notification, the title and message passed as arguments,
 * never pasted into a script: a torrent named {@code "; rm -rf ~} is only a torrent name.
 */
final class ProcessNotifier implements Notifier {

    /** Longer than any of them takes; a hung one is killed. */
    static final Duration CEILING = Duration.ofSeconds(10);

    private final String name;
    private final BiFunction<String, String, List<String>> argv;

    /** @param argv the command line for a title and a message, the program first */
    ProcessNotifier(String name, BiFunction<String, String, List<String>> argv) {
        this.name = name;
        this.argv = argv;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<String> show(String title, String message) {
        List<String> command;
        try {
            command = argv.apply(title, message);
        } catch (RuntimeException e) {
            return Optional.of(tr("automation.notifier.failed", name, e.getMessage()));
        }
        Subprocess.Result result = Subprocess.run(command, CEILING, 2000);
        if (!result.started()) return Optional.of(tr("automation.couldNotRun", name, result.failure()));
        if (result.timedOut()) return Optional.of(tr("automation.notifier.timedOut", name, CEILING.toSeconds()));
        if (result.code() != 0) {
            List<String> lines = result.firstLines(1);
            return Optional.of(
                    lines.isEmpty()
                            ? tr("automation.exited", name, String.valueOf(result.code()))
                            : tr("automation.exitedWith", name, String.valueOf(result.code()), lines.get(0)));
        }
        return Optional.empty();
    }
}
