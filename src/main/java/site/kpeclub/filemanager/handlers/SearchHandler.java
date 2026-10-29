package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

/**
 * Find in files: case-insensitive substring search across every text file under
 * the root. Binary files, symlinks and files too large to open in the editor are
 * skipped, so every result can be opened.
 */
public class SearchHandler extends BaseApiHandler {

    private static final int MIN_QUERY = 2;
    private static final int MAX_QUERY = 200;
    private static final int MAX_MATCHES = 300;
    private static final int MAX_FILES_SCANNED = 5000;
    private static final long TIME_BUDGET_MS = 5000;
    private static final int SNIPPET_CHARS = 240;

    private final long maxFileBytes;

    public SearchHandler(File root, TokenManager tokenManager, long maxFileBytes) {
        super(root, tokenManager);
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        Map<String, String> params = queryParams(exchange);
        String query = params.getOrDefault("q", "").trim();
        if (query.length() < MIN_QUERY || query.length() > MAX_QUERY) {
            sendJson(exchange, 400, "{\"error\":\"search text must be 2 to 200 characters\"}");
            return;
        }

        String needle = query.toLowerCase(Locale.ROOT);
        File rootDir = root.getCanonicalFile();
        Path rootPath = rootDir.toPath();
        long deadline = System.currentTimeMillis() + TIME_BUDGET_MS;

        StringBuilder matches = new StringBuilder();
        int matchCount = 0;
        int filesScanned = 0;
        boolean truncated = false;

        Deque<File> dirs = new ArrayDeque<>();
        dirs.push(rootDir);
        scan:
        while (!dirs.isEmpty()) {
            // Folders count against the time limit too, not only files (many empty folders).
            if (System.currentTimeMillis() > deadline) {
                truncated = true;
                break;
            }
            File dir = dirs.pop();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (Files.isSymbolicLink(child.toPath())) continue;
                if (child.isDirectory()) {
                    dirs.push(child);
                    continue;
                }
                if (filesScanned >= MAX_FILES_SCANNED || System.currentTimeMillis() > deadline) {
                    truncated = true;
                    break scan;
                }
                filesScanned++;
                if (child.length() > maxFileBytes) continue;

                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(child.toPath());
                } catch (IOException unreadable) {
                    continue;
                }
                if (FileUtils.looksBinary(bytes)) continue;

                String rel = rootPath.relativize(child.toPath()).toString().replace('\\', '/');
                String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    int idx = lines[i].toLowerCase(Locale.ROOT).indexOf(needle);
                    if (idx == -1) continue;
                    if (matchCount >= MAX_MATCHES) {
                        truncated = true;
                        break scan;
                    }
                    if (matchCount > 0) matches.append(",");
                    matches.append("{\"path\":").append(jsonString(rel))
                           .append(",\"line\":").append(i + 1)
                           .append(",\"text\":").append(jsonString(snippet(lines[i], idx)))
                           .append("}");
                    matchCount++;
                }
            }
        }

        String json = "{\"query\":" + jsonString(query)
                + ",\"matches\":[" + matches + "]"
                + ",\"filesScanned\":" + filesScanned
                + ",\"truncated\":" + truncated + "}";
        sendJson(exchange, 200, json);
    }

    /** A window of the line around the match, so the match stays visible on long lines. */
    private static String snippet(String line, int matchIndex) {
        String clean = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        int start = Math.min(Math.max(0, matchIndex - SNIPPET_CHARS / 3), clean.length());
        int end = Math.min(clean.length(), start + SNIPPET_CHARS);
        return (start > 0 ? "…" : "") + clean.substring(start, end) + (end < clean.length() ? "…" : "");
    }
}
