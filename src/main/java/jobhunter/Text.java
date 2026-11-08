package jobhunter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text helpers: HTML to plain text, normalising, hashing, e-mail detection. */
final class Text {
    private Text() {}

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUM_ENT = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Pattern LEFTOVER_TAG = Pattern.compile("(?i)<\\s*/?\\s*(p|br|div|li|ul|ol|strong|b|em|a|span|h[1-6])\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    static String stripHtml(String html) {
        if (html == null) return "";
        String s = oneStrip(html);
        // Some feeds deliver HTML that is itself entity-escaped; strip a second time when tags reappear.
        if (LEFTOVER_TAG.matcher(s).find()) s = oneStrip(s);
        return s;
    }

    private static String oneStrip(String html) {
        String s = html
                .replaceAll("(?i)<\\s*br\\s*/?>", "\n")
                .replaceAll("(?i)</\\s*(p|div|li|h[1-6]|tr)\\s*>", "\n")
                .replaceAll("(?i)<\\s*li[^>]*>", "- ");
        s = TAG.matcher(s).replaceAll(" ");
        s = unescape(s);
        s = s.replace(' ', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    static String unescape(String s) {
        if (s == null) return "";
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&rsquo;", "'").replace("&lsquo;", "'")
                .replace("&ndash;", "-").replace("&mdash;", "-").replace("&hellip;", "...").replace("&amp;", "&");
        Matcher m = NUM_ENT.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep;
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                rep = new String(Character.toChars(cp));
            } catch (IllegalArgumentException e) {
                rep = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n).trim() + "...";
    }

    static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first usable e-mail address in the text, or null. */
    static String firstEmail(String text) {
        if (text == null) return null;
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            String e = m.group().toLowerCase(Locale.ROOT);
            if (e.startsWith("noreply") || e.startsWith("no-reply") || e.contains("example.com") || e.contains("sentry")
                    || e.endsWith(".png") || e.endsWith(".jpg")) continue;
            return m.group();
        }
        return null;
    }

    static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
