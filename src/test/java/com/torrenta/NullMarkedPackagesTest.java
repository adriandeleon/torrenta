package com.torrenta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ratchet behind NullAway (CLAUDE.md, "Null annotations"). NullAway checks only a package whose
 * {@code package-info.java} says {@code @NullMarked}; nothing else would stop a new package from
 * starting unchecked, or the pom from losing the plugin. Editora's test, without its list: every
 * package here was marked from the start.
 */
class NullMarkedPackagesTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir"));
    private static final Path SOURCES = REPO.resolve("src/main/java");
    private static final Pattern NULL_MARKED =
            Pattern.compile("@(?:org\\.jspecify\\.annotations\\.)?NullMarked\\b[\\s\\S]*\\bpackage\\s");

    @Test
    void everyPackageIsNullMarked() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String pkg : packages()) {
            if (!nullMarked(pkg)) {
                problems.add(pkg + " is not @NullMarked: add src/main/java/" + pkg.replace('.', '/')
                        + "/package-info.java with @NullMarked");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theMainCompileRunsNullAwayOnTheMarkedPackages() throws IOException {
        String pom = Files.readString(REPO.resolve("pom.xml"));
        for (String flag : new String[] {
            "-Xplugin:ErrorProne",
            "-Xep:NullAway:ERROR",
            "-XepOpt:NullAway:OnlyNullMarked=true",
            "<artifactId>nullaway</artifactId>"
        }) {
            assertTrue(pom.contains(flag), "pom.xml no longer has " + flag + ": @NullMarked would check nothing");
        }
        String moduleInfo = Files.readString(SOURCES.resolve("module-info.java"));
        assertTrue(moduleInfo.contains("requires static org.jspecify;"), "module-info.java needs org.jspecify");
    }

    /** Every package of the main sources, by its folder: one with at least one {@code .java} file. */
    private static TreeSet<String> packages() throws IOException {
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(f -> f.getFileName().toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("module-info.java"))
                    .forEach(f -> out.add(SOURCES.relativize(f.getParent())
                            .toString()
                            .replace(f.getFileSystem().getSeparator(), ".")));
        }
        return out;
    }

    private static boolean nullMarked(String pkg) throws IOException {
        Path info = SOURCES.resolve(pkg.replace('.', '/')).resolve("package-info.java");
        return Files.isRegularFile(info)
                && NULL_MARKED.matcher(Files.readString(info)).find();
    }
}
