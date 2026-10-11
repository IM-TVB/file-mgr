package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import site.kpeclub.filemanager.TokenManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class IndexHandler implements HttpHandler {

    private final TokenManager tokenManager;
    private final long sessionExpirySeconds;
    private final boolean httpsEnabled;
    private final String loginPassword;

    public IndexHandler(TokenManager tokenManager, long sessionExpiryHours, boolean httpsEnabled, String loginPassword) {
        this.tokenManager = tokenManager;
        this.sessionExpirySeconds = sessionExpiryHours * 3600L;
        this.httpsEnabled = httpsEnabled;
        this.loginPassword = loginPassword;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            SecurityHeaders.apply(exchange);
            String query = exchange.getRequestURI().getRawQuery();
            String token = null;
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 && kv[0].equals("token")) {
                        token = kv[1];
                    }
                }
            }

            if (token != null) {
                String ip = BaseApiHandler.clientIp(exchange);
                if (!tokenManager.allowTokenRedeemAttempt(ip)) {
                    BaseApiHandler.AUDIT_LOG.warning("[security] Rate-limited " + ip + " after repeated login-link attempts.");
                    sendHtml(exchange, 429, "<h1>Too many attempts</h1><p>Wait a few minutes and try again.</p>");
                    return;
                }

                boolean passwordRequired = loginPassword != null && !loginPassword.isEmpty();

                if (passwordRequired) {
                    String pendingId = tokenManager.redeemTokenToPending(token);
                    if (pendingId == null) {
                        sendHtml(exchange, 401, "<h1>Link expired or already used</h1><p>Run /filemgr in-game again to get a fresh link.</p>");
                        return;
                    }
                    PasswordVerifyHandler.sendPasswordPage(exchange, pendingId, null);
                    return;
                }

                String sessionId = tokenManager.redeemToken(token);
                if (sessionId == null) {
                    sendHtml(exchange, 401, "<h1>Link expired or already used</h1><p>Run /filemgr in-game again to get a fresh link.</p>");
                    return;
                }
                String cookie = "kpefm_session=" + sessionId + "; Path=/; HttpOnly; SameSite=Strict"
                        + (httpsEnabled ? "; Secure" : "")
                        + "; Max-Age=" + sessionExpirySeconds;
                exchange.getResponseHeaders().set("Set-Cookie", cookie);
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(302, -1);
                BaseApiHandler.AUDIT_LOG.info("[security] File manager login from " + ip);
                exchange.close();
                return;
            }

            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String sessionId = BaseApiHandler.extractCookie(cookieHeader, "kpefm_session");
            String player = tokenManager.validateSession(sessionId);

            if (player == null) {
                sendHtml(exchange, 401, "<h1>Not logged in</h1><p>Run <code>/filemgr</code> in-game to get a login link.</p>");
                return;
            }

            byte[] html = readResource("/web/index.html");
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        } finally {
            exchange.close();
        }
    }

    private void sendHtml(HttpExchange exchange, int status, String bodyHtml) throws IOException {
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

    static byte[] readResource(String path) throws IOException {
        try (InputStream is = IndexHandler.class.getResourceAsStream(path)) {
            if (is == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
