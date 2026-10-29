package site.kpeclub.filemanager;

import java.io.IOException;
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
    /**
     * Fallback when the GitHub API refuses the request (its limit is 60 per hour per IP address,
     * which shared hosts often use up). This page answers with a redirect to the newest release.
     */
    public static final String DEFAULT_WEB_URL = "https://github.com/IM-TVB/file-mgr/releases/latest";

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([0-9A-Za-z._-]{1,40})\"");
    private static final Pattern RELEASE_URL = Pattern.compile(
            "\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"\\s]{1,200}/releases/tag/[^\"\\s]{1,80})\"");

    private static final Pattern WEB_TAG = Pattern.compile("^(https://github\\.com/[^\\s]{1,200}/releases/tag/([0-9A-Za-z._-]{1,40}))$");

    private final String currentVersion;
    private final String apiUrl;
    private final String webUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile String latestVersion;
    private volatile String releaseUrl;
    private volatile String lastError;

    private final HttpClient noRedirectClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public UpdateChecker(String currentVersion) {
        this(currentVersion, DEFAULT_API_URL, DEFAULT_WEB_URL);
    }

    public UpdateChecker(String currentVersion, String apiUrl) {
        this(currentVersion, apiUrl, null);
    }

    public UpdateChecker(String currentVersion, String apiUrl, String webUrl) {
        this.currentVersion = currentVersion;
        this.apiUrl = apiUrl;
        this.webUrl = webUrl;
    }

    /** Why the last check could not be completed, or null if it worked. */
    public String getLastError() {
        return lastError;
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
        String[] found = null; // {tag, releaseUrl}
        String apiProblem = null;
        try {
            found = fetchFromApi();
        } catch (Exception e) {
            apiProblem = describe(e);
        }
        if (found == null && webUrl != null) {
            try {
                found = fetchFromWebRedirect();
                if (found != null) apiProblem = null;
            } catch (Exception e) {
                if (apiProblem == null) apiProblem = describe(e);
            }
        }
        if (found == null) {
            // No answer is not the same as "no update": keep what we knew, but remember why.
            if (apiProblem != null) lastError = apiProblem;
            else { lastError = null; latestVersion = null; releaseUrl = null; } // no release published yet
            return false;
        }
        lastError = null;
        String latest = found[0].replaceFirst("^[vV]", "");
        if (compareVersions(latest, currentVersion) > 0) {
            latestVersion = latest;
            releaseUrl = found[1];
            return true;
        }
        latestVersion = null;
        releaseUrl = null;
        return false;
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /** Returns {tag, releaseUrl}, or null when GitHub did not give a usable answer. */
    private String[] fetchFromApi() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KPEFileManager/" + currentVersion)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code == 404) return null;                       // the project has no release yet
        if (code != 200) throw new IOException("GitHub answered HTTP " + code);   // rate limit, server error ...
        String body = response.body();
        Matcher tag = TAG.matcher(body);
        if (!tag.find()) return null;
        Matcher url = RELEASE_URL.matcher(body);
        return new String[] { tag.group(1), url.find() ? url.group(1) : null };
    }

    /** Reads the release tag from where github.com/<repo>/releases/latest redirects to. */
    private String[] fetchFromWebRedirect() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(webUrl))
                .timeout(TIMEOUT)
                .header("User-Agent", "KPEFileManager/" + currentVersion)
                .GET()
                .build();
        HttpResponse<Void> response = noRedirectClient.send(request, HttpResponse.BodyHandlers.discarding());
        int code = response.statusCode();
        if (code == 404) return null;
        if (code < 300 || code > 399) {
            if (code == 200) return null;                   // no redirect: no release to point to
            throw new IOException("GitHub answered HTTP " + code);
        }
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null) return null;
        Matcher m = WEB_TAG.matcher(location);
        // Without any release, GitHub redirects to the plain releases page: no tag in it.
        return m.matches() ? new String[] { m.group(2), m.group(1) } : null;
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
