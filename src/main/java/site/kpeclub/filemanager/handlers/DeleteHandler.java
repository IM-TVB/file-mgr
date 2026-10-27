package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class DeleteHandler extends BaseApiHandler {

    public DeleteHandler(File root, TokenManager tokenManager) {
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
        File target = FileUtils.resolveSafe(root, path);

        if (target.equals(root.getCanonicalFile())) {
            sendJson(exchange, 400, "{\"error\":\"cannot delete root directory\"}");
            return;
        }
        if (!target.exists()) {
            sendJson(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }

        FileUtils.deleteRecursive(target);
        sendJson(exchange, 200, "{\"ok\":true}");
    }
}
