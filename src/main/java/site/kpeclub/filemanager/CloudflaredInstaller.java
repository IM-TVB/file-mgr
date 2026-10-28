package site.kpeclub.filemanager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Downloads the official cloudflared binary from Cloudflare's GitHub releases, so the
 * server does not need cloudflared installed by hand. Supports Linux amd64 and arm64.
 * The file is written to a temporary name and moved into place only when complete.
 */
public final class CloudflaredInstaller {

    public static final String DEFAULT_BASE_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/";
    private static final long DEFAULT_MAX_BYTES = 200L * 1024 * 1024;

    private final String baseUrl;
    private final long maxBytes;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CloudflaredInstaller() {
        this(DEFAULT_BASE_URL, DEFAULT_MAX_BYTES);
    }

    public CloudflaredInstaller(String baseUrl, long maxBytes) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.maxBytes = maxBytes;
    }

    /** The release file for this OS and CPU, or null if it is not supported. */
    public static String assetName(String osName, String osArch) {
        if (osName == null || !osName.toLowerCase().contains("linux")) return null;
        String arch = osArch == null ? "" : osArch.toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            return "cloudflared-linux-amd64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "cloudflared-linux-arm64";
        }
        return null;
    }

    /**
     * Downloads cloudflared into targetFile and makes it executable. Throws IOException
     * with a readable reason if the system is unsupported, the download fails, or the file
     * is too large. A failed download leaves no partial file behind.
     */
    public File download(File targetFile, String osName, String osArch, Logger log) throws IOException {
        String asset = assetName(osName, osArch);
        if (asset == null) {
            throw new IOException("automatic download only supports Linux amd64 and arm64 (this system is "
                    + osName + " " + osArch + ")");
        }
        File dir = targetFile.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(dir, targetFile.getName() + ".download");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + asset))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    throw new IOException("download failed with HTTP " + response.statusCode());
                }
            }
            log.info("Downloading cloudflared (" + asset + ") from GitHub...");
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tmp.toPath())) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IOException("download is larger than " + (maxBytes / (1024 * 1024)) + " MB");
                    }
                    out.write(buffer, 0, n);
                }
            }
            Files.move(tmp.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            targetFile.setExecutable(true, false);
            log.info("cloudflared saved to " + targetFile.getAbsolutePath());
            return targetFile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download was interrupted");
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }
}
