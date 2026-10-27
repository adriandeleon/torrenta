package com.torrenta.search;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A size as an indexer writes it, into bytes: "1.4 GiB", "700 MB", "1,234,567", "3.2G",
 * "96504312 bytes". Pure; {@code -1} for anything that is not a size, never an exception.
 *
 * <p>Both "MB" and "MiB" mean 1024-based: every torrent site rounds to the binary unit whatever
 * letter it prints, and a decimal reading would only be wrong by a different amount.
 */
public final class SizeText {

    private static final Pattern SIZE = Pattern.compile(
            "^\\s*([0-9][0-9,.' ]*?)\\s*(bytes?|b|kib|kb|k|mib|mb|m|gib|gb|g|tib|tb|t|pib|pb|p)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private SizeText() {}

    public static long parse(@Nullable String text) {
        if (text == null) return -1;
        Matcher m =
                SIZE.matcher(text.replace('\u00a0', ' ').replace('\u202f', ' ').trim());
        if (!m.matches()) return -1;
        String number = m.group(1).replace(" ", "").replace("'", "");
        // "1,234,567" groups thousands with commas; "1,4 GB" is a decimal comma. A comma followed
        // by exactly three digits to the end (repeatedly) is a group separator, else a decimal.
        if (number.matches("[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?")) number = number.replace(",", "");
        else number = number.replace(',', '.');
        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) return -1;
        String unit = m.group(2) == null ? "b" : m.group(2).toLowerCase(Locale.ROOT);
        int power =
                switch (unit.charAt(0)) {
                    case 'k' -> 1;
                    case 'm' -> 2;
                    case 'g' -> 3;
                    case 't' -> 4;
                    case 'p' -> 5;
                    default -> 0;
                };
        double bytes = value * Math.pow(1024, power);
        if (bytes > Long.MAX_VALUE) return -1;
        return Math.round(bytes);
    }

    /** "1,234" or "1 234" or "12" into a count; {@code -1} when there is no number in it. */
    public static int count(@Nullable String text) {
        if (text == null) return -1;
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) return -1;
        return Integer.parseInt(digits);
    }
}
