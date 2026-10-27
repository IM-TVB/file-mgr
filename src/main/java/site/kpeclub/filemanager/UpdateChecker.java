package site.kpeclub.filemanager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub for the latest release of this plugin and reports whether it is newer
 * than the running version. Only the tag and the release page link are read. Any
 * failure (no internet, rate limit, bad response) is treated as "no update", so the
 * check never affects the server.
 */
public class UpdateChecker {

    public static final String DEFAULT_API_URL = "https://api.github.com/repos/IM-TVB/file-mgr/releases/latest";

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([0-9A-Za-z._-]{1,40})\"");
    private static final Pattern RELEASE_URL = Pattern.compile(
            "\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"\\s]{1,200}/releases/tag/[^\"\\s]{1,80})\"");

    private final String currentVersion;
    private final String apiUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile String latestVersion;
    private volatile String releaseUrl;

    public UpdateChecker(String currentVersion) {
        this(currentVersion, DEFAULT_API_URL);
    }

    public UpdateChecker(String currentVersion, String apiUrl) {
        this.currentVersion = currentVersion;
        this.apiUrl = apiUrl;
    }

    /** Newer version found by the last check, or null if none. */
    public String getLatestVersion() {
        return latestVersion;
    }

    /** Release page for the newer version, or null if none. */
    public String getReleaseUrl() {
        return releaseUrl;
    }

    /** Blocking network call: run it off the main thread. Returns true if a newer release exists. */
    public boolean check() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "KPEFileManager/" + currentVersion)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                latestVersion = null;
                releaseUrl = null;
                return false;
            }
            String body = response.body();
            Matcher tag = TAG.matcher(body);
            Matcher url = RELEASE_URL.matcher(body);
            if (!tag.find()) {
                return false;
            }
            String latest = tag.group(1).replaceFirst("^[vV]", "");
            if (compareVersions(latest, currentVersion) > 0) {
                latestVersion = latest;
                releaseUrl = url.find() ? url.group(1) : null;
                return true;
            }
            latestVersion = null;
            releaseUrl = null;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Compares dotted versions numerically: 1.0.10 is newer than 1.0.9.
     * Anything after a non-digit in a part (such as "3-beta") is ignored.
     * Returns a positive number if a is newer, negative if older, 0 if equal.
     */
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? leadingNumber(pa[i]) : 0;
            int y = i < pb.length ? leadingNumber(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int leadingNumber(String part) {
        int value = 0;
        for (int i = 0; i < part.length() && Character.isDigit(part.charAt(i)); i++) {
            value = value * 10 + (part.charAt(i) - '0');
        }
        return value;
    }
}
