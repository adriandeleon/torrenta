package com.torrenta.ui;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;

/**
 * The filter box's text, read as a query: a test over {@link Torrent} and the chips that show it.
 *
 * <p>Terms are separated by spaces and must all hold. A plain word is a case-insensitive substring
 * of the name, so the box still filters by name as it always did. A term of the form {@code
 * key:value} or {@code key<value} narrows on something else:
 *
 * <ul>
 *   <li>{@code state:} downloading, seeding, paused, checking, metadata, finished — the State
 *       column's notion, so a torrent paused by the user is paused and nothing else
 *   <li>{@code cat:} / {@code category:} the category, exactly but ignoring case; {@code tag:} one
 *       of the tags, likewise
 *   <li>{@code tracker:} part of the tracker's host
 *   <li>{@code hash:} the start of the info-hash
 *   <li>{@code is:sequential}
 *   <li>{@code ratio}, {@code size} and {@code added} with {@code < <= > >= =} (or after a colon,
 *       {@code size:>4g}): sizes take k/m/g/t in binary units, as {@link Bytes} prints them; ages
 *       take h/d/w, and {@code added<7d} is "added within the last seven days"
 * </ul>
 *
 * <p>A leading {@code -} negates a term; double quotes keep spaces in a value ({@code
 * cat:"Linux ISOs"}) or in a phrase. Nothing here throws: a term whose key is unknown or whose
 * value does not read is matched against the name as typed (so {@code Wars:} still finds "Star
 * Wars: …") and its chip is marked invalid, for the omnibox to show while it is being typed.
 *
 * <p>Pure and immutable; "added" is measured from the {@code now} passed to {@link #parse}.
 */
public final class FilterQuery {

    /**
     * One {@code key:value} term as the omnibox shows it.
     *
     * @param term the term as typed, quotes and all, so it can be removed from the text
     * @param label what it means ("state is Seeding", "not tag keep"), or what is wrong with it
     * @param valid false when the term was read as name text instead
     */
    public record Chip(String term, String label, boolean valid) {}

    /** Matches everything. */
    public static final FilterQuery EMPTY = new FilterQuery(List.of(), List.of());

