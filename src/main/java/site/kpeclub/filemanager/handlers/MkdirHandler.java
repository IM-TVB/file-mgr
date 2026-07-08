package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class MkdirHandler extends BaseApiHandler {

    public MkdirHandler(File root, TokenManager tokenManager) {
        super(root, tokenManager);
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        Map<String, String> params = queryParams(exchange);
        String path = params.get("path");
        File dir = FileUtils.resolveSafe(root, path);

        if (dir.exists()) {
            sendJson(exchange, 400, "{\"error\":\"already exists\"}");
            return;
        }
        if (!dir.mkdirs()) {
            sendJson(exchange, 500, "{\"error\":\"failed to create directory\"}");
            return;
        }
        sendJson(exchange, 200, "{\"ok\":true}");
    }
}
