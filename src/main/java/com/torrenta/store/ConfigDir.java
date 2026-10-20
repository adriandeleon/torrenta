package com.torrenta.store;

import java.nio.file.Path;
import java.util.Map;

/**
 * Where Torrenta keeps its files: settings, resume data, the unpacked engine.
 *
 * <p>{@code ~/.torrenta}, as Termina uses {@code ~/.termina}: one folder, the same on every
 * platform, easy to find and easy to delete. Overridden by {@code --config-dir} first and the
 * {@value #ENV} environment variable second, so a dev run, a capture or a test never touches the
 * real one.
 */
public final class ConfigDir {

    public static final String ENV = "TORRENTA_CONFIG_DIR";
    public static final String FOLDER = ".torrenta";

    private ConfigDir() {}

    public static Path resolve(String commandLineOverride) {
        return resolve(commandLineOverride, System.getenv(), System.getProperty("user.home"));
    }

    /** Pure, so the precedence is testable. */
    static Path resolve(String commandLineOverride, Map<String, String> env, String userHome) {
        if (commandLineOverride != null && !commandLineOverride.isBlank()) {
            return Path.of(commandLineOverride).toAbsolutePath();
        }
        String fromEnv = env.get(ENV);
        if (fromEnv != null && !fromEnv.isBlank()) return Path.of(fromEnv).toAbsolutePath();
        return Path.of(userHome, FOLDER);
    }
}
