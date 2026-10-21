package com.torrenta.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No English in the UI sources: every text a user reads goes through {@code Messages.tr}.
 *
 * <p>Reads the sources under {@code src/main/java} (the tests run from the project root, as
 * Surefire does), strips the comments, and fails with the file and line of every string literal
 * handed to a control's text, a menu item, a command label, a tooltip, a prompt, a title or the
 * status bar. The few literals that are not user text (an em dash, a style class that happens to
 * sit in a text position, a format pattern) are allow-listed by value, so a new one has to be
 * argued for here. The same pass checks that every key named in a {@code tr("…")} call exists in
 * the English catalogue, that no catalogue key is dead, and that no {@code static} field holds a
 * translated text (the language is chosen after classes load).
 */
class UiStringSweepTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "torrenta");

    /**
     * A string literal in a text position. Whitespace and newlines are allowed after the paren so
     * a wrapped call is still seen; the literal has to start right there, so {@code new
     * Label(tr("x"))} and {@code new Label(name)} do not match.
     */
    private static final Pattern TEXT_POSITION = Pattern.compile("(?:"
            + "new\\s+(?:Label|MenuItem|Menu|CheckMenuItem|RadioMenuItem|Button|CheckBox|RadioButton|ToggleButton|Tab|Tooltip|Hyperlink|TitledPane|Text|TextFlow|CustomMenuItem)\\s*\\(\\s*"
            + "|new\\s+TableColumn<[^>]*>\\s*\\(\\s*"
            + "|new\\s+TreeTableColumn<[^>]*>\\s*\\(\\s*"
            + "|\\.(?:setText|setPromptText|setTitle|setHeaderText|setContentText|setStatus|setTooltip|setAccessibleText)\\s*\\(\\s*(?:new\\s+Tooltip\\s*\\(\\s*)?"
            + "|new\\s+Command\\s*\\(\\s*\"[^\"]*\"\\s*,\\s*"
            + "|new\\s+Alert\\s*\\([^,()]*,\\s*"
            + ")\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern TR_LITERAL = Pattern.compile("\\btr\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TR_PREFIX = Pattern.compile("\\btr\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\+");
    private static final Pattern STATIC_TR =
            Pattern.compile("static\\s+(?:final\\s+)?\\w[\\w<>, ]*\\s+\\w+\\s*=[^;]*\\btr\\s*\\(");
    private static final Pattern ANY_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern LETTERS = Pattern.compile("\\p{L}");

    /**
     * Literals in a text position that are not user text, by value: punctuation and symbols are
     * allowed implicitly (no letter in them), these have letters and still are not words anyone
     * reads in a language.
     */
    private static final Set<String> ALLOWED = Set.of(
            // example values in prompts: a branch name and a list of addresses (URLs are allowed by shape)
            "main",
            "1.2.3.4\\n10.0.0.0/8\\n1.2.3.0-1.2.3.255",
            // the default name of the sync repository, in a TextInputDialog
            "torrenta-settings");

    private static boolean allowed(String literal) {
        if (!LETTERS.matcher(literal).find()) return true;
        if (ALLOWED.contains(literal)) return true;
        return literal.startsWith("http://") || literal.startsWith("https://") || literal.startsWith("udp://");
    }

    /** Files the sweep reads: every main source, so an enum label in the engine package is seen too. */
    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(out::add);
        }
        assertTrue(out.size() > 40, "found the UI sources: " + out.size());
        return out;
    }

    /** The source without its comments, newlines kept so line numbers hold. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                // a string literal: copy it whole, escapes included, so "//" inside stays
                int j = i + 1;
                while (j < n && source.charAt(j) != '"') {
                    if (source.charAt(j) == '\\') j++;
                    j++;
                }
                out.append(source, i, Math.min(j + 1, n));
                i = j + 1;
            } else if (c == '\'' && i + 2 < n) {
                int j = source.charAt(i + 1) == '\\' ? i + 3 : i + 2;
                out.append(source, i, Math.min(j + 1, n));
                i = j + 1;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) end = n - 2;
                for (int k = i; k < end + 2 && k < n; k++) if (source.charAt(k) == '\n') out.append('\n');
                i = end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    @Test
    void noStringLiteralInATextPosition() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = TEXT_POSITION.matcher(text);
            while (m.find()) {
                String literal = m.group(1);
                if (allowed(literal)) continue;
                offenders.add(file + ":" + lineOf(text, m.start()) + "  \"" + literal + "\"");
            }
        }
        assertEquals(List.of(), offenders, "English in the UI sources; wrap in tr(...) or allow-list here");
    }

    @Test
    void noStaticFieldHoldsATranslation() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = STATIC_TR.matcher(text);
            while (m.find()) offenders.add(file + ":" + lineOf(text, m.start()));
        }
        assertEquals(List.of(), offenders, "tr() in a static field runs before the language is chosen");
    }

    @Test
    void everyKeyNamedInTheSourcesExistsAndEveryCatalogueKeyIsUsed() throws Exception {
        Properties english = MessagesTest.catalogue("en");
        Set<String> keys = new TreeSet<>(english.stringPropertyNames());
        Set<String> named = new TreeSet<>();
        Set<String> prefixes = new TreeSet<>();
        Set<String> literals = new TreeSet<>();
        for (Path file : sources()) {
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = TR_LITERAL.matcher(text);
            while (m.find()) named.add(m.group(1));
            Matcher p = TR_PREFIX.matcher(text);
            while (p.find()) prefixes.add(p.group(1));
            Matcher any = ANY_LITERAL.matcher(text);
            while (any.find()) literals.add(any.group(1));
        }
        // tr("a.b" + x): the literal before the plus is a prefix, not a key
        Set<String> missing = new TreeSet<>();
        for (String key : named) {
            if (prefixes.contains(key)) continue;
            if (!keys.contains(key)) missing.add(key);
        }
        assertEquals(Set.of(), missing, "tr(key) names a key the English catalogue lacks");

        Set<String> dead = new TreeSet<>();
        for (String key : keys) {
            if (literals.contains(key)) continue; // tr("key"), or a key handed to a helper
            if (prefixes.stream().anyMatch(key::startsWith)) continue; // tr("prefix." + id)
            if (DERIVED_SUFFIXES.stream()
                    .anyMatch(s -> key.endsWith(s) && literals.contains(key.substring(0, key.length() - s.length())))) {
                continue; // tr(base + ".desc"), the row helper's shape
            }
            dead.add(key);
        }
        assertEquals(Set.of(), dead, "catalogue keys no source names (dead, or built in a way this sweep cannot see)");
    }

    /** Suffixes a helper appends to a base key it was handed. */
    private static final List<String> DERIVED_SUFFIXES = List.of(".desc", ".keywords", ".tooltip", ".title");

    @Test
    void theCommentStripperKeepsStringsAndLines() {
        String src = "a // c\n\"http://x\" /* b\n b */ d";
        assertEquals("a \n\"http://x\" \n d", stripComments(src));
    }
}
