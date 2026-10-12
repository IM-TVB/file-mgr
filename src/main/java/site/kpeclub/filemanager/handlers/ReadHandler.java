package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

public class ReadHandler extends BaseApiHandler {

    private final long maxEditBytes;

    public ReadHandler(File root, TokenManager tokenManager, long maxEditBytes) {
        super(root, tokenManager);
        this.maxEditBytes = maxEditBytes;
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
        if (file.length() > maxEditBytes) {
            sendJson(exchange, 413, "{\"error\":\"file too large to edit, use download instead\"}");
            return;
        }

        byte[] bytes = Files.readAllBytes(file.toPath());
        if (FileUtils.looksBinary(bytes)) {
            sendJson(exchange, 415, "{\"error\":\"binary file, cannot open in the editor\"}");
            return;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String json = "{\"path\":" + jsonString(path) + ",\"content\":" + jsonString(content) + "}";
        sendJson(exchange, 200, json);
    }
}
