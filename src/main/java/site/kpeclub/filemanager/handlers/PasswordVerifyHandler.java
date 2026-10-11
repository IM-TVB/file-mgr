package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import site.kpeclub.filemanager.TokenManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles the password step between clicking the /filemgr link and getting a
 * real session — only wired up when config.yml's login-password is set.
 * Deliberately NOT a BaseApiHandler: at this point in the flow there's no
 * session cookie yet, so there's nothing to authenticate against.
 */
public class PasswordVerifyHandler implements HttpHandler {

    private final TokenManager tokenManager;
    private final String configuredPassword;
    private final long sessionExpirySeconds;
    private final boolean httpsEnabled;

    public PasswordVerifyHandler(TokenManager tokenManager, String configuredPassword,
                                  long sessionExpiryHours, boolean httpsEnabled) {
        this.tokenManager = tokenManager;
        this.configuredPassword = configuredPassword;
        this.sessionExpirySeconds = sessionExpiryHours * 3600L;
        this.httpsEnabled = httpsEnabled;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            SecurityHeaders.apply(exchange);

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendPlainHtml(exchange, 405, "<h1>Method not allowed</h1>");
                return;
            }

            Map<String, String> form = parseForm(readAll(exchange));
            String pendingId = form.get("pending");
            String password = form.getOrDefault("password", "");
            String ip = BaseApiHandler.clientIp(exchange);

            if (!tokenManager.pendingSessionValid(pendingId)) {
                sendPlainHtml(exchange, 401,
                        "<h1>Link expired</h1><p>Run <code>/filemgr</code> in-game again to get a fresh link.</p>");
                return;
            }

            if (!tokenManager.allowPasswordAttempt(ip)) {
                BaseApiHandler.AUDIT_LOG.warning("[security] Rate-limited " + ip + " after repeated wrong file manager passwords.");
                sendPlainHtml(exchange, 429, "<h1>Too many attempts</h1><p>Wait a few minutes and try again.</p>");
                return;
            }

            String sessionId = tokenManager.confirmPendingSession(pendingId, password, configuredPassword);
            if (sessionId == null) {
                BaseApiHandler.AUDIT_LOG.warning("[security] Wrong file manager password attempt from " + ip);
                sendPasswordPage(exchange, pendingId, "Incorrect password. Try again.");
                return;
            }

            String cookie = "kpefm_session=" + sessionId + "; Path=/; HttpOnly; SameSite=Strict"
                    + (httpsEnabled ? "; Secure" : "")
                    + "; Max-Age=" + sessionExpirySeconds;
            exchange.getResponseHeaders().set("Set-Cookie", cookie);
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(302, -1);
            BaseApiHandler.AUDIT_LOG.info("[security] File manager password accepted from " + ip);
        } finally {
            exchange.close();
        }
    }

    /** Renders the password entry page. Also called by IndexHandler right after a token redeems to "pending". */
    public static void sendPasswordPage(HttpExchange exchange, String pendingId, String errorMessage) throws IOException {
        String errorBlock = errorMessage == null ? ""
                : "<div class=\"err\">" + escapeHtml(errorMessage) + "</div>";
        String page = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>KPE File Manager</title><style>"
                + "body{background:#1b1e29;color:#e7e9ee;font-family:-apple-system,'Segoe UI',sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
                + ".card{background:#20232f;border:1px solid #313543;border-radius:14px;padding:32px;width:300px;text-align:center}"
                + "h1{font-size:16px;margin:0 0 6px}"
                + "p{color:#9aa1b0;font-size:13px;margin:0 0 20px}"
                + "input{width:100%;box-sizing:border-box;background:#262a38;border:1px solid #313543;color:#e7e9ee;"
                + "padding:10px 12px;border-radius:8px;font-size:14px;margin-bottom:12px}"
                + "input:focus{outline:none;border-color:#3f8cff}"
                + "button{width:100%;background:linear-gradient(90deg,#3f8cff,#29d6c8);border:none;color:#061014;"
                + "font-weight:600;padding:10px;border-radius:8px;font-size:14px;cursor:pointer}"
                + ".err{color:#ff5c5c;font-size:12.5px;margin-bottom:12px}"
                + "</style></head><body>"
                + "<form class=\"card\" method=\"POST\" action=\"/api/verify-password\">"
                + "<h1>KPE File Manager</h1><p>Enter the panel password to continue.</p>"
                + errorBlock
                + "<input type=\"hidden\" name=\"pending\" value=\"" + escapeHtml(pendingId) + "\">"
                + "<input type=\"password\" name=\"password\" placeholder=\"Password\" autofocus required>"
                + "<button type=\"submit\">Continue</button>"
                + "</form></body></html>";
        SecurityHeaders.apply(exchange);
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendPlainHtml(HttpExchange exchange, int status, String bodyHtml) throws IOException {
        String page = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<title>KPE File Manager</title>"
                + "<style>body{background:#0d0d0d;color:#e6e6e6;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center}"
                + "code{background:#1a1a1a;padding:2px 6px;border-radius:4px;color:#4ec9ff}</style></head>"
                + "<body><div>" + bodyHtml + "</div></body></html>";
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            long total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > 8192) throw new IOException("form body too large");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static Map<String, String> parseForm(byte[] body) {
        Map<String, String> map = new HashMap<>();
        String s = new String(body, StandardCharsets.UTF_8);
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
