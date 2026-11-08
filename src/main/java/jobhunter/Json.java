package jobhunter;

import java.util.*;

/**
 * Tiny dependency-free JSON reader and writer.
 * Objects become LinkedHashMap, arrays become ArrayList, numbers become Long or Double.
 */
public final class Json {
    private Json() {}

    // ---------- reading ----------

    public static Object parse(String text) {
        P p = new P(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("Unexpected trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Object o) {
        return o == null ? null : (o instanceof String s ? s : String.valueOf(o));
    }

    public static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    public static boolean bool(Object o, boolean dflt) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return s.equalsIgnoreCase("true");
        return dflt;
    }

    /** A list of strings; a single string becomes a one-item list; null becomes empty. */
    public static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) if (x != null) out.add(str(x));
        } else if (o instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    // ---------- writing ----------

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o, 0, false);
        return sb.toString();
    }

    public static String pretty(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o, 0, true);
        return sb.toString();
    }

    private static void w(StringBuilder sb, Object o, int ind, boolean pretty) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            q(sb, s);
        } else if (o instanceof Boolean || o instanceof Integer || o instanceof Long) {
            sb.append(o);
        } else if (o instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) nl(sb, ind + 1);
                q(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                w(sb, e.getValue(), ind + 1, pretty);
            }
            if (pretty) nl(sb, ind);
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) nl(sb, ind + 1);
                w(sb, x, ind + 1, pretty);
            }
            if (pretty) nl(sb, ind);
            sb.append(']');
        } else {
            q(sb, String.valueOf(o));
        }
    }

    private static void nl(StringBuilder sb, int ind) {
        sb.append('\n');
        for (int i = 0; i < ind; i++) sb.append("  ");
    }

    private static void q(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- parser ----------

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s == null ? "" : s; }

        RuntimeException err(String m) { return new IllegalArgumentException("JSON: " + m + " at position " + i); }

        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            // tolerate a UTF-8 byte-order mark at the very start
            if (i == 0 && s.length() > 0 && s.charAt(0) == '﻿') { i++; ws(); }
        }

        Object value() {
            if (i >= s.length()) throw err("Unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        void expect(String w) {
            if (!s.startsWith(w, i)) throw err("Expected " + w);
            i += w.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw err("Expected string key");
                String k = string();
                ws();
                if (peek() != ':') throw err("Expected ':'");
                i++; ws();
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw err("Expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return l; }
                throw err("Expected ',' or ']'");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                if (i >= s.length()) throw err("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw err("Bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '/' -> sb.append('/');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw err("Bad unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err("Bad unicode escape");
                        }
                        i += 4;
                    }
                    default -> throw err("Bad escape \\" + e);
                }
            }
        }

        Object number() {
            int st = i;
            if (peek() == '-') i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty() || t.equals("-")) throw err("Unexpected character '" + peek() + "'");
            try {
                if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
                return Long.parseLong(t);
            } catch (NumberFormatException ex) {
                try { return Double.parseDouble(t); } catch (NumberFormatException e2) { throw err("Bad number " + t); }
            }
        }
    }
}
