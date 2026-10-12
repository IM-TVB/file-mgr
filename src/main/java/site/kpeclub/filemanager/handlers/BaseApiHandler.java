package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import site.kpeclub.filemanager.TokenManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public abstract class BaseApiHandler implements HttpHandler {

    // Default cap for any request body this plugin reads. Handlers that
    // legitimately need more (uploads) pass an explicit larger limit to
    // readBody(exchange, maxBytes). This exists so a bad-faith or buggy
    // client can't OOM the server by streaming an unbounded body — readBody
    // used to buffer with no limit at all.
    private static final long DEFAULT_MAX_BODY_BYTES = 20L * 1024 * 1024;

    // Named logger rather than a silent println: this plugin's ConsoleManager
    // hooks the root logger, so anything logged here also shows up in the
    // live Console tab and console.txt automatically.
    protected static final Logger AUDIT_LOG = Logger.getLogger("KPEFileManager");

    protected final File root;
    protected final TokenManager tokenManager;

    protected BaseApiHandler(File root, TokenManager tokenManager) {
        this.root = root;
        this.tokenManager = tokenManager;
    }

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        try {
            SecurityHeaders.apply(exchange);
            String player = authenticate(exchange);
            if (player == null) {
                String ip = clientIp(exchange);
                if (!tokenManager.allowAuthFailure(ip)) {
                    AUDIT_LOG.warning("[security] Rate-limited " + ip + " after repeated invalid file manager sessions.");
                    sendJson(exchange, 429, "{\"error\":\"too many attempts, slow down\"}");
                    return;
                }
                sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            handleAuthed(exchange, player);
        } catch (SecurityException se) {
            sendJson(exchange, 403, "{\"error\":\"forbidden\"}");
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":" + jsonString(e.getMessage() == null ? "internal error" : e.getMessage()) + "}");
        } finally {
            exchange.close();
        }
    }

    protected abstract void handleAuthed(HttpExchange exchange, String player) throws IOException;

    private String authenticate(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = extractCookie(cookieHeader, "kpefm_session");
        return tokenManager.validateSession(sessionId);
    }

    /**
     * The address to rate-limit by. Behind a Cloudflare tunnel or a reverse proxy on this
     * machine, every request arrives from loopback, so the real visitor address is taken from
     * the forwarding header. Headers are only trusted from loopback, so remote clients cannot
     * pick their own address.
     */
    protected static String clientIp(HttpExchange exchange) {
        InetSocketAddress addr = exchange.getRemoteAddress();
        if (addr == null || addr.getAddress() == null) return "unknown";
        if (addr.getAddress().isLoopbackAddress()) {
            String forwarded = forwardedClientIp(exchange);
            if (forwarded != null) return forwarded;
        }
        return addr.getAddress().getHostAddress();
    }

    private static String forwardedClientIp(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("CF-Connecting-IP");
        if (value == null) {
            String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null) value = xff.split(",")[0];
        }
        if (value == null) return null;
        value = value.trim();
        // Only plain IPv4/IPv6 text, so the value is safe to use as a key.
        return value.matches("[0-9A-Fa-f:.]{3,45}") ? value : null;
    }

    protected static String extractCookie(String cookieHeader, String name) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return kv[1];
            }
        }
        return null;
    }

    protected Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> map = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = urlDecode(kv[0]);
            String value = kv.length > 1 ? urlDecode(kv[1]) : "";
            map.put(key, value);
        }
        return map;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    protected byte[] readBody(HttpExchange exchange) throws IOException {
        return readBody(exchange, DEFAULT_MAX_BODY_BYTES);
    }

    /** Reads the request body, aborting if it exceeds maxBytes rather than buffering unbounded. */
    protected byte[] readBody(HttpExchange exchange, long maxBytes) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("Request body exceeds the maximum allowed size ("
                            + (maxBytes / (1024 * 1024)) + " MB)");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    protected void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ResponseUtil.send(exchange, status, "application/json; charset=utf-8", bytes, true);
    }

    protected void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    protected static String jsonString(String s) {
        return "\"" + site.kpeclub.filemanager.FileUtils.jsonEscape(s) + "\"";
    }
}
