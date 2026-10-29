package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.ModrinthClient;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;

/**
 * POST /api/plugins/install?versionId=&lt;id&gt;&amp;overwrite=&lt;bool&gt;[&amp;fileIndex=&lt;n&gt;]
 *
 * Installs a plugin jar from Modrinth into the plugins/ folder. The browser only ever sends a
 * Modrinth version id (and optionally which file in that version) — never a URL. This handler
 * re-fetches that version from Modrinth itself, so a client can only ever install a file that
 * genuinely belongs to a real Modrinth version, never an arbitrary attacker-chosen address. The
 * file's host is also checked against cdn.modrinth.com, and the download is verified against
 * the SHA-1 Modrinth reports for that exact file before it is kept.
 *
 * This writes a real server plugin into plugins/. Anyone who can reach this endpoint already has
 * full file manager access (same trust level as uploading a jar by hand) — this just automates
 * fetching one from Modrinth. The new plugin does not load until the server restarts.
 */
public class PluginInstallHandler extends BaseApiHandler {

    /** The only host plugin files may be downloaded from. */
    public static final String DEFAULT_ALLOWED_HOST = "cdn.modrinth.com";

    private final long maxBytes;
    private final String allowedHost;
    private final ModrinthClient client;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public PluginInstallHandler(File root, TokenManager tokenManager, long maxUploadSizeMb) {
        this(root, tokenManager, maxUploadSizeMb, DEFAULT_ALLOWED_HOST, new ModrinthClient());
    }

    /**
     * allowedHost and client are overridable only so tests can point the host check and the
     * Modrinth lookups at a local mock instead of the real network.
     */
    public PluginInstallHandler(File root, TokenManager tokenManager, long maxUploadSizeMb,
                                 String allowedHost, ModrinthClient client) {
        super(root, tokenManager);
        this.maxBytes = maxUploadSizeMb * 1024L * 1024L;
        this.allowedHost = allowedHost;
        this.client = client;
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        if (!pluginInstallerEnabled) {
            sendJson(exchange, 403, "{\"error\":\"the plugin installer is turned off in config.yml\"}");
            return;
        }

        Map<String, String> params = queryParams(exchange);
        String versionId = params.get("versionId");
        if (versionId == null || versionId.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"missing versionId\"}");
            return;
        }
        boolean overwrite = "true".equalsIgnoreCase(params.get("overwrite"));
        Integer fileIndex = parseInt(params.get("fileIndex"));

        ModrinthClient.Version version;
        try {
            version = client.getVersion(versionId);
        } catch (IOException e) {
            sendJson(exchange, 502, "{\"error\":" + jsonString("Could not reach Modrinth: " + e.getMessage()) + "}");
            return;
        }

        if (!site.kpeclub.filemanager.ServerLoaders.matches(version.loaders)) {
            sendJson(exchange, 400, "{\"error\":" + jsonString("this version is not for your server software (it is for: "
                    + String.join(", ", version.loaders) + "; your server runs: "
                    + String.join(", ", site.kpeclub.filemanager.ServerLoaders.get()) + ")") + "}");
            return;
        }

        ModrinthClient.VersionFile file = (fileIndex != null && fileIndex >= 0 && fileIndex < version.files.size())
                ? version.files.get(fileIndex)
                : version.primaryFile();
        if (file == null) {
            sendJson(exchange, 502, "{\"error\":\"this version has no installable file\"}");
            return;
        }

        String host;
        try {
            host = new URI(file.url).getHost();
        } catch (URISyntaxException e) {
            host = null;
        }
        if (host == null || !host.equalsIgnoreCase(allowedHost)) {
            sendJson(exchange, 502, "{\"error\":\"refusing to download from an unexpected host\"}");
            return;
        }

        String name = file.filename;
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            sendJson(exchange, 502, "{\"error\":\"Modrinth returned an invalid file name\"}");
            return;
        }
        if (!name.toLowerCase().endsWith(".jar")) {
            sendJson(exchange, 502, "{\"error\":\"this file is not a .jar and cannot be installed as a plugin\"}");
            return;
        }
        if (file.sha1 == null || file.sha1.isBlank()) {
            sendJson(exchange, 502, "{\"error\":\"Modrinth did not provide a checksum for this file; refusing to "
                    + "install it unverified\"}");
            return;
        }

        File targetFile;
        try {
            targetFile = FileUtils.resolveSafe(root, "plugins/" + name);
        } catch (SecurityException e) {
            sendJson(exchange, 400, "{\"error\":\"invalid file name\"}");
            return;
        }
        File targetDir = targetFile.getParentFile();
        targetDir.mkdirs();

        if (targetFile.isDirectory()) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("A folder named \"" + name
                    + "\" is in the plugins folder. Rename or remove it, then try again.") + "}");
            return;
        }

        if (targetFile.exists() && !overwrite) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("A plugin named \"" + name
                    + "\" already exists. Replace it?") + ",\"canOverwrite\":true}");
            return;
        }

        File tmp = new File(targetDir, name + ".download");
        try {
            downloadAndVerify(file.url, file.sha1, tmp);
            Files.move(tmp.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            sendJson(exchange, 502, "{\"error\":" + jsonString(message) + "}");
            return;
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }

        sendJson(exchange, 200, "{\"ok\":true,\"filename\":" + jsonString(name)
                + ",\"size\":" + targetFile.length() + "}");
    }

    /** Downloads url to dest, enforcing the size cap while streaming, and checks its SHA-1 against expectedSha1. */
    private void downloadAndVerify(String url, String expectedSha1, File dest) throws IOException {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is not available", e);
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(new URI(url))
                    .timeout(Duration.ofMinutes(3))
                    .header("User-Agent", "IM-TVB/file-mgr/KPEFileManager-plugin-installer")
                    .GET()
                    .build();
        } catch (URISyntaxException e) {
            throw new IOException("invalid download URL");
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download was interrupted");
        }
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
                throw new IOException("download failed with HTTP " + response.statusCode());
            }
        }
        long total = 0;
        try (InputStream in = response.body(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("plugin file is larger than the configured max-upload-size-mb limit");
                }
                out.write(buffer, 0, n);
                sha1.update(buffer, 0, n);
            }
        }
        String actual = toHex(sha1.digest());
        if (!actual.equalsIgnoreCase(expectedSha1)) {
            throw new IOException("downloaded file did not match the checksum Modrinth reported for it; "
                    + "refusing to install it");
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Integer parseInt(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
