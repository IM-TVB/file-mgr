package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Accepts a raw file body. Destination folder and filename are passed as
 * query params (?path=<folder>&name=<filename>) to keep this dependency-free
 * (no multipart parsing library needed).
 */
public class UploadHandler extends BaseApiHandler {

    private final long maxUploadBytes;

    public UploadHandler(File root, TokenManager tokenManager, long maxUploadSizeMb) {
        super(root, tokenManager);
        this.maxUploadBytes = maxUploadSizeMb * 1024L * 1024L;
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        Map<String, String> params = queryParams(exchange);
        String folder = params.getOrDefault("path", "");
        String name = params.get("name");

        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            sendJson(exchange, 400, "{\"error\":\"invalid filename\"}");
            return;
        }

        File dir = FileUtils.resolveSafe(root, folder);
        if (dir.exists() && !dir.isDirectory()) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("A file named \"" + dir.getName()
                    + "\" is in the way of this folder.") + "}");
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            sendJson(exchange, 409, "{\"error\":\"could not create the folder (a file may be in the way)\"}");
            return;
        }
        File dest = FileUtils.resolveSafe(root, folder + "/" + name);
        if (dest.isDirectory()) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("A folder named \"" + name
                    + "\" already exists here. Rename or remove it, then upload again.") + "}");
            return;
        }

        byte[] body;
        try {
            body = readBody(exchange, maxUploadBytes);
        } catch (IOException tooBig) {
            sendJson(exchange, 413, "{\"error\":" + jsonString(tooBig.getMessage()) + "}");
            return;
        }
        Files.write(dest.toPath(), body);
        sendJson(exchange, 200, "{\"ok\":true}");
    }
}
