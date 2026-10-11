package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

public class ListHandler extends BaseApiHandler {

    public ListHandler(File root, TokenManager tokenManager) {
        super(root, tokenManager);
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        Map<String, String> params = queryParams(exchange);
        String path = params.getOrDefault("path", "");
        File dir = FileUtils.resolveSafe(root, path);

        if (!dir.exists() || !dir.isDirectory()) {
            sendJson(exchange, 404, "{\"error\":\"not a directory\"}");
            return;
        }

        File[] children = dir.listFiles();
        if (children == null) children = new File[0];
        Arrays.sort(children, Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":").append(jsonString(path)).append(",\"entries\":[");
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"name\":").append(jsonString(f.getName())).append(",")
              .append("\"isDir\":").append(f.isDirectory()).append(",")
              .append("\"size\":").append(f.isDirectory() ? 0 : f.length()).append(",")
              .append("\"sizeHuman\":").append(jsonString(f.isDirectory() ? "" : FileUtils.humanReadableSize(f.length()))).append(",")
              .append("\"modified\":").append(f.lastModified())
              .append("}");
        }
        sb.append("]}");
        sendJson(exchange, 200, sb.toString());
    }
}
