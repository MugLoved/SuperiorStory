package com.mugloved.superiorstory.dialogue;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one duration primitive, in real (wall-clock) time only: {@code "45s"}, {@code "30m"}, {@code "2h"}, {@code "1d"},
 * or combined units such as {@code "1h30m"}. It keeps running while the player is offline.
 */
public final class Duration {
    public static final long MAX_MILLIS = 3650L * 86_400_000L;
    private static final Pattern WHOLE = Pattern.compile("(?:\\d+[dhms])+");
    private static final Pattern PART = Pattern.compile("(\\d+)([dhms])");
    private static final long[] UNIT_MILLIS = {86_400_000L, 3_600_000L, 60_000L, 1_000L};
    private static final String[] UNIT_KEYS = {"d", "h", "m", "s"};

    private Duration() {}

    public static long parse(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        if (!WHOLE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid duration \"" + text + "\" (use 45s, 30m, 2h, 1d, or 1h30m)");
        }
        long total = 0;
        Matcher matcher = PART.matcher(value);
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            long unit = UNIT_MILLIS[indexOf(matcher.group(2))];
            if (amount > MAX_MILLIS / unit) throw new IllegalArgumentException("Duration too long: " + text);
            total += amount * unit;
            if (total > MAX_MILLIS) throw new IllegalArgumentException("Duration too long: " + text);
        }
        if (total <= 0) throw new IllegalArgumentException("Duration must be above zero: " + text);
        return total;
    }

    /**
     * The two largest units left, such as "1d 4h", "2h 14m", or "45s". {@code unit} maps a unit key ({@code d h m s}) to
     * its localized suffix, so the wording comes from lang files.
     */
    public static String format(long millis, UnaryOperator<String> unit) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        long[] parts = {seconds / 86_400, seconds / 3_600 % 24, seconds / 60 % 60, seconds % 60};
        int first = 0;
        while (first < 3 && parts[first] == 0) first++;
        StringBuilder out = new StringBuilder().append(parts[first]).append(unit.apply(UNIT_KEYS[first]));
        if (first < 3 && parts[first + 1] > 0) out.append(' ').append(parts[first + 1]).append(unit.apply(UNIT_KEYS[first + 1]));
        return out.toString();
    }

    private static int indexOf(String key) {
        for (int i = 0; i < UNIT_KEYS.length; i++) if (UNIT_KEYS[i].equals(key)) return i;
        throw new IllegalArgumentException(key);
    }
}
