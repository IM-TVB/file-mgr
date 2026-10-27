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

        // Headers can only carry plain ASCII, so non-ASCII names (for example Khmer) get an
        // ASCII fallback plus the real name in filename* (RFC 6266 / RFC 5987).
        String fallback = file.getName().replaceAll("[^\\x20-\\x7E]", "_").replaceAll("[\\\\\"\r\n]", "_");
        String utf8Name = java.net.URLEncoder.encode(file.getName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + fallback + "\"; filename*=UTF-8\'\'" + utf8Name);
        byte[] bytes = Files.readAllBytes(file.toPath());
        sendBytes(exchange, 200, "application/octet-stream", bytes);
    }
}
