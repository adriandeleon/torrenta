package com.torrenta;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

import com.torrenta.store.Settings;
import com.torrenta.webapi.Passwords;

/**
 * {@code torrenta --set-remote-password}: Remote access's password for a server with no window to
 * type it into. Asked twice at a terminal, with no echo; read as one line from stdin otherwise (a
 * script, a secret piped in). Only its hash reaches the settings; the switch itself is left as it
 * is, so {@code webapi.enabled=true} is still the person's to write.
 */
final class RemotePassword {

    private RemotePassword() {}

    /** The exit status: 0 set, 1 not. */
    static int run(Path configDir, Console console, InputStream in, PrintStream out, PrintStream err) {
        char[] password;
        try {
            password = console != null ? ask(console) : readLine(in);
        } catch (IOException e) {
            err.println(AppInfo.NAME + ": " + e.getMessage());
            return 1;
        }
        if (password == null) {
            err.println(AppInfo.NAME + ": the two passwords differ; nothing was changed");
            return 1;
        }
        if (password.length == 0) {
            err.println(AppInfo.NAME + ": an empty password; nothing was changed");
            return 1;
        }
        Settings settings = new Settings(configDir.resolve(Settings.FILE_NAME));
        settings.load();
        settings.setWebApiPasswordHash(Passwords.hash(password, new SecureRandom()));
        Arrays.fill(password, '\0');
        try {
            settings.save();
        } catch (IOException e) {
            err.println(AppInfo.NAME + ": the settings were not saved: " + e.getMessage());
            return 1;
        }
        out.println("Remote access: the password for '" + settings.webApi().username() + "' is set in "
                + configDir.resolve(Settings.FILE_NAME)
                + (settings.webApi().enabled() ? "." : "; set webapi.enabled=true there to switch it on."));
        return 0;
    }

    /** Twice with no echo; null when the two differ. */
    private static char[] ask(Console console) {
        char[] first = console.readPassword("Password for Remote access: ");
        char[] second = console.readPassword("Again: ");
        if (first == null || second == null) return new char[0];
        boolean same = Arrays.equals(first, second);
        Arrays.fill(second, '\0');
        if (same) return first;
        Arrays.fill(first, '\0');
        return null;
    }

    private static char[] readLine(InputStream in) throws IOException {
        String line = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
        return line == null ? new char[0] : line.toCharArray();
    }
}
