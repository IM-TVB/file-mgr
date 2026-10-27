package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.ModrinthClient;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * GET /api/plugins/versions?project=<id-or-slug>
 * Lists a plugin's published versions. Deliberately leaves out each file's download URL and
 * hash: the browser only ever needs a version id to ask for an install, and the install step
 * re-fetches that version from Modrinth itself rather than trusting anything sent back here.
 */
public class PluginVersionsHandler extends BaseApiHandler {

    private final ModrinthClient client;

    public PluginVersionsHandler(File root, TokenManager tokenManager) {
        this(root, tokenManager, new ModrinthClient());
    }

    /** Lets tests point this at a local mock instead of the real Modrinth API. */
    public PluginVersionsHandler(File root, TokenManager tokenManager, ModrinthClient client) {
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
        String project = params.get("project");
        if (project == null || project.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"missing project\"}");
            return;
        }

        List<ModrinthClient.Version> versions;
        try {
            versions = client.listVersions(project);
        } catch (IOException e) {
            sendJson(exchange, 502, "{\"error\":" + jsonString("Could not reach Modrinth: " + e.getMessage()) + "}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"versions\":[");
        for (int i = 0; i < versions.size(); i++) {
            ModrinthClient.Version v = versions.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(jsonString(v.id))
              .append(",\"versionNumber\":").append(jsonString(v.versionNumber))
              .append(",\"name\":").append(jsonString(v.name))
              .append(",\"datePublished\":").append(jsonString(v.datePublished))
              .append(",\"gameVersions\":[");
            for (int j = 0; j < v.gameVersions.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(jsonString(v.gameVersions.get(j)));
            }
            sb.append("],\"loaders\":[");
            for (int j = 0; j < v.loaders.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(jsonString(v.loaders.get(j)));
            }
            sb.append("],\"files\":[");
            for (int j = 0; j < v.files.size(); j++) {
                ModrinthClient.VersionFile f = v.files.get(j);
                if (j > 0) sb.append(",");
                sb.append("{\"filename\":").append(jsonString(f.filename))
                  .append(",\"size\":").append(f.size)
                  .append(",\"primary\":").append(f.primary)
                  .append("}");
            }
            sb.append("]}");
        }
        sb.append("]}");
        sendJson(exchange, 200, sb.toString());
    }
}
