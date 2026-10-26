package com.torrenta.search;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A date as an indexer writes it, into an instant: ISO ("2026-10-03T12:35:22Z", with an offset,
 * or a local date-time taken as UTC), RFC 1123 ("Sat, 18 Oct 2026 09:12:00 +0000"),
 * "2026-10-03" and "2026-10-03 12:35", epoch seconds or milliseconds, "Sep 13, 2026", and the
 * relative "2 days ago", "an hour ago", "yesterday", "just now" against a clock the caller
 * gives. Pure; null for anything else, never an exception.
 */
public final class DateText {

    private static final Pattern RELATIVE = Pattern.compile(
            "^\\s*(\\d+|an?)\\s*(second|sec|minute|min|hour|hr|day|week|month|year)s?\\s+ago\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final List<DateTimeFormatter> LOCAL = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT),
            DateTimeFormatter.ofPattern("uuuu/MM/dd", Locale.ROOT),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH));

    private DateText() {}

    /** The shapes above, in that order; {@code now} anchors the relative ones. */
    public static @Nullable Instant parse(@Nullable String text, Instant now) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        Instant relative = relative(t, now);
        if (relative != null) return relative;
        if (t.matches("[0-9]{9,13}")) {
            long n = Long.parseLong(t);
            return n > 99_999_999_999L ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
        }
        Instant iso = TorznabProvider.parseDate(t);
        if (iso != null) return iso;
        for (DateTimeFormatter f : LOCAL) {
            Instant at = local(f, t);
            if (at != null) return at;
        }
        return null;
    }

    /**
     * A {@link DateTimeFormatter} pattern of the definition's own ("dd.MM.uuuu HH:mm"), a time of
     * midnight and a zone of UTC filled in where the text has none; {@code format} being empty
     * means {@link #parse}.
     */
    public static @Nullable Instant parse(@Nullable String text, String format, Instant now) {
        if (format == null || format.isBlank()) return parse(text, now);
        if (text == null || text.isBlank()) return null;
        try {
            return local(DateTimeFormatter.ofPattern(format.trim(), Locale.ENGLISH), text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable Instant local(DateTimeFormatter f, String t) {
        try {
            TemporalAccessor parsed = f.parse(t);
            LocalDate date = parsed.query(TemporalQueries.localDate());
            if (date == null) return null;
            LocalTime time = parsed.query(TemporalQueries.localTime());
            ZoneOffset offset = parsed.query(TemporalQueries.offset());
            LocalDateTime dateTime = LocalDateTime.of(date, time == null ? LocalTime.MIDNIGHT : time);
            return dateTime.toInstant(offset == null ? ZoneOffset.UTC : offset);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static @Nullable Instant relative(String t, Instant now) {
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("just now") || lower.equals("now") || lower.equals("today")) return now;
        if (lower.equals("yesterday")) return now.minus(1, ChronoUnit.DAYS);
        Matcher m = RELATIVE.matcher(t);
        if (!m.matches()) return null;
        long n = m.group(1).matches("\\d+") ? Long.parseLong(m.group(1)) : 1;
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "second", "sec" -> now.minusSeconds(n);
            case "minute", "min" -> now.minus(n, ChronoUnit.MINUTES);
            case "hour", "hr" -> now.minus(n, ChronoUnit.HOURS);
            case "day" -> now.minus(n, ChronoUnit.DAYS);
            case "week" -> now.minus(n * 7, ChronoUnit.DAYS);
            case "month" ->
                ZonedDateTime.ofInstant(now, ZoneOffset.UTC).minusMonths(n).toInstant();
            case "year" ->
                ZonedDateTime.ofInstant(now, ZoneOffset.UTC).minusYears(n).toInstant();
            default -> null;
        };
    }
}
