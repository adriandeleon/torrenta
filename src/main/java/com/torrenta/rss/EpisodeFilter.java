package com.torrenta.rss;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.torrenta.i18n.Messages.tr;

/**
 * qBittorrent's episode filter: terms separated by {@code ;}, each {@code 1x2} (one episode),
 * {@code 1x2-5} (a range) or {@code 1x2-} (from episode 2 of season 1 on); a trailing {@code ;}
 * and spaces are allowed. A title matches when the episode found in it ({@code S01E02},
 * {@code 1x02}, {@code Season 1 Episode 2}, in the common spellings) falls in a term; a title
 * without an episode matches no filter. Pure.
 */
public final class EpisodeFilter {

    /** A season and an episode read off a title. */
    public record Episode(int season, int episode) {
        /** The smart filter's spelling, {@code 1x2}: leading zeros gone, so S01E02 and 1x2 agree. */
        public String key() {
            return season + "x" + episode;
        }
    }

    /** One term: season, first episode, last episode ({@link Integer#MAX_VALUE} for open-ended). */
    public record Term(int season, int from, int to) {
        boolean matches(Episode e) {
            return e.season() == season && e.episode() >= from && e.episode() <= to;
        }
    }

    public static final EpisodeFilter NONE = new EpisodeFilter(List.of());

    /** The help line under the field, in the interface language. */
    public static String help() {
        return tr("dialog.rule.episodeHelp");
    }

    private static final Pattern TERM = Pattern.compile("(?i)(\\d{1,4})x(\\d{1,4})(?:-(\\d{1,4})?)?");

    /** Season x Episode y, any case, dots or underscores for spaces, an optional comma. */
    private static final Pattern WORDS =
            Pattern.compile("(?i)(?<![a-z0-9])season[ ._-]*(\\d{1,4})[ ._,-]*(?:episode|ep)[ ._-]*(\\d{1,4})(?![0-9])");

    /** S01E02, s1e2, S01.E02, S01-E02; a second E for a double episode is left alone. */
    private static final Pattern SXXEYY = Pattern.compile("(?i)(?<![a-z0-9])s(\\d{1,4})[ ._-]?e(\\d{1,4})(?![0-9])");

    /** 1x02; seasons of at most two digits and episodes of at most three, so 1920x1080 is a resolution. */
    private static final Pattern NXM = Pattern.compile("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{1,3})(?![a-z0-9])");

    private final List<Term> terms;

    private EpisodeFilter(List<Term> terms) {
        this.terms = List.copyOf(terms);
    }

    public List<Term> terms() {
        return terms;
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /**
     * Reads the filter; blank is {@link #NONE}.
     *
     * @throws IllegalArgumentException with the term at fault and what was expected
     */
    public static EpisodeFilter parse(String text) {
        if (text == null || text.isBlank()) return NONE;
        List<Term> out = new ArrayList<>();
        for (String piece : text.split(";")) {
            String term = piece.trim();
            if (term.isEmpty()) continue;
            Matcher m = TERM.matcher(term);
            if (!m.matches()) {
                throw new IllegalArgumentException(tr("rss.episodes.notATerm", term));
            }
            int season = Integer.parseInt(m.group(1));
            int from = Integer.parseInt(m.group(2));
            int to;
            if (m.group(3) != null) {
                to = Integer.parseInt(m.group(3));
                if (to < from) throw new IllegalArgumentException(tr("rss.episodes.backwards", term));
            } else if (term.endsWith("-")) {
                to = Integer.MAX_VALUE;
            } else {
                to = from;
            }
            out.add(new Term(season, from, to));
        }
        if (out.isEmpty()) throw new IllegalArgumentException(tr("rss.episodes.onlySeparators"));
        return new EpisodeFilter(out);
    }

    /** Why {@code text} is not a filter, in a sentence; empty when it is (or is blank). */
    public static Optional<String> problem(String text) {
        try {
            parse(text);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** Whether the title names an episode that one of the terms covers; an empty filter matches nothing. */
    public boolean matches(String title) {
        Optional<Episode> e = find(title);
        return e.isPresent() && matches(e.get());
    }

    public boolean matches(Episode e) {
        for (Term t : terms) if (t.matches(e)) return true;
        return false;
    }

    /** The first episode spelled in the title, in any of the forms; empty when there is none. */
    public static Optional<Episode> find(String title) {
        if (title == null || title.isEmpty()) return Optional.empty();
        Episode best = null;
        int at = Integer.MAX_VALUE;
        for (Pattern p : List.of(WORDS, SXXEYY, NXM)) {
            Matcher m = p.matcher(title);
            if (m.find() && m.start() < at) {
                at = m.start();
                best = new Episode(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
        }
        return Optional.ofNullable(best);
    }

    /** Where the episode token starts in the title, or -1: what "create rule from title" cuts at. */
    public static int tokenStart(String title) {
        if (title == null || title.isEmpty()) return -1;
        int at = -1;
        for (Pattern p : List.of(WORDS, SXXEYY, NXM)) {
            Matcher m = p.matcher(title);
            if (m.find() && (at < 0 || m.start() < at)) at = m.start();
        }
        return at;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Term t : terms) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(t.season()).append('x').append(t.from());
            if (t.to() == Integer.MAX_VALUE) sb.append('-');
            else if (t.to() != t.from()) sb.append('-').append(t.to());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
