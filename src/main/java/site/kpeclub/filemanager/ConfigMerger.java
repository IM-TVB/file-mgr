package site.kpeclub.filemanager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds settings that exist in the bundled config.yml but are missing from a server's
 * config.yml, so an update does not leave new options out. It works on the text, not
 * by re-saving the YAML, so comments and existing values are kept exactly as they are.
 * Handles top-level settings and settings one level down inside a section (such as
 * console: and https:).
 */
public final class ConfigMerger {

    private static final Pattern TOP = Pattern.compile("^([A-Za-z0-9_.-]+):(\\s.*)?$");
    private static final Pattern CHILD = Pattern.compile("^  ([A-Za-z0-9_.-]+):(\\s.*)?$");

    /** The merged text and the names of the settings that were added (empty if none). */
    public static final class Result {
        public final String text;
        public final List<String> added;

        Result(String text, List<String> added) {
            this.text = text;
            this.added = added;
        }
    }

    private static final class Entry {
        final String key;
        final List<String> lines;

        Entry(String key, List<String> lines) {
            this.key = key;
            this.lines = lines;
        }
    }

    private ConfigMerger() {}

    public static Result merge(String current, String bundled) {
        List<String> cur = split(current);
        List<String> added = new ArrayList<>();

        // 1. Settings missing inside sections that already exist.
        Map<String, Entry> bundledTop = entries(split(bundled));
        for (Entry section : bundledTop.values()) {
            if (!topKeys(cur).contains(section.key)) continue;
            Set<String> have = childKeys(cur, section.key);
            List<String> toInsert = new ArrayList<>();
            for (Entry child : children(section.lines)) {
                if (!have.contains(child.key)) {
                    toInsert.addAll(child.lines);
                    added.add(section.key + "." + child.key);
                }
            }
            if (!toInsert.isEmpty()) insertIntoSection(cur, section.key, toInsert);
        }

        // 2. Whole top-level settings missing from the file are appended at the end.
        List<String> tail = new ArrayList<>();
        for (Entry e : bundledTop.values()) {
            if (!topKeys(cur).contains(e.key)) {
                tail.addAll(e.lines);
                added.add(e.key);
            }
        }
        if (!tail.isEmpty()) {
            if (!cur.isEmpty() && !cur.get(cur.size() - 1).isBlank()) cur.add("");
            cur.add("# Settings added by the plugin update:");
            cur.addAll(tail);
        }

        if (added.isEmpty()) {
            return new Result(current, added);
        }
        return new Result(String.join("\n", cur) + "\n", added);
    }

    /** Splits text into lines, dropping the empty entry after a final newline. */
    private static List<String> split(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static Set<String> topKeys(List<String> lines) {
        Set<String> keys = new HashSet<>();
        for (String line : lines) {
            Matcher m = TOP.matcher(line);
            if (m.matches()) keys.add(m.group(1));
        }
        return keys;
    }

    /** Child keys (two spaces in) of one section of the existing file. */
    private static Set<String> childKeys(List<String> lines, String section) {
        Set<String> keys = new HashSet<>();
        int start = indexOfTop(lines, section);
        if (start < 0) return keys;
        for (int i = start + 1; i < lines.size(); i++) {
            if (TOP.matcher(lines.get(i)).matches()) break;
            Matcher m = CHILD.matcher(lines.get(i));
            if (m.matches()) keys.add(m.group(1));
        }
        return keys;
    }

    /** Inserts lines at the end of a section, before any comments that belong to the next setting. */
    private static void insertIntoSection(List<String> lines, String section, List<String> toInsert) {
        int start = indexOfTop(lines, section);
        if (start < 0) return;
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (TOP.matcher(lines.get(i)).matches()) {
                end = i;
                break;
            }
        }
        int pos = end;
        while (pos - 1 > start && (lines.get(pos - 1).isBlank() || lines.get(pos - 1).startsWith("#"))) {
            pos--;
        }
        lines.addAll(pos, toInsert);
    }

    private static int indexOfTop(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = TOP.matcher(lines.get(i));
            if (m.matches() && m.group(1).equals(key)) return i;
        }
        return -1;
    }

    /** Top-level settings of a bundled file, each with the comment lines above it. */
    private static Map<String, Entry> entries(List<String> lines) {
        Map<String, Entry> map = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        Entry current = null;
        for (String line : lines) {
            Matcher m = TOP.matcher(line);
            if (m.matches()) {
                List<String> block = new ArrayList<>(pending);
                pending.clear();
                block.add(line);
                current = new Entry(m.group(1), block);
                map.put(current.key, current);
            } else if (current != null && line.startsWith(" ") && !line.isBlank()) {
                current.lines.add(line);
            } else {
                pending.add(line);
            }
        }
        return map;
    }

    /** One-level children of a section in the bundled file, each with the comments above it. */
    private static List<Entry> children(List<String> sectionLines) {
        List<Entry> out = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Entry current = null;
        for (int i = 1; i < sectionLines.size(); i++) {
            String line = sectionLines.get(i);
            Matcher m = CHILD.matcher(line);
            if (m.matches()) {
                List<String> block = new ArrayList<>(pending);
                pending.clear();
                block.add(line);
                current = new Entry(m.group(1), block);
                out.add(current);
            } else if (current != null && line.startsWith("    ")) {
                current.lines.add(line);
            } else if (line.isBlank() || line.trim().startsWith("#")) {
                pending.add(line);
            }
        }
        return out;
    }
}
