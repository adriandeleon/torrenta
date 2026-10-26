package com.torrenta.rss;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * One auto-download rule, qBittorrent's shape: which articles it wants (phrases or a regex that
 * the title must and must not contain, an {@link EpisodeFilter}, which feeds, how old is too
 * old), what it does with them (paused or not, a category, a save path), and what it has done
 * (the last match, the episodes it already took when the smart episode filter is on). Pure and
 * JavaFX-free; kept in {@code rss/rules.json} by the {@link RssStore}, this machine's.
 *
 * @param name the rule's name, unique among the rules; the list's key
 * @param enabled whether the engine runs it
 * @param mustContain phrases: alternatives separated by {@code |}, each a set of words that must
 *     all appear in the title, case-insensitively; or a regex when {@code useRegex} is on; blank
 *     matches every title
 * @param mustNotContain the same shape; a title matching it is left alone; blank excludes nothing
 * @param useRegex both expressions are regular expressions rather than phrases
 * @param episodeFilter {@link EpisodeFilter}'s syntax; blank for none
 * @param smartEpisodeFilter an episode this rule already downloaded is not downloaded again
 * @param feeds the ids of the feeds the rule reads; empty for every feed
 * @param ignoreDays an article published more than this many days ago is not downloaded; 0 for
 *     no limit
 * @param addPaused whether the torrent starts paused, or as the Add dialog's default says
 * @param category the category the torrent is filed under; blank for none
 * @param savePath the folder the torrent saves to; blank for the category's, else the default
 * @param lastMatch when the rule last added something; null for never
 * @param previouslyMatched the smart filter's memory, oldest first, at most
 *     {@link #MAX_PREVIOUSLY_MATCHED}
 */
public record Rule(
        String name,
        boolean enabled,
        String mustContain,
        String mustNotContain,
        boolean useRegex,
        String episodeFilter,
        boolean smartEpisodeFilter,
        List<String> feeds,
        int ignoreDays,
        AddPaused addPaused,
        String category,
        String savePath,
        @Nullable Instant lastMatch,
        List<String> previouslyMatched) {

    public static final int MAX_PREVIOUSLY_MATCHED = 1000;
    public static final int MAX_IGNORE_DAYS = 3650;

    /** Whether a torrent the rule adds starts paused. */
    public enum AddPaused {
        DEFAULT("default"),
        YES("yes"),
        NO("no");

        private final String label;

        AddPaused(String label) {
            this.label = label;
        }

        public String label() {
            return tr("rule.addPaused." + label);
        }

        /** The file's word, {@code default}, {@code yes} or {@code no}; anything else is the default. */
        public static AddPaused parse(String text) {
            if (text == null) return DEFAULT;
            return switch (text.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "yes", "true", "paused" -> YES;
                case "no", "false", "started" -> NO;
                default -> DEFAULT;
            };
        }

        public String fileWord() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public Rule {
        name = name == null ? "" : name.trim();
        mustContain = mustContain == null ? "" : mustContain.trim();
        mustNotContain = mustNotContain == null ? "" : mustNotContain.trim();
        episodeFilter = episodeFilter == null ? "" : episodeFilter.trim();
        feeds = distinct(feeds);
        ignoreDays = Math.clamp(ignoreDays, 0, MAX_IGNORE_DAYS);
        addPaused = addPaused == null ? AddPaused.DEFAULT : addPaused;
        category = category == null ? "" : category.trim();
        savePath = savePath == null ? "" : savePath.trim();
        previouslyMatched = cap(distinct(previouslyMatched));
    }

    /** A new rule with every field at its default: enabled, every feed, nothing matched yet. */
    public static Rule blank(String name) {
        return new Rule(name, true, "", "", false, "", false, List.of(), 0, AddPaused.DEFAULT, "", "", null, List.of());
    }

    /** Whether the rule reads this feed: every feed when none is named. */
    public boolean appliesTo(String feedId) {
        return feeds.isEmpty() || feeds.contains(feedId);
    }

    public Rule withName(String newName) {
        return new Rule(
                newName,
                enabled,
                mustContain,
                mustNotContain,
                useRegex,
                episodeFilter,
                smartEpisodeFilter,
                feeds,
                ignoreDays,
                addPaused,
                category,
                savePath,
                lastMatch,
                previouslyMatched);
    }

    public Rule withEnabled(boolean on) {
        return new Rule(
                name,
                on,
                mustContain,
                mustNotContain,
                useRegex,
                episodeFilter,
                smartEpisodeFilter,
                feeds,
                ignoreDays,
                addPaused,
                category,
                savePath,
                lastMatch,
                previouslyMatched);
    }

    /** The same rule under another name, with nothing matched yet: what Duplicate makes. */
    public Rule duplicate(String newName) {
        return new Rule(
                newName,
                enabled,
                mustContain,
                mustNotContain,
                useRegex,
                episodeFilter,
                smartEpisodeFilter,
                feeds,
                ignoreDays,
                addPaused,
                category,
                savePath,
                null,
                List.of());
    }

    /**
     * After a download: the match time, and the episode key remembered (a blank key is not); the
     * oldest memories go once there are more than {@link #MAX_PREVIOUSLY_MATCHED}.
     */
    public Rule recordMatch(String episodeKey, Instant at) {
        List<String> memory = previouslyMatched;
        if (episodeKey != null && !episodeKey.isBlank() && !previouslyMatched.contains(episodeKey)) {
            memory = new ArrayList<>(previouslyMatched);
            memory.add(episodeKey);
        }
        return new Rule(
                name,
                enabled,
                mustContain,
                mustNotContain,
                useRegex,
                episodeFilter,
                smartEpisodeFilter,
                feeds,
                ignoreDays,
                addPaused,
                category,
                savePath,
                at,
                memory);
    }

    private static List<String> distinct(Collection<String> items) {
        if (items == null || items.isEmpty()) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : items) if (s != null && !s.isBlank()) set.add(s.trim());
        return List.copyOf(set);
    }

    private static List<String> cap(List<String> items) {
        return items.size() <= MAX_PREVIOUSLY_MATCHED
                ? items
                : List.copyOf(items.subList(items.size() - MAX_PREVIOUSLY_MATCHED, items.size()));
    }
}
