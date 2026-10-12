package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;

public class LogoutHandler extends BaseApiHandler {

    public LogoutHandler(File root, TokenManager tokenManager) {
        super(root, tokenManager);
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = extractCookie(cookieHeader, "kpefm_session");
        tokenManager.invalidateSession(sessionId);

        exchange.getResponseHeaders().set("Set-Cookie", "kpefm_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        AUDIT_LOG.info("[security] " + player + " logged out of the file manager.");
        sendJson(exchange, 200, "{\"ok\":true}");
    }
}
