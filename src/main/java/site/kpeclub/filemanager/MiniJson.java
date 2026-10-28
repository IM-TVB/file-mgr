package site.kpeclub.filemanager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON reader. This project has no JSON library, and adding one
 * just to read a handful of fields from a third-party API is not worth the extra build
 * dependency. Produces plain Java values: Map&lt;String,Object&gt;, List&lt;Object&gt;,
 * String, Double, Boolean, or null. Not meant for huge documents — it reads the whole text
 * into one tree.
 */
public final class MiniJson {

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != p.text.length()) {
            throw new IllegalArgumentException("unexpected trailing content at position " + p.pos);
        }
        return value;
    }

    // ---- typed helpers for walking the parsed tree ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    public static String asString(Object o, String fallback) {
        String s = asString(o);
        return s != null ? s : fallback;
    }

    public static long asLong(Object o, long fallback) {
        return o instanceof Number ? ((Number) o).longValue() : fallback;
    }

    public static boolean asBool(Object o, boolean fallback) {
        return o instanceof Boolean ? (Boolean) o : fallback;
    }

    public static Map<String, Object> field(Map<String, Object> m, String key) {
        return m == null ? null : asMap(m.get(key));
    }

    public static List<Object> fieldList(Map<String, Object> m, String key) {
        return m == null ? null : asList(m.get(key));
    }

    public static String fieldString(Map<String, Object> m, String key) {
        return m == null ? null : asString(m.get(key));
    }

    public static String fieldString(Map<String, Object> m, String key, String fallback) {
        String s = fieldString(m, key);
        return s != null ? s : fallback;
    }

    public static long fieldLong(Map<String, Object> m, String key, long fallback) {
        return m == null ? fallback : asLong(m.get(key), fallback);
    }

    public static boolean fieldBool(Map<String, Object> m, String key, boolean fallback) {
        return m == null ? fallback : asBool(m.get(key), fallback);
    }

    /** All string entries of a list, skipping any non-string entries. */
    public static List<String> stringsOf(List<Object> list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (Object o : list) {
            if (o instanceof String) out.add((String) o);
        }
        return out;
    }

    // ---- parsing ----

    private Object readValue() {
        if (pos >= text.length()) throw err("unexpected end of input");
        char c = text.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
                throw err("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') throw err("expected a string key");
            String key = readString();
            skipWs();
            if (peek() != ':') throw err("expected ':' after key");
            pos++;
            skipWs();
            map.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                break;
            } else {
                throw err("expected ',' or '}' in object");
            }
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                break;
            } else {
                throw err("expected ',' or ']' in array");
            }
        }
        return list;
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw err("unterminated string");
            char c = text.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                if (pos >= text.length()) throw err("unterminated escape");
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > text.length()) throw err("truncated unicode escape");
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw err("unknown escape '\\" + esc + "'");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double readNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        int digitsStart = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        int intDigits = pos - digitsStart;
        if (intDigits == 0) throw err("invalid number");
        // JSON forbids a leading zero followed by more digits (e.g. "01"); "0" and "0.5" are fine.
        if (intDigits > 1 && text.charAt(digitsStart) == '0') throw err("invalid number: leading zero");
        if (pos < text.length() && text.charAt(pos) == '.') {
            pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        String num = text.substring(start, pos);
        if (num.isEmpty() || num.equals("-")) throw err("invalid number");
        return Double.parseDouble(num);
    }

    private void expect(String literal) {
        if (pos + literal.length() > text.length() || !text.regionMatches(pos, literal, 0, literal.length())) {
            throw err("expected '" + literal + "'");
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= text.length()) throw err("unexpected end of input");
        return text.charAt(pos);
    }

    private void skipWs() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private IllegalArgumentException err(String message) {
        return new IllegalArgumentException("JSON parse error: " + message + " (at position " + pos + ")");
    }
}
