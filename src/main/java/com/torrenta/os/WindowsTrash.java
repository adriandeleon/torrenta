package com.torrenta.os;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Recycle Bin, through a PowerShell script of ours that hands the path to the shell's own
 * {@code SendToRecycleBin} (Visual Basic's {@code FileSystem}, which every Windows has). The path
 * is the script's parameter, never part of a command line it parses.
 */
final class WindowsTrash implements Trash {

    /** Long enough for a big folder; the shell counts the items before it moves them. */
    private static final long TIMEOUT_SECONDS = 120;

    /** The script, a resource of the module, unpacked once per run. */
    private static final class Script {
        static final Path PATH = unpack();

        private static Path unpack() {
            try (InputStream in = WindowsTrash.class.getResourceAsStream("recycle.ps1")) {
                if (in == null) throw new IOException("recycle.ps1 is missing from the application");
                Path file = Files.createTempFile("torrenta-recycle", ".ps1");
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                file.toFile().deleteOnExit();
                return file;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    @Override
    public void trash(Path path) throws IOException {
        Path item = path.toAbsolutePath().normalize();
        if (!Files.exists(item)) return;
        Process p;
        try {
            p = new ProcessBuilder(argv(Script.PATH, item))
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")))
                    .start();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        String output;
        try (InputStream out = p.getInputStream()) {
            output = new String(out.readNBytes(8192), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("The Recycle Bin did not answer for " + item);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted while recycling " + item, e);
        }
        if (p.exitValue() != 0) throw new IOException(output.isEmpty() ? "Could not recycle " + item : output);
    }

    /** No profile, no prompt, the path as the script's one parameter. */
    static List<String> argv(Path script, Path item) {
        return List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
                "-Path",
                item.toString());
    }
}
