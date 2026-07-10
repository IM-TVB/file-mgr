package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

public class DownloadHandler extends BaseApiHandler {

    public DownloadHandler(File root, TokenManager tokenManager) {
        super(root, tokenManager);
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        Map<String, String> params = queryParams(exchange);
        String path = params.get("path");
        File file = FileUtils.resolveSafe(root, path);

        if (!file.exists() || file.isDirectory()) {
            sendJson(exchange, 404, "{\"error\":\"file not found\"}");
            return;
        }

        // Strip characters that could break out of the quoted header value
        // (CR/LF would enable response-splitting; quotes would end it early).
        String safeName = file.getName().replaceAll("[\\r\\n\"]", "_");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        byte[] bytes = Files.readAllBytes(file.toPath());
        sendBytes(exchange, 200, "application/octet-stream", bytes);
    }
}