    private static final Pattern KEYED = Pattern.compile("([A-Za-z]+)(<=|>=|<|>|=|:)(.*)", Pattern.DOTALL);
    private static final Pattern COMPARISON = Pattern.compile("(<=|>=|<|>|=)?(.*)", Pattern.DOTALL);
    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?|\\.\\d+");
    private static final Pattern SIZE = Pattern.compile("(\\d+(?:\\.\\d+)?|\\.\\d+)\\s*([kmgt]?)(?:i?b)?");
    private static final Pattern AGE = Pattern.compile("(\\d+)\\s*([hdw])");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]+");

    private final List<Predicate<Torrent>> tests;
    private final List<Chip> chips;

    private FilterQuery(List<Predicate<Torrent>> tests, List<Chip> chips) {
        this.tests = List.copyOf(tests);
        this.chips = List.copyOf(chips);
    }

    /** Reads {@code text}; {@code now} is epoch seconds, what {@code added<7d} counts back from. */
    public static FilterQuery parse(String text, long now) {
        if (text == null || text.isBlank()) return EMPTY;
        List<Predicate<Torrent>> tests = new ArrayList<>();
        List<Chip> chips = new ArrayList<>();
        for (Token token : tokens(text)) {
            Predicate<Torrent> test = term(token, now, chips);
            tests.add(token.negated ? test.negate() : test);
        }
        return new FilterQuery(tests, chips);
    }

    public boolean matches(Torrent t) {
        for (Predicate<Torrent> test : tests) if (!test.test(t)) return false;
        return true;
    }

    /** The {@code key:value} terms in order, valid or not; plain words have none. */
    public List<Chip> chips() {
        return chips;
    }

    public boolean isEmpty() {
        return tests.isEmpty();
    }

    // ---- terms

    /** The test for one token, without its negation; adds the token's chip if it has a key. */
    private static Predicate<Torrent> term(Token token, long now, List<Chip> chips) {
        Matcher m = token.quotedKey ? null : KEYED.matcher(token.text);
        if (m == null || !m.matches()) return name(token.text);
        String key = m.group(1).toLowerCase(Locale.ROOT);
        String op = m.group(2);
        String value = m.group(3).trim();
        Term term =
                switch (key) {
                    case "state" -> op.equals(":") ? state(value) : Term.invalid("state takes a colon");
                    case "cat", "category" -> op.equals(":") ? category(value) : Term.invalid(key + " takes a colon");
                    case "tag" -> op.equals(":") ? tag(value) : Term.invalid("tag takes a colon");
                    case "tracker" -> op.equals(":") ? tracker(value) : Term.invalid("tracker takes a colon");
                    case "hash" -> op.equals(":") ? hash(value) : Term.invalid("hash takes a colon");
                    case "is" -> op.equals(":") ? is(value) : Term.invalid("is takes a colon");
                    case "ratio" -> ratio(comparison(op, value));
                    case "size" -> size(comparison(op, value));
                    case "added" -> added(comparison(op, value), now);
                    default -> Term.invalid("unknown filter " + key);
                };
        if (term.test == null) {
            chips.add(new Chip(token.raw, term.label, false));
            return name(token.text);
        }
        chips.add(new Chip(token.raw, (token.negated ? "not " : "") + term.label, true));
        return term.test;
    }

    private static Predicate<Torrent> name(String word) {
        String needle = word.toLowerCase(Locale.ROOT);
        return t -> t.name().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Term state(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "downloading" -> running(TorrentState.DOWNLOADING);
            case "seeding" -> running(TorrentState.SEEDING);
            case "checking" -> running(TorrentState.CHECKING);
            case "metadata" -> running(TorrentState.FETCHING_METADATA);
            case "finished" -> running(TorrentState.FINISHED);
            case "paused" -> new Term(Torrent::paused, "state is Paused");
            default -> Term.invalid(value.isEmpty() ? "state needs a value" : "no state " + value);
        };
    }

    /** The State column's rule: a user pause outranks the engine's state. */
    private static Term running(TorrentState state) {
        return new Term(t -> !t.paused() && t.state() == state, "state is " + state.label());
    }

    private static Term category(String value) {
        if (value.isEmpty()) return Term.invalid("category needs a name");
        return new Term(t -> t.category().equalsIgnoreCase(value), "category " + value);
    }

    private static Term tag(String value) {
        if (value.isEmpty()) return Term.invalid("tag needs a name");
        return new Term(t -> t.tags().stream().anyMatch(value::equalsIgnoreCase), "tag " + value);
    }

    private static Term tracker(String value) {
        if (value.isEmpty()) return Term.invalid("tracker needs a host");
        String needle = value.toLowerCase(Locale.ROOT);
        return new Term(
                t -> !t.tracker().isBlank()
                        && Format.host(t.tracker()).toLowerCase(Locale.ROOT).contains(needle),
                "tracker " + value);
    }

    private static Term hash(String value) {
        String prefix = value.toLowerCase(Locale.ROOT);
        if (!HEX.matcher(prefix).matches()) return Term.invalid("hash takes hex digits");
        return new Term(t -> t.id().toLowerCase(Locale.ROOT).startsWith(prefix), "hash " + prefix + "…");
    }

    private static Term is(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "sequential" -> new Term(Torrent::sequential, "sequential");
            default -> Term.invalid(value.isEmpty() ? "is needs a value" : "no such flag " + value);
        };
    }

    // ---- comparisons

    private enum Op {
        LT("under"),
        LE("at most"),
        GT("over"),
        GE("at least"),
        EQ("is");

        /** How the op reads in a ratio or size chip. */
        final String word;

        Op(String word) {
            this.word = word;
        }

        static Op of(String symbol) {
            return switch (symbol) {
                case "<" -> LT;
                case "<=" -> LE;
                case ">" -> GT;
                case ">=" -> GE;
                default -> EQ;
            };
        }

        boolean holds(int comparison) {
            return switch (this) {
                case LT -> comparison < 0;
                case LE -> comparison <= 0;
                case GT -> comparison > 0;
                case GE -> comparison >= 0;
                case EQ -> comparison == 0;
            };
        }
    }

    /** The operator and the operand: {@code size>4g}, or {@code size:>4g}, or {@code size:4g} for equal. */
    private record Comparison(Op op, String operand) {}

    private static Comparison comparison(String op, String value) {
        if (!op.equals(":")) return new Comparison(Op.of(op), value);
        Matcher m = COMPARISON.matcher(value);
        m.matches();
        return new Comparison(
                m.group(1) == null ? Op.EQ : Op.of(m.group(1)), m.group(2).trim());
    }

    private static Term ratio(Comparison c) {
        if (!NUMBER.matcher(c.operand).matches()) return Term.invalid("ratio takes a number");
        double bound = Double.parseDouble(c.operand);
        // Nothing moved either way shows as a dash, and counts as no ratio at all: 0.
        return new Term(
                t -> c.op.holds(Double.compare(Math.max(0, Format.ratioValue(t.uploaded(), t.downloaded())), bound)),
                "ratio " + c.op.word + " " + c.operand);
    }

    private static Term size(Comparison c) {
        Matcher m = SIZE.matcher(c.operand.toLowerCase(Locale.ROOT));
        if (!m.matches()) return Term.invalid("size takes a number and k, m, g or t");
        int shift =
                switch (m.group(2)) {
                    case "k" -> 10;
                    case "m" -> 20;
                    case "g" -> 30;
                    case "t" -> 40;
                    default -> 0;
                };
        long bound = new BigDecimal(m.group(1))
                .multiply(BigDecimal.valueOf(1L << shift))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValue();
        return new Term(t -> c.op.holds(Long.compare(t.size(), bound)), "size " + c.op.word + " " + Bytes.human(bound));
    }

    /**
     * {@code added<7d}: added less than seven days ago; {@code added>7d}: longer ago than that. A
     * torrent whose added time is unknown (0) matches neither.
     */
    private static Term added(Comparison c, long now) {
        if (c.op == Op.EQ) return Term.invalid("added takes < or >");
        Matcher m = AGE.matcher(c.operand.toLowerCase(Locale.ROOT));
        if (!m.matches()) return Term.invalid("added takes a number and h, d or w");
        long amount;
        try {
            amount = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return Term.invalid("added: too long ago");
        }
        String unit =
                switch (m.group(2)) {
                    case "h" -> "hour";
                    case "d" -> "day";
                    default -> "week";
                };
        long seconds =
                switch (unit) {
                    case "hour" -> 3600L;
                    case "day" -> 86_400L;
                    default -> 7 * 86_400L;
                };
        long age;
        try {
            age = Math.multiplyExact(amount, seconds);
        } catch (ArithmeticException e) {
            return Term.invalid("added: too long ago");
        }
        String span = amount + " " + unit + (amount == 1 ? "" : "s");
        String label =
                switch (c.op) {
                    case LT -> "added in the last " + span;
                    case LE -> "added at most " + span + " ago";
                    case GT -> "added more than " + span + " ago";
                    default -> "added at least " + span + " ago";
                };
        // Compare ages, not instants: the younger the torrent, the smaller its age.
        return new Term(t -> t.added() > 0 && c.op.holds(Long.compare(now - t.added(), age)), label);
    }

    /** A term read: a test and its label, or no test and what is wrong. */
    private record Term(Predicate<Torrent> test, String label) {
        static Term invalid(String why) {
            return new Term(null, why);
        }
    }

    // ---- tokens

    /**
     * One space-separated term.
     *
     * @param raw as typed, quotes and minus included
     * @param text without the minus and the quotes
     * @param negated it began with a minus (and had more after it)
     * @param quotedKey a quote came before any colon or operator, so it is a phrase, not a key
     */
    private record Token(String raw, String text, boolean negated, boolean quotedKey) {}

    private static List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= n) break;
            int start = i;
            StringBuilder unquoted = new StringBuilder();
            boolean inQuotes = false;
            int firstQuote = -1;
            while (i < n && (inQuotes || !Character.isWhitespace(text.charAt(i)))) {
                char c = text.charAt(i);
                if (c == '"') {
                    if (firstQuote < 0) firstQuote = unquoted.length();
                    inQuotes = !inQuotes;
                } else {
                    unquoted.append(c);
                }
                i++;
            }
            String raw = text.substring(start, i);
            String body = unquoted.toString();
            boolean negated = body.length() > 1 && body.charAt(0) == '-' && raw.charAt(0) == '-';
            int offset = negated ? 1 : 0;
            if (negated) body = body.substring(1);
            if (body.isEmpty()) continue; // "" or a lone pair of quotes: nothing to match
            boolean quotedKey = firstQuote >= 0 && firstQuote - offset <= keyEnd(body);
            out.add(new Token(raw, body, negated, quotedKey));
        }
        return out;
    }

    /** Where a key would end: the first colon or comparison sign, or the end. */
    private static int keyEnd(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ':' || c == '<' || c == '>' || c == '=') return i;
        }
        return body.length();
    }
}
