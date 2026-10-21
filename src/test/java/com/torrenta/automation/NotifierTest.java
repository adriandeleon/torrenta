package com.torrenta.automation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifierTest {

    @Test
    void theTitleAndMessageArriveAsTwoArguments(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("args.txt");
        Notifier notifier = new ProcessNotifier("stand-in", (title, message) -> {
            List<String> argv = new ArrayList<>(ArgsToFile.command(out));
            argv.add(title);
            argv.add(message);
            return argv;
        });
        String message = "Some \"quoted\" name; with $HOME and spaces finished";
        assertEquals(Optional.empty(), notifier.show("Torrenta", message));
        assertEquals(List.of("Torrenta", message), Files.readAllLines(out, StandardCharsets.UTF_8));
    }

    @Test
    void aProgramThatIsNotThereIsAProblemNotAnException() {
        Notifier notifier = new ProcessNotifier(
                "notify-send", (t, m) -> List.of("torrenta-no-such-program-" + System.nanoTime(), t, m));
        Optional<String> problem = notifier.show("Torrenta", "hello");
        assertTrue(problem.isPresent());
        assertTrue(problem.get().startsWith("notify-send could not be run: "), problem.get());
    }

    @Test
    void aFailingExitIsReportedWithItsFirstLine(@TempDir Path dir) {
        Path out = dir.resolve("args.txt");
        Notifier notifier = new ProcessNotifier("stand-in", (title, message) -> {
            List<String> argv = new ArrayList<>(ArgsToFile.command(out));
            argv.add("--exit");
            argv.add("3");
            return argv;
        });
        Optional<String> problem = notifier.show("Torrenta", "hello");
        assertEquals(Optional.of("stand-in exited 3: hello from ArgsToFile"), problem);
    }

    @Test
    void theCeilingKillsAHungProgram(@TempDir Path dir) {
        List<String> argv = new ArrayList<>(ArgsToFile.command(dir.resolve("args.txt")));
        argv.add("--sleep");
        Subprocess.Result result = Subprocess.run(argv, Duration.ofSeconds(2), 100);
        assertTrue(result.started());
        assertTrue(result.timedOut(), "killed at the ceiling");
    }

    @Test
    void theOutputIsKeptOnlyUpToTheLimit(@TempDir Path dir) {
        Subprocess.Result result =
                Subprocess.run(ArgsToFile.command(dir.resolve("args.txt")), Duration.ofSeconds(30), 10);
        assertTrue(result.ok(), result.toString());
        assertEquals("hello from", result.output());
        assertEquals(List.of("hello from"), result.firstLines(3));
    }

    @Test
    void windowsArgumentsSurviveTheCRuntimesQuoting() {
        assertEquals("plain", Subprocess.windowsArgument("plain"));
        assertEquals("two words", Subprocess.windowsArgument("two words"), "the JDK wraps this one itself");
        assertEquals("Some \\\"quoted\\\" name", Subprocess.windowsArgument("Some \"quoted\" name"));
        assertEquals("a\\\\\\\"b", Subprocess.windowsArgument("a\\\"b"), "backslashes before a quote double");
        assertEquals(
                "C:\\dir with space\\\\", Subprocess.windowsArgument("C:\\dir with space\\"), "a trailing one too");
        assertEquals("C:\\dir\\", Subprocess.windowsArgument("C:\\dir\\"), "but not when there is nothing to wrap");
        assertEquals("C:\\Program Files\\x", Subprocess.windowsArgument("C:\\Program Files\\x"), "inner ones stay");
    }

    @Test
    void eachPlatformsCommandLinePassesTheTextAsArguments() {
        assertEquals(
                List.of("notify-send", "--app-name=Torrenta", "--", "T", "-starts with a dash"),
                Notifiers.linuxArgv("T", "-starts with a dash"),
                "the double dash ends the options");
        List<String> mac = Notifiers.macArgv("T", "it's \"quoted\"");
        assertEquals("osascript", mac.get(0));
        assertEquals(
                List.of("T", "it's \"quoted\""),
                mac.subList(mac.size() - 2, mac.size()),
                "the text is argv, not script");
        assertTrue(
                mac.stream().noneMatch(a -> a.contains("quoted") && a.contains("display")), "never inside the script");
        List<String> win = Notifiers.windowsArgv(Path.of("toast.ps1"), "T", "M");
        assertEquals("powershell.exe", win.get(0));
        assertTrue(win.containsAll(List.of("-NoProfile", "-NonInteractive", "-File", "-Title", "T", "-Message", "M")));
        assertEquals(win.indexOf("-File") + 1, win.indexOf("toast.ps1"));
    }

    @Test
    void thePlatformPicksTheNotifier() {
        assertEquals("notify-send", Notifiers.forPlatform("Linux").name());
        assertEquals("notify-send", Notifiers.forPlatform("FreeBSD").name());
        assertEquals("osascript", Notifiers.forPlatform("Mac OS X").name());
        assertEquals("PowerShell", Notifiers.forPlatform("Windows 11").name());
        assertTrue(Notifier.none("no notifier here").show("T", "M").isPresent());
    }

    @Test
    void theToastScriptShipsWithTheApplication() throws Exception {
        try (var in = Notifiers.class.getResourceAsStream("toast.ps1")) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(script.contains("[string]$Title"), "reads its text from parameters");
            assertTrue(script.contains("CreateTextNode($Message)"), "and puts it in as text, not markup");
            assertTrue(script.contains("Windows.UI.Notifications.ToastNotificationManager"));
        }
    }
}
