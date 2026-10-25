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

import static com.torrenta.i18n.Messages.tr;

/**
 * The filter box's text, read as a query: a test over {@link Torrent} and the chips that show it.
 *
 * <p>Terms are separated by spaces and must all hold. A plain word is a case-insensitive substring
 * of the name, so the box still filters by name as it always did. A term of the form {@code
 * key:value} or {@code key<value} narrows on something else:
 *
 * <ul>
 *   <li>{@code state:} downloading, seeding, paused, checking, metadata, finished, error — what
 *       the State column says, so an error outranks a pause and a pause outranks the rest
 *   <li>{@code cat:} / {@code category:} the category, exactly but ignoring case; {@code tag:} one
 *       of the tags, likewise
 *   <li>{@code tracker:} part of the host of any of its trackers
 *   <li>{@code hash:} the start of the info-hash
 *   <li>{@code is:sequential}
 *   <li>{@code ratio}, {@code size} and {@code added} with {@code < <= > >= =} (or after a colon,
 *       {@code size:>4g}): sizes take k/m/g/t in binary units, as {@link Bytes} prints them; ages
 *       take h/d/w, and {@code added<7d} is "added within the last seven days"
 *   <li>{@code uploaded} (bytes, as size), {@code seeds} (connected seeds, a whole number), and
 *       {@code seeding} and {@code eta} with {@code <} or {@code >} and m/h/d/w: {@code seeding>2d}
 *       has seeded more than two days in all, {@code eta<30m} finishes within half an hour
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
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([mhdw])");
    private static final Pattern WHOLE = Pattern.compile("\\d+");
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
                    case "state" -> op.equals(":") ? state(value) : Term.invalid(tr("filter.takesColon", key));
                    case "cat", "category" ->
                        op.equals(":") ? category(value) : Term.invalid(tr("filter.takesColon", key));
                    case "tag" -> op.equals(":") ? tag(value) : Term.invalid(tr("filter.takesColon", key));
                    case "tracker" -> op.equals(":") ? tracker(value) : Term.invalid(tr("filter.takesColon", key));
                    case "hash" -> op.equals(":") ? hash(value) : Term.invalid(tr("filter.takesColon", key));
                    case "is" -> op.equals(":") ? is(value) : Term.invalid(tr("filter.takesColon", key));
                    case "ratio" -> ratio(comparison(op, value));
                    case "size" -> size(comparison(op, value));
                    case "uploaded" -> uploaded(comparison(op, value));
                    case "added" -> added(comparison(op, value), now);
                    case "seeding" -> seeding(comparison(op, value));
                    case "eta" -> eta(comparison(op, value));
                    case "seeds" -> seeds(comparison(op, value));
                    default -> Term.invalid(tr("filter.unknown", key));
                };
        if (term.test == null) {
            chips.add(new Chip(token.raw, term.label, false));
            return name(token.text);
        }
        chips.add(new Chip(token.raw, token.negated ? tr("filter.not", term.label) : term.label, true));
        return term.test;
    }

    private static Predicate<Torrent> name(String word) {
        String needle = word.toLowerCase(Locale.ROOT);
        return t -> t.name().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Term state(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "downloading" -> column(Format.stateLabel(TorrentState.DOWNLOADING));
            case "seeding" -> column(Format.stateLabel(TorrentState.SEEDING));
            case "checking" -> column(Format.stateLabel(TorrentState.CHECKING));
            case "metadata" -> column(Format.stateLabel(TorrentState.FETCHING_METADATA));
            case "finished" -> column(Format.stateLabel(TorrentState.FINISHED));
            case "paused" -> column(Format.pausedLabel());
            case "error", "errored" -> column(Format.errorLabel());
            default -> Term.invalid(value.isEmpty() ? tr("filter.stateNeedsValue") : tr("filter.noState", value));
        };
    }

    /** The State column's own text, so the two cannot disagree on what outranks what. */
    private static Term column(String label) {
        return new Term(t -> Format.stateLabel(t).equals(label), tr("filter.stateIs", label));
    }

    private static Term category(String value) {
        if (value.isEmpty()) return Term.invalid(tr("filter.categoryNeedsName"));
        return new Term(t -> t.category().equalsIgnoreCase(value), tr("filter.category", value));
    }

    private static Term tag(String value) {
        if (value.isEmpty()) return Term.invalid(tr("filter.tagNeedsName"));
        return new Term(t -> t.tags().stream().anyMatch(value::equalsIgnoreCase), tr("filter.tag", value));
    }

    /** Any of its tracker hosts, or the one it last announced to (which a snapshot may name alone). */
    private static Term tracker(String value) {
        if (value.isEmpty()) return Term.invalid(tr("filter.trackerNeedsHost"));
        String needle = value.toLowerCase(Locale.ROOT);
        Predicate<String> has = host -> host.toLowerCase(Locale.ROOT).contains(needle);
        return new Term(
                t -> t.trackers().stream().anyMatch(has)
                        || (!t.tracker().isBlank() && has.test(Format.host(t.tracker()))),
                tr("filter.tracker", value));
    }

    private static Term hash(String value) {
        String prefix = value.toLowerCase(Locale.ROOT);
        if (!HEX.matcher(prefix).matches()) return Term.invalid(tr("filter.hashTakesHex"));
        return new Term(t -> t.id().toLowerCase(Locale.ROOT).startsWith(prefix), tr("filter.hash", prefix));
    }

    private static Term is(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "sequential" -> new Term(Torrent::sequential, tr("filter.sequential"));
            case "link", "links", "direct" -> new Term(Torrent::direct, tr("filter.link"));
            case "torrent", "torrents" -> new Term(t -> !t.direct(), tr("filter.torrent"));
            default -> Term.invalid(value.isEmpty() ? tr("filter.isNeedsValue") : tr("filter.noSuchFlag", value));
        };
    }

    // ---- comparisons

    private enum Op {
        LT("under"),
        LE("atMost"),
        GT("over"),
        GE("atLeast"),
        EQ("is");

        /** The catalogue key's last segment: {@code filter.op.<id>}. */
        private final String id;

        Op(String id) {
            this.id = id;
        }

        /** How the op reads in a ratio or size chip, in the UI language; read at call time. */
        String word() {
            return tr("filter.op." + id);
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
        if (!NUMBER.matcher(c.operand).matches()) return Term.invalid(tr("filter.ratioTakesNumber"));
        double bound = Double.parseDouble(c.operand);
        // Nothing moved either way shows as a dash, and counts as no ratio at all: 0.
        return new Term(
                t -> c.op.holds(Double.compare(Math.max(0, Format.ratioValue(t.uploaded(), t.downloaded())), bound)),
                tr("filter.ratio", c.op.word(), c.operand));
    }

    private static Term size(Comparison c) {
        Long bound = bytes(c.operand);
        if (bound == null) return Term.invalid(tr("filter.sizeTakesNumber"));
        return new Term(
                t -> c.op.holds(Long.compare(t.size(), bound)), tr("filter.size", c.op.word(), Bytes.human(bound)));
    }

    /** {@code uploaded>10g}: payload sent over the torrent's life, in the same units as {@code size}. */
    private static Term uploaded(Comparison c) {
        Long bound = bytes(c.operand);
        if (bound == null) return Term.invalid(tr("filter.uploadedTakesNumber"));
        return new Term(
                t -> c.op.holds(Long.compare(t.uploaded(), bound)),
                tr("filter.uploaded", c.op.word(), Bytes.human(bound)));
    }

    /** A byte count with k/m/g/t in binary units, as {@link Bytes} prints them; null if it does not read. */
    static Long bytes(String operand) {
        Matcher m = SIZE.matcher(operand.toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        int shift =
                switch (m.group(2)) {
                    case "k" -> 10;
                    case "m" -> 20;
                    case "g" -> 30;
                    case "t" -> 40;
                    default -> 0;
                };
        BigDecimal value = new BigDecimal(m.group(1))
                .multiply(BigDecimal.valueOf(1L << shift))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        return value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? null : value.longValue();
    }

    /**
     * {@code seeding>2d}: time spent finished over the torrent's life ({@link Torrent#seedingTime},
     * what a seeding-time limit measures), so a torrent that never finished has seeded for none.
     */
    private static Term seeding(Comparison c) {
        if (c.op == Op.EQ) return Term.invalid(tr("filter.seedingTakesComparison"));
        Span span = span(c.operand);
        if (span == null) return Term.invalid(tr("filter.seedingTakesNumber"));
        return new Term(
                t -> c.op.holds(Long.compare(t.seedingTime(), span.seconds())),
                tr("filter.seeding", c.op.word(), span.words()));
    }

    /**
     * {@code eta<30m}: the time left the ETA column shows. A torrent with none (not downloading,
     * or done) matches neither side; one that is stuck has an endless ETA, over any bound.
     */
    private static Term eta(Comparison c) {
        if (c.op == Op.EQ) return Term.invalid(tr("filter.etaTakesComparison"));
        Span span = span(c.operand);
        if (span == null) return Term.invalid(tr("filter.etaTakesNumber"));
        return new Term(
                t -> {
                    long eta = Format.etaSeconds(t);
                    return eta != Format.NO_ETA && c.op.holds(Long.compare(eta, span.seconds()));
                },
                tr("filter.eta", c.op.word(), span.words()));
    }

    /** {@code seeds<3}: the seeds it is connected to now, as the Seeds column counts them. */
    private static Term seeds(Comparison c) {
        if (!WHOLE.matcher(c.operand).matches()) return Term.invalid(tr("filter.seedsTakesWholeNumber"));
        int bound;
        try {
            bound = Integer.parseInt(c.operand);
        } catch (NumberFormatException e) {
            return Term.invalid(tr("filter.seedsTakesWholeNumber"));
        }
        return new Term(t -> c.op.holds(Integer.compare(t.seeds(), bound)), tr("filter.seeds", c.op.word(), bound));
    }

    /** A length of time: seconds, and the words for it in the UI language. */
    private record Span(long seconds, String words) {}

    /** {@code 30m}, {@code 6h}, {@code 2d}, {@code 3w}; null if it does not read or is too long. */
    private static Span span(String operand) {
        Matcher m = DURATION.matcher(operand.toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        String unit =
                switch (m.group(2)) {
                    case "m" -> "minutes";
                    case "h" -> "hours";
                    case "d" -> "days";
                    default -> "weeks";
                };
        long per =
                switch (unit) {
                    case "minutes" -> 60L;
                    case "hours" -> 3600L;
                    case "days" -> 86_400L;
                    default -> 7 * 86_400L;
                };
        try {
            long amount = Long.parseLong(m.group(1));
            return new Span(Math.multiplyExact(amount, per), tr("filter.span." + unit, amount));
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /**
     * {@code added<7d}: added less than seven days ago; {@code added>7d}: longer ago than that. A
     * torrent whose added time is unknown (0) matches neither.
     */
    private static Term added(Comparison c, long now) {
        if (c.op == Op.EQ) return Term.invalid(tr("filter.addedTakesComparison"));
        Matcher m = AGE.matcher(c.operand.toLowerCase(Locale.ROOT));
        if (!m.matches()) return Term.invalid(tr("filter.addedTakesNumber"));
        long amount;
        try {
            amount = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return Term.invalid(tr("filter.addedTooLongAgo"));
        }
        String unit =
                switch (m.group(2)) {
                    case "h" -> "hours";
                    case "d" -> "days";
                    default -> "weeks";
                };
        long seconds =
                switch (unit) {
                    case "hours" -> 3600L;
                    case "days" -> 86_400L;
                    default -> 7 * 86_400L;
                };
        long age;
        try {
            age = Math.multiplyExact(amount, seconds);
        } catch (ArithmeticException e) {
            return Term.invalid(tr("filter.addedTooLongAgo"));
        }
        String span = tr("filter.span." + unit, amount);
        String label =
                switch (c.op) {
                    case LT -> tr("filter.addedInLast", span);
                    case LE -> tr("filter.addedAtMost", span);
                    case GT -> tr("filter.addedMoreThan", span);
                    default -> tr("filter.addedAtLeast", span);
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
