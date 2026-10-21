package com.torrenta.rss;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Whether a rule wants an article: the feed, the must-contain and must-not-contain expressions
 * against the title, the episode filter, the age, and the smart filter's memory, in that order.
 * Whether the rule is enabled is the engine's business, not the matcher's: the dialog previews a
 * rule that is off. An article without a torrent never matches; there is nothing to download.
 * Pure; a rule is compiled once and asked about many articles.
 */
public final class RuleMatcher {

    /** A rule that wants an article; {@code episodeKey} is what the smart filter remembers. */
    public record Match(Rule rule, Article article, String episodeKey) {}

    /** One phrase alternative: the words that must all appear, lower-cased. */
    private record Phrase(List<String> words) {
        boolean in(String lowerTitle) {
            for (String w : words) if (!lowerTitle.contains(w)) return false;
            return true;
        }
    }

    private final Rule rule;
    private final Pattern mustPattern;
    private final Pattern mustNotPattern;
    private final List<Phrase> mustPhrases;
    private final List<Phrase> mustNotPhrases;
    private final EpisodeFilter episodes;

    private RuleMatcher(Rule rule) {
        this.rule = rule;
        if (rule.useRegex()) {
            mustPattern = rule.mustContain().isEmpty() ? null : regex(rule.mustContain());
            mustNotPattern = rule.mustNotContain().isEmpty() ? null : regex(rule.mustNotContain());
            mustPhrases = List.of();
            mustNotPhrases = List.of();
        } else {
            mustPattern = null;
            mustNotPattern = null;
            mustPhrases = phrases(rule.mustContain());
            mustNotPhrases = phrases(rule.mustNotContain());
        }
        episodes = EpisodeFilter.parse(rule.episodeFilter());
    }

    /**
     * Compiles the rule.
     *
     * @throws IllegalArgumentException when {@link #problem(Rule)} would say why
     */
    public static RuleMatcher compile(Rule rule) {
        problem(rule).ifPresent(p -> {
            throw new IllegalArgumentException(p);
        });
        return new RuleMatcher(rule);
    }

    public Rule rule() {
        return rule;
    }

    /** Why the rule cannot be evaluated, in a sentence; empty when it can. The dialog's rule. */
    public static Optional<String> problem(Rule rule) {
        if (rule.name().isEmpty()) return Optional.of("a name is needed");
        if (rule.useRegex()) {
            Optional<String> bad = regexProblem(rule.mustContain(), "must contain");
            if (bad.isPresent()) return bad;
            bad = regexProblem(rule.mustNotContain(), "must not contain");
            if (bad.isPresent()) return bad;
        }
        return EpisodeFilter.problem(rule.episodeFilter()).map(p -> "episode filter: " + p);
    }

    private static Optional<String> regexProblem(String expression, String field) {
        if (expression.isEmpty()) return Optional.empty();
        try {
            regex(expression);
            return Optional.empty();
        } catch (PatternSyntaxException e) {
            return Optional.of(field + " is not a regular expression: " + e.getDescription());
        }
    }

    /** The one-off form: compiles and asks; null when the rule has a problem or does not want the article. */
    public static Match matches(Rule rule, Article article, Instant now) {
        if (problem(rule).isPresent()) return null;
        return new RuleMatcher(rule).match(article, now);
    }

    /** The match, or null. {@code now} is for the age rule; a test passes a fixed instant. */
    public Match match(Article article, Instant now) {
        if (!article.hasTorrent()) return null;
        if (!rule.appliesTo(article.feedId())) return null;
        String title = article.title();
        if (!wanted(title)) return null;
        if (!episodes.isEmpty() && !episodes.matches(title)) return null;
        if (rule.ignoreDays() > 0
                && article.published() != null
                && article.published().isBefore(now.minus(rule.ignoreDays(), ChronoUnit.DAYS))) return null;
        String key = episodeKey(title);
        if (rule.smartEpisodeFilter() && rule.previouslyMatched().contains(key)) return null;
        return new Match(rule, article, key);
    }

    /** The must-contain and must-not-contain expressions alone, for the dialog's "why not" line. */
    public boolean wanted(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (rule.useRegex()) {
            if (mustPattern != null && !mustPattern.matcher(title).find()) return false;
            return mustNotPattern == null || !mustNotPattern.matcher(title).find();
        }
        if (!mustPhrases.isEmpty() && mustPhrases.stream().noneMatch(p -> p.in(lower))) return false;
        return mustNotPhrases.stream().noneMatch(p -> p.in(lower));
    }

    /**
     * What the smart filter remembers an article by: its episode as {@code 1x2}, else the whole
     * title lower-cased with its spacing collapsed, so the same release listed twice is one.
     */
    public static String episodeKey(String title) {
        Optional<EpisodeFilter.Episode> e = EpisodeFilter.find(title);
        if (e.isPresent()) return e.get().key();
        return title.toLowerCase(Locale.ROOT).replaceAll("[\\s._-]+", " ").trim();
    }

    private static Pattern regex(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** {@code a b|c} is two alternatives: the words a and b, or the word c; blank is none. */
    static List<Phrase> phrases(String expression) {
        List<Phrase> out = new ArrayList<>();
        for (String alternative : expression.split("\\|")) {
            List<String> words = new ArrayList<>();
            for (String w : alternative.trim().split("\\s+")) if (!w.isEmpty()) words.add(w.toLowerCase(Locale.ROOT));
            if (!words.isEmpty()) out.add(new Phrase(words));
        }
        return out;
    }
}
