package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

public class WriteHandler extends BaseApiHandler {

    private final long maxEditBytes;

    public WriteHandler(File root, TokenManager tokenManager, long maxEditBytes) {
        super(root, tokenManager);
        this.maxEditBytes = maxEditBytes;
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        Map<String, String> params = queryParams(exchange);
        String path = params.get("path");
        File file = FileUtils.resolveSafe(root, path);

        if (file.isDirectory()) {
            sendJson(exchange, 400, "{\"error\":\"cannot write to a directory\"}");
            return;
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        byte[] body;
        try {
            body = readBody(exchange, maxEditBytes);
        } catch (IOException tooBig) {
            sendJson(exchange, 413, "{\"error\":\"content too large to save from the in-browser editor\"}");
            return;
        }
        Files.write(file.toPath(), body);
        sendJson(exchange, 200, "{\"ok\":true}");
    }
}
