package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class RenameHandler extends BaseApiHandler {

    public RenameHandler(File root, TokenManager tokenManager) {
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
        String newPath = params.get("newPath");

        File source = FileUtils.resolveSafe(root, path);
        File dest = FileUtils.resolveSafe(root, newPath);

        if (!source.exists()) {
            sendJson(exchange, 404, "{\"error\":\"source not found\"}");
            return;
        }
        if (source.equals(root.getCanonicalFile())) {
            sendJson(exchange, 400, "{\"error\":\"cannot rename the root directory\"}");
            return;
        }
        if (dest.exists()) {
            sendJson(exchange, 400, "{\"error\":\"destination already exists\"}");
            return;
        }
        File destParent = dest.getParentFile();
        if (destParent != null && !destParent.exists()) {
            destParent.mkdirs();
        }
        if (!source.renameTo(dest)) {
            sendJson(exchange, 500, "{\"error\":\"rename failed\"}");
            return;
        }
        sendJson(exchange, 200, "{\"ok\":true}");
    }
}
