package com.torrenta.ui;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.torrenta.search.SearchCategory;
import com.torrenta.search.SearchResult;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Search tab's results filter, read as a query: a test over {@link SearchResult} and the chips
 * that show it. The omnibox's language ({@link FilterQuery}), its tokens, quotes, minus and
 * comparisons, over the results table's columns instead of the torrent list's.
 *
 * <p>Terms are separated by spaces and must all hold. A plain word is a case-insensitive substring
 * of the name. A term of the form {@code key:value} or {@code key<value} narrows on a column:
 *
 * <ul>
 *   <li>{@code provider:} part of the provider's name, ignoring case
 *   <li>{@code cat:} / {@code category:} the category the indexer files it under, by its English
 *       name or its chip's label ({@code cat:movies}); a result whose category is not known has none
 *   <li>{@code size} with {@code < <= > >= =} and k/m/g/t in binary units, as {@link FilterQuery}
 *   <li>{@code seeds} and {@code leechers}, whole numbers
 *   <li>{@code date} with a day ({@code date>=2024-01-01}, {@code date:2024-08-30} for that day,
 *       in the zone given) or an age with m/h/d/w ({@code date<7d}, published within the last
 *       seven days, as the torrent list's {@code added<7d})
 * </ul>
 *
 * <p>A size, a count or a date the indexer did not give matches no comparison, so {@code
 * seeds>=10} hides the results whose seeds are unknown; with a minus in front it keeps them. A
 * range is two terms: {@code size>1g size<4g}. Nothing throws: a term that does not read is
 * matched against the name as typed and its chip is marked invalid.
 *
 * <p>Pure and immutable; ages are measured from the {@code now} passed to {@link #parse}.
 */
public final class SearchFilter {

    /** Matches everything. */
    public static final SearchFilter EMPTY = new SearchFilter(List.of(), List.of());

    private static final Pattern WHOLE = Pattern.compile("\\d+");
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final List<Predicate<SearchResult>> tests;
    private final List<FilterQuery.Chip> chips;

    private SearchFilter(List<Predicate<SearchResult>> tests, List<FilterQuery.Chip> chips) {
        this.tests = List.copyOf(tests);
        this.chips = List.copyOf(chips);
    }

    /** Reads {@code text}; {@code now} is what an age counts back from, {@code zone} where a day is. */
    public static SearchFilter parse(String text, Instant now, ZoneId zone) {
        if (text == null || text.isBlank()) return EMPTY;
        List<Predicate<SearchResult>> tests = new ArrayList<>();
        List<FilterQuery.Chip> chips = new ArrayList<>();
        for (FilterQuery.Token token : FilterQuery.tokens(text)) {
            Predicate<SearchResult> test = term(token, now, zone, chips);
            tests.add(token.negated() ? test.negate() : test);
        }
        return new SearchFilter(tests, chips);
    }

    public boolean matches(SearchResult r) {
        for (Predicate<SearchResult> test : tests) if (!test.test(r)) return false;
        return true;
    }

    /** The {@code key:value} terms in order, valid or not; plain words have none. */
    public List<FilterQuery.Chip> chips() {
        return chips;
    }

    public boolean isEmpty() {
        return tests.isEmpty();
    }

    /**
     * {@code text} with the term {@code term} as typed taken out, for a click on its chip: the
     * first occurrence standing alone between spaces, the spaces around it closed up.
     */
    public static String without(String text, String term) {
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (FilterQuery.Token token : FilterQuery.tokens(text)) {
            if (!removed && token.raw().equals(term)) removed = true;
            else kept.add(token.raw());
        }
        return String.join(" ", kept);
    }

    /**
     * {@code text} with {@code term} added at the end, unless a term just like it is there already:
     * what "Show Only This Provider" does to the box.
     */
    public static String with(String text, String term) {
        String trimmed = text == null ? "" : text.strip();
        for (FilterQuery.Token token : FilterQuery.tokens(trimmed)) {
            if (token.raw().equalsIgnoreCase(term)) return trimmed;
        }
        return trimmed.isEmpty() ? term : trimmed + " " + term;
    }

    /** The term that picks a provider by name, quoted when the name has a space. */
    public static String providerTerm(String provider) {
        return "provider:" + quoted(provider);
    }

    /** The term that picks a category, by its English name so it reads the same in every language. */
    public static String categoryTerm(SearchCategory category) {
        return "cat:" + category.id().toLowerCase(Locale.ROOT);
    }

    private static String quoted(String value) {
        return value.chars().anyMatch(Character::isWhitespace) ? "\"" + value.replace("\"", "") + "\"" : value;
    }

    // ---- terms

    private static Predicate<SearchResult> term(
            FilterQuery.Token token, Instant now, ZoneId zone, List<FilterQuery.Chip> chips) {
        Matcher m = token.quotedKey() ? null : FilterQuery.KEYED.matcher(token.text());
        if (m == null || !m.matches()) return name(token.text());
        String key = m.group(1).toLowerCase(Locale.ROOT);
        String op = m.group(2);
        String value = m.group(3).trim();
        Term term =
                switch (key) {
                    case "provider" -> op.equals(":") ? provider(value) : Term.invalid(tr("filter.takesColon", key));
                    case "cat", "category" ->
                        op.equals(":") ? category(value) : Term.invalid(tr("filter.takesColon", key));
                    case "size" -> size(FilterQuery.comparison(op, value));
                    case "seeds" ->
                        count(
                                FilterQuery.comparison(op, value),
                                SearchResult::seeds,
                                "filter.seeds",
                                "filter.seedsTakesWholeNumber");
                    case "leechers" ->
                        count(
                                FilterQuery.comparison(op, value),
                                SearchResult::leechers,
                                "filter.leechers",
                                "filter.leechersTakesWholeNumber");
                    case "date" -> date(FilterQuery.comparison(op, value), now, zone);
                    default -> Term.invalid(tr("filter.unknown", key));
                };
        if (term.test == null) {
            chips.add(new FilterQuery.Chip(token.raw(), term.label, false));
            return name(token.text());
        }
        chips.add(new FilterQuery.Chip(token.raw(), token.negated() ? tr("filter.not", term.label) : term.label, true));
        return term.test;
    }

    private static Predicate<SearchResult> name(String word) {
        String needle = word.toLowerCase(Locale.ROOT);
        return r -> r.name().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Term provider(String value) {
        if (value.isEmpty()) return Term.invalid(tr("filter.providerNeedsName"));
        String needle = value.toLowerCase(Locale.ROOT);
        return new Term(r -> r.provider().toLowerCase(Locale.ROOT).contains(needle), tr("filter.provider", value));
    }

    private static Term category(String value) {
        if (value.isEmpty()) return Term.invalid(tr("filter.categoryNeedsName"));
        SearchCategory c = SearchCategory.named(value);
        if (c == null) return Term.invalid(tr("filter.noCategory", value));
        return new Term(r -> r.category() == c, tr("filter.category", c.label()));
    }

    private static Term size(FilterQuery.Comparison c) {
        Long bound = FilterQuery.bytes(c.operand());
        if (bound == null) return Term.invalid(tr("filter.sizeTakesNumber"));
        return new Term(
                r -> r.size() >= 0 && c.op().holds(Long.compare(r.size(), bound)),
                tr("filter.size", c.op().word(), Bytes.human(bound)));
    }

    private static Term count(
            FilterQuery.Comparison c, java.util.function.ToIntFunction<SearchResult> value, String label, String why) {
        if (!WHOLE.matcher(c.operand()).matches()) return Term.invalid(tr(why));
        int bound;
        try {
            bound = Integer.parseInt(c.operand());
        } catch (NumberFormatException e) {
            return Term.invalid(tr(why));
        }
        return new Term(
                r -> {
                    int n = value.applyAsInt(r);
                    return n >= 0 && c.op().holds(Integer.compare(n, bound));
                },
                tr(label, c.op().word(), bound));
    }

    /** A day ({@code 2024-08-30}) or an age ({@code 7d}); see the class comment. */
    private static Term date(FilterQuery.Comparison c, Instant now, ZoneId zone) {
        if (DAY.matcher(c.operand()).matches()) return day(c, zone);
        if (c.op() == FilterQuery.Op.EQ) return Term.invalid(tr("filter.dateTakes"));
        FilterQuery.Span span = FilterQuery.span(c.operand());
        if (span == null) return Term.invalid(tr("filter.dateTakes"));
        String label =
                switch (c.op()) {
                    case LT -> tr("filter.dateInLast", span.words());
                    case LE -> tr("filter.dateAtMost", span.words());
                    case GT -> tr("filter.dateMoreThan", span.words());
                    default -> tr("filter.dateAtLeast", span.words());
                };
        // Ages, as added<7d: the newer the result, the smaller its age.
        return new Term(
                r -> r.published() != null
                        && c.op().holds(Long.compare(
                                now.getEpochSecond() - r.published().getEpochSecond(), span.seconds())),
                label);
    }

    private static Term day(FilterQuery.Comparison c, ZoneId zone) {
        LocalDate day;
        try {
            day = LocalDate.parse(c.operand());
        } catch (DateTimeException e) {
            return Term.invalid(tr("filter.dateTakes"));
        }
        String label =
                switch (c.op()) {
                    case LT -> tr("filter.dateBefore", day.toString());
                    case LE -> tr("filter.dateOnOrBefore", day.toString());
                    case GT -> tr("filter.dateAfter", day.toString());
                    case GE -> tr("filter.dateOnOrAfter", day.toString());
                    case EQ -> tr("filter.dateOn", day.toString());
                };
        // The result's own day where the person is: published at 23:30 UTC is the next day in Tokyo.
        return new Term(
                r -> r.published() != null
                        && c.op().holds(LocalDate.ofInstant(r.published(), zone).compareTo(day)),
                label);
    }

    /** A term read: a test and its label, or no test and what is wrong. */
    private record Term(Predicate<SearchResult> test, String label) {
        static Term invalid(String why) {
            return new Term(null, why);
        }
    }
}
