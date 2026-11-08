package jobhunter;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns pay text such as "$40k - $60k", "R10,000 / month" or "$25/hr" into an hourly USD range. */
final class Pay {
    private Pay() {}

    private static final String CUR = "US\\$|USD|\\$|€|EUR|£|GBP|ZAR|CAD|AUD|R(?=\\s?\\d)";
    private static final String AMT = "\\d{1,3}(?:[,\\s]\\d{3})+|\\d+(?:\\.\\d+)?";
    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?<![A-Za-z])(" + CUR + ")\\s?(" + AMT + ")\\s?(k)?"
                    + "(?:\\s?(?:-|–|—|to)\\s?(?:" + CUR + ")?\\s?(" + AMT + ")\\s?(k)?)?");

    private static final Pattern U_HOUR = Pattern.compile("(?i)^\\s*(?:/|per\\s+|a\\s+|an\\s+)?\\s*(?:hour(?:ly)?|hr|h)\\b");
    private static final Pattern U_DAY = Pattern.compile("(?i)^\\s*(?:/|per\\s+|a\\s+)?\\s*(?:day|daily)\\b");
    private static final Pattern U_WEEK = Pattern.compile("(?i)^\\s*(?:/|per\\s+|a\\s+)?\\s*(?:week(?:ly)?|wk)\\b");
    private static final Pattern U_MONTH = Pattern.compile("(?i)^\\s*(?:/|per\\s+|a\\s+)?\\s*(?:month(?:ly)?|mo|mth)\\b");
    private static final Pattern U_YEAR = Pattern.compile("(?i)^\\s*(?:/|per\\s+|a\\s+)?\\s*(?:year(?:ly)?|yr|annum|annual(?:ly)?|p\\.a\\.)");

    /** Returns {minHourlyUsd, maxHourlyUsd}, or null when no usable amount is found. */
    static double[] hourlyUsd(String text, Map<String, Double> fx) {
        if (text == null || text.isBlank()) return null;
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            double a = amount(m.group(2), m.group(3));
            Double b = m.group(4) != null ? amount(m.group(4), m.group(5)) : null;
            // "40-60k": the k belongs to both numbers
            if (b != null && m.group(3) == null && m.group(5) != null && a < 1000) a *= 1000;
            double lo = b != null ? Math.min(a, b) : a;
            double hi = b != null ? Math.max(a, b) : a;
            String tail = text.substring(m.end(), Math.min(text.length(), m.end() + 24));
            Double div = divisor(tail);
            if (div == null) {
                if (hi >= 5000) div = 2080.0;       // a bare big number is almost always a yearly salary
                else if (hi <= 300) div = 1.0;      // a bare small number is almost always hourly
                else continue;                      // ambiguous, ignore
            }
            double rate = fx.getOrDefault(currency(m.group(1)), 1.0);
            return new double[]{lo * rate / div, hi * rate / div};
        }
        return null;
    }

    /** Yearly salary in the given currency to hourly USD. */
    static Double annualToHourly(Double annual, String currency, Map<String, Double> fx) {
        return toHourly(annual, "yearly", currency, fx);
    }

    /**
     * An amount per period ("hourly", "daily", "weekly", "monthly", anything else = yearly) in a currency, as hourly USD.
     * Returns null when the currency is not in the fx table: better to say "pay unknown" than to guess wrongly.
     */
    static Double toHourly(Double amount, String period, String currency, Map<String, Double> fx) {
        if (amount == null || amount <= 0) return null;
        String cur = currency == null || currency.isBlank() ? "USD" : currency.toUpperCase(Locale.ROOT);
        Double rate = fx.get(cur);
        if (rate == null) return null;
        String p = period == null ? "" : period.toLowerCase(Locale.ROOT);
        double div = p.startsWith("hour") ? 1.0
                : p.startsWith("day") || p.startsWith("daily") ? 8.0
                : p.startsWith("week") ? 40.0
                : p.startsWith("month") ? 173.33
                : 2080.0;
        return amount * rate / div;
    }

    private static double amount(String digits, String k) {
        double v = Double.parseDouble(digits.replaceAll("[,\\s]", ""));
        return k != null ? v * 1000 : v;
    }

    private static Double divisor(String tail) {
        if (U_HOUR.matcher(tail).find()) return 1.0;
        if (U_DAY.matcher(tail).find()) return 8.0;
        if (U_WEEK.matcher(tail).find()) return 40.0;
        if (U_MONTH.matcher(tail).find()) return 173.33;
        if (U_YEAR.matcher(tail).find()) return 2080.0;
        return null;
    }

    private static String currency(String sym) {
        String s = sym.toUpperCase(Locale.ROOT);
        return switch (s) {
            case "$", "US$", "USD" -> "USD";
            case "€", "EUR" -> "EUR";
            case "£", "GBP" -> "GBP";
            case "R", "ZAR" -> "ZAR";
            default -> s;
        };
    }
}
