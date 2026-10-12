package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GET /api/list?path=<folder>[&wait=<seconds>]
 *
 * Returns the folder listing with an ETag fingerprint. With If-None-Match and wait, the
 * request is held until the folder changes (or the wait ends), so the browser sees new
 * files at once. Unchanged folders get a 304 with no body.
 */
public class ListHandler extends BaseApiHandler {

    private static final int MAX_WAIT_SECONDS = 25;
    private static final long CHECK_EVERY_MS = 250;
    /**
     * A waiting request holds a server thread, so only a few may wait at the same time. A
     * client that cancels a wait does not stop it on the server until its time runs out.
     */
    private static final int MAX_WAITERS = 4;

    private final AtomicInteger waiters = new AtomicInteger();

    public ListHandler(File root, TokenManager tokenManager) {
        super(root, tokenManager);
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        Map<String, String> params = queryParams(exchange);
        String path = params.getOrDefault("path", "");
        File dir = FileUtils.resolveSafe(root, path);
        String clientEtag = exchange.getRequestHeaders().getFirst("If-None-Match");
        int waitSeconds = parseWait(params.get("wait"));

        if (!dir.exists() || !dir.isDirectory()) {
            sendJson(exchange, 404, "{\"error\":\"not a directory\"}");
            return;
        }

        // Build the listing once. If the browser asked to wait and the folder is still the
        // same, hold the request until it changes, then build the listing again.
        byte[] body = listingJson(dir, path).getBytes(StandardCharsets.UTF_8);
        String etag = ResponseUtil.etagOf(body);
        if (waitSeconds > 0 && etag.equals(clientEtag)) {
            if (waiters.incrementAndGet() <= MAX_WAITERS) {
                try {
                    awaitChange(dir, etag, waitSeconds);
                } finally {
                    waiters.decrementAndGet();
                }
                body = listingJson(dir, path).getBytes(StandardCharsets.UTF_8);
                etag = ResponseUtil.etagOf(body);
            } else {
                // Too many waiters already: answer now, and the browser asks again shortly.
                waiters.decrementAndGet();
            }
        }
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if (etag.equals(clientEtag)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        ResponseUtil.send(exchange, 200, "application/json; charset=utf-8", body, true);
    }

    /**
     * Returns when the folder changes, vanishes, or time runs out. While waiting, a cheap
     * signature (names, sizes, times) is compared instead of rebuilding the full listing;
     * the full listing is only built once something has changed.
     */
    private void awaitChange(File dir, String etag, int waitSeconds) {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        long baseline = signature(dir);
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(CHECK_EVERY_MS);
                if (!dir.isDirectory()) return;
                if (signature(dir) != baseline) return;
            }
        } catch (InterruptedException e) {
            // Server is stopping: stop waiting and answer.
            Thread.currentThread().interrupt();
        }
    }

    /** Order-independent summary of the folder's entries. Changes when entries change. */
    static long signature(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return -1;
        long sig = children.length;
        for (File f : children) {
            long h = f.getName().hashCode();
            try {
                BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
                h = h * 31 + a.lastModifiedTime().toMillis();
                h = h * 31 + (a.isDirectory() ? 7 : a.size() * 2 + 3);
            } catch (IOException gone) {
                h = h * 31 + 11; // vanished between listing and stat
            }
            sig += mix(h);
        }
        return sig;
    }

    /** SplitMix64 finaliser: spreads small changes over all bits. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static int parseWait(String raw) {
        if (raw == null) return 0;
        try {
            return Math.max(0, Math.min(MAX_WAIT_SECONDS, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String listingJson(File dir, String path) {
        File[] children = dir.listFiles();
        if (children == null) children = new File[0];
        Arrays.sort(children, Comparator.comparing((File f) -> !f.isDirectory())
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":").append(jsonString(path)).append(",\"entries\":[");
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"name\":").append(jsonString(f.getName())).append(",")
              .append("\"isDir\":").append(f.isDirectory()).append(",")
              .append("\"size\":").append(f.isDirectory() ? 0 : f.length()).append(",")
              .append("\"sizeHuman\":").append(jsonString(f.isDirectory() ? "" : FileUtils.humanReadableSize(f.length()))).append(",")
              .append("\"modified\":").append(f.lastModified())
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
