package skyblockshoppinglist;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Small helpers for item counts. */
public final class Counts {
    private Counts() {}

    /** Per item, how much {@code current} differs from {@code previous}; items that are gone count negative. */
    public static Map<String, Integer> diff(Map<String, Integer> previous, Map<String, Integer> current) {
        Map<String, Integer> changes = new HashMap<>();
        current.forEach((name, count) -> {
            int delta = count - previous.getOrDefault(name, 0);
            if (delta != 0) changes.put(name, delta);
        });
        previous.forEach((name, count) -> {
            if (!current.containsKey(name) && count != 0) changes.put(name, -count);
        });
        return changes;
    }

    /**
     * {@code a × b}, held at the int range instead of wrapping: recipe amounts times big coin costs (25m coins for
     * each of 100 pet upgrades) would otherwise turn negative.
     */
    public static int times(int a, int b) {
        return clamp((long) a * b);
    }

    /** {@code a + b}, held at the int range like {@link #times}, so a sum with a held product can't wrap either. */
    public static int plus(int a, int b) {
        return clamp((long) a + b);
    }

    /** {@code a - b}, held at the int range like {@link #times}. */
    public static int minus(int a, int b) {
        return clamp((long) a - b);
    }

    private static int clamp(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    /** "1,234" -> 1234; null when it isn't a number. */
    public static Integer parse(String text) {
        try {
            return Integer.parseInt(text.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 999 -> "999", 5120 -> "5.1k", 512000 -> "512k", 500000000 -> "500m", 1500000000 -> "1.5b". */
    static String shortNumber(long value) {
        long abs = Math.abs(value);
        if (abs < 1000) return Long.toString(value);
        String[] units = {"k", "m", "b", "t"};
        double scaled = value;
        int unit = -1;
        while (Math.abs(scaled) >= 1000 && unit < units.length - 1) {
            scaled /= 1000;
            unit++;
        }
        // One decimal below 100 (5.1k, 12.5m), none above (512k); always rounded towards zero.
        String text = Math.abs(scaled) < 100 ? String.format(Locale.ROOT, "%.1f", (long) (scaled * 10) / 10.0)
            : Long.toString((long) scaled);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text + units[unit];
    }
}
