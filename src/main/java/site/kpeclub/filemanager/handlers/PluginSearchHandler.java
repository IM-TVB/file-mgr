package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.ModrinthClient;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * GET /api/plugins/search?q=&offset=
 * Searches Modrinth for server plugins. A thin proxy: this keeps the real Modrinth API off
 * the browser (no CORS problems, no exposing this server's network to client-controlled
 * requests) and lets the response be trimmed to only what the UI needs.
 */
public class PluginSearchHandler extends BaseApiHandler {

    private final ModrinthClient client;

    public PluginSearchHandler(File root, TokenManager tokenManager) {
        this(root, tokenManager, new ModrinthClient());
    }

    /** Lets tests point this at a local mock instead of the real Modrinth API. */
    public PluginSearchHandler(File root, TokenManager tokenManager, ModrinthClient client) {
        super(root, tokenManager);
        this.client = client;
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        if (!pluginInstallerEnabled) {
            sendJson(exchange, 403, "{\"error\":\"the plugin installer is turned off in config.yml\"}");
            return;
        }
        Map<String, String> params = queryParams(exchange);
        String query = params.getOrDefault("q", "");
        int offset = parseInt(params.get("offset"), 0);

        ModrinthClient.SearchResult result;
        try {
            result = client.search(query, offset, 20);
        } catch (IOException e) {
            sendJson(exchange, 502, "{\"error\":" + jsonString("Could not reach Modrinth: " + e.getMessage()) + "}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":").append(jsonString(query))
          .append(",\"total\":").append(result.totalHits)
          .append(",\"offset\":").append(result.offset)
          .append(",\"hits\":[");
        List<ModrinthClient.SearchHit> hits = result.hits;
        for (int i = 0; i < hits.size(); i++) {
            ModrinthClient.SearchHit h = hits.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(jsonString(h.id))
              .append(",\"slug\":").append(jsonString(h.slug))
              .append(",\"title\":").append(jsonString(h.title))
              .append(",\"description\":").append(jsonString(h.description))
              .append(",\"icon\":").append(h.icon == null ? "null" : jsonString(h.icon))
              .append(",\"downloads\":").append(h.downloads)
              .append(",\"loaders\":[");
            for (int j = 0; j < h.loaders.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(jsonString(h.loaders.get(j)));
            }
            sb.append("]}");
        }
        sb.append("]}");
        sendJson(exchange, 200, sb.toString());
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
