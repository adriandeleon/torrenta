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
            + ")\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"");

    /*
     * A literal's body is [^"\\]*+(?:\\.[^"\\]*+)*+, possessive and unrolled, never the plainer
     * (?:[^"\\]|\\.)*: java.util.regex recurses once per character through an alternation in a
     * loop, and a long literal overflowed the stack on Linux CI (smaller thread stacks), while the
     * possessive character class runs as a loop and the group recurses only per escape.
     */
    private static final Pattern TR_LITERAL =
            Pattern.compile("\\btr\\s*\\(\\s*\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"");
    private static final Pattern TR_PREFIX =
            Pattern.compile("\\btr\\s*\\(\\s*\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"\\s*\\+");
    private static final Pattern STATIC_TR =
            Pattern.compile("static\\s+(?:final\\s+)?\\w[\\w<>, ]*\\s+\\w+\\s*=[^;]*\\btr\\s*\\(");
    private static final Pattern ANY_LITERAL = Pattern.compile("\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"");
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
            "torrenta-settings",
            // the program run when the field is empty, as its prompt
            "curl");

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
                // a char literal becomes ' ': a '"' left as it was paired with the next string's
                // quote, and the "literal" between them ran on for a thousand characters
                int j = source.charAt(i + 1) == '\\' ? i + 3 : i + 2;
                out.append("' '");
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

    /**
     * Calls whose message a person reads without a text position in sight: an engine error or
     * event (the status bar, the Add dialog, the event log, the notifications), a provider's or a
     * feed's failure, a definition file's problem, a tray or badge reason. Every literal among
     * their arguments is a sentence in English unless it sits inside a {@code tr(…)}.
     */
    private static final Pattern CARRIER = Pattern.compile("(?:"
            + "new\\s+(?:EngineException|SearchException|FeedException|EngineEvent)\\s*\\("
            + "|EngineEvent\\s*\\.\\s*(?:info|warning|error|session|of)\\s*\\("
            + "|Attempt\\s*\\.\\s*none\\s*\\("
            + ")");

    /** The file and the key at fault come first; only the third argument is the sentence. */
    private static final Pattern DEFINITION_EXCEPTION = Pattern.compile("new\\s+DefinitionException\\s*\\(");

    /**
     * Methods whose result the window shows as it is: a dialog's problem line, a failure's reason,
     * a notification, a log line, a status line. A literal in one of their {@code return}s or
     * {@code Optional.of(…)}s is English on every screen. A bare name counts in every class (a
     * {@code problem} or a {@code reason} is always a sentence for a person); {@code Class.method}
     * names one method where the name alone is too common. A new method of that kind belongs here.
     */
    private static final Set<String> SHOWN_METHODS = Set.of(
            "problem",
            "regexProblem",
            "reason",
            "notificationText",
            "Automation.label",
            "RuleEngine.describe",
            "RuleEngine.describeFailure",
            "WatchFolders.statusText",
            "TorznabEndpoint.maskedKey",
            "LibtorrentEngine.description",
            "LibtorrentEngine.limitText",
            "TransmissionEngine.description",
            "DetailPane.trackerStatus",
            "PieceSize.label");

    private static final Pattern METHOD_HEADER =
            Pattern.compile("[\\w>\\]?]\\s+(\\w+)\\s*\\([^()]*\\)\\s*(?:throws\\s+[\\w., ]+)?\\{");

    private static final Pattern RETURNED = Pattern.compile("\\breturn\\b|\\bOptional\\s*\\.\\s*of\\s*\\(");

    /**
     * Literals that are not sentences even where a person may read them: product names and the
     * library's own words, which every language writes the same.
     */
    private static final Set<String> NOT_SENTENCES = Set.of(
            // LibtorrentEngine.description(): "libtorrent 2.1.0 (libtorrent4j 2.1.0-39)"
            "libtorrent ",
            " (libtorrent4j ",
            // binary units, written the same in every language (Bytes)
            " KiB",
            " MiB");

    /** A switch's {@code case "…"} label: a value the method was handed, not one it shows. */
    private static final Pattern CASE_LABEL = Pattern.compile("\\bcase\\s+\"(?:[^\"\\\\]|\\\\.)*\"");

    @Test
    void noEnglishInAnEngineErrorAnEventOrAFailure() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = CARRIER.matcher(text);
            while (m.find()) {
                String args = argumentsAt(text, m.end());
                for (String literal : untranslated(args)) {
                    offenders.add(file + ":" + lineOf(text, m.start()) + "  \"" + literal + "\"");
                }
            }
            Matcher d = DEFINITION_EXCEPTION.matcher(text);
            while (d.find()) {
                List<String> args = splitArguments(argumentsAt(text, d.end()));
                for (int i = 2; i < args.size(); i++) {
                    for (String literal : untranslated(args.get(i))) {
                        offenders.add(file + ":" + lineOf(text, d.start()) + "  \"" + literal + "\"");
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "English handed to an exception or an event a person reads; use tr(...)");
    }

    @Test
    void noEnglishReturnedFromAMethodTheWindowShows() throws IOException {
        // A set: an Optional.of inside a return is seen by both patterns.
        Set<String> offenders = new java.util.LinkedHashSet<>();
        for (Path file : sources()) {
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            String type = file.getFileName().toString().replace(".java", "");
            Matcher h = METHOD_HEADER.matcher(text);
            while (h.find()) {
                if (!SHOWN_METHODS.contains(h.group(1)) && !SHOWN_METHODS.contains(type + "." + h.group(1))) continue;
                int open = h.end() - 1;
                String body = text.substring(open + 1, closing(text, open, '{', '}'));
                Matcher r = RETURNED.matcher(body);
                while (r.find()) {
                    int end = body.indexOf(';', r.end());
                    // A switch expression returns from every arm: the whole switch is what is returned.
                    if (body.substring(r.end()).stripLeading().startsWith("switch")) {
                        end = closing(body, body.indexOf('{', r.end()), '{', '}');
                    }
                    String returned = CASE_LABEL
                            .matcher(body.substring(r.end(), end < 0 ? body.length() : end))
                            .replaceAll("case _");
                    for (String literal : untranslated(returned)) {
                        offenders.add(file + ":" + lineOf(text, open + 1 + r.start()) + "  " + h.group(1) + "() \""
                                + literal + "\"");
                    }
                }
            }
        }
        assertEquals(
                List.of(), List.copyOf(offenders), "English returned from a method whose result is shown; use tr(...)");
    }

    @Test
    void theSweepSeesThroughTranslationsAndNothingElse() {
        assertEquals(List.of(), untranslated("tr(\"engine.error.noSuchFile\", input), e"));
        assertEquals(List.of(), untranslated("tr(\"search.def.query\", \"{query}\")"));
        assertEquals(List.of("No such file: "), untranslated("\"No such file: \" + input"));
        assertEquals(List.of("failed"), untranslated("x ? tr(\"a\") : \"failed\""));
        assertEquals(List.of(), untranslated("name + \": \" + message"));
        assertEquals(List.of("a", "b(c)", "\"d,e\""), splitArguments("a, b(c), \"d,e\""));
        String call = "new EngineException(\"x (y)\", e) + z";
        assertEquals("\"x (y)\", e", argumentsAt(call, call.indexOf('(') + 1));
    }

    /** The text between the parenthesis just before {@code from} and its partner. */
    static String argumentsAt(String text, int from) {
        return text.substring(from, closing(text, from - 1, '(', ')'));
    }

    /** Where the bracket opened at {@code open} closes, strings and characters skipped; the end if never. */
    private static int closing(String text, int open, char opener, char closer) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(text, i);
            } else if (c == opener) {
                depth++;
            } else if (c == closer && --depth == 0) {
                return i;
            }
        }
        return text.length();
    }

    /** The index of the quote that ends the literal opened at {@code i}. */
    private static int skipQuoted(String text, int i) {
        char quote = text.charAt(i);
        int j = i + 1;
        while (j < text.length() && text.charAt(j) != quote) {
            if (text.charAt(j) == '\\') j++;
            j++;
        }
        return j;
    }

    /** The top-level arguments of an argument list, trimmed. */
    static List<String> splitArguments(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '"' || c == '\'') i = skipQuoted(args, i);
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(args.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (!args.isBlank()) out.add(args.substring(start).trim());
        return out;
    }

    /** The literals with letters in {@code code} once every {@code tr(…)} call is taken out. */
    static List<String> untranslated(String code) {
        StringBuilder rest = new StringBuilder();
        Matcher tr = Pattern.compile("\\btr\\s*\\(").matcher(code);
        int at = 0;
        while (tr.find(at)) {
            rest.append(code, at, tr.start());
            at = Math.min(code.length(), closing(code, tr.end() - 1, '(', ')') + 1);
        }
        rest.append(code.substring(Math.min(at, code.length())));
        List<String> out = new ArrayList<>();
        Matcher literal = ANY_LITERAL.matcher(rest);
        while (literal.find()) {
            String value = literal.group(1);
            if (LETTERS.matcher(value).find() && !NOT_SENTENCES.contains(value)) out.add(value);
        }
        return out;
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
        assertEquals("c == ' ' || s == \"x\"", stripComments("c == '\"' || s == \"x\""));
        assertEquals("' ' + \"a\"", stripComments("'\\'' + \"a\""));
    }
}
