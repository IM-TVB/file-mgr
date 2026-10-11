package site.kpeclub.filemanager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Looks up this server's public IPv4 address from an external service, so the
 * /filemgr link works when the server runs in a container or behind NAT.
 * Results are cached. get() blocks on the network, so call it off the main thread.
 */
public class PublicIpResolver {

    private static final String[] SERVICES = {
            "https://api4.ipify.org",
            "https://ipv4.icanhazip.com",
            "https://ifconfig.me/ip"
    };
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final long CACHE_MS = 10L * 60 * 1000;
    private static final long RETRY_AFTER_FAILURE_MS = 60L * 1000;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private volatile String cachedIp;
    private volatile long cachedAt;
    private volatile long lastFailureAt;

    /**
     * Returns the public IPv4 address, or null if none is known. Looks it up when
     * the cache is stale. After a failed lookup it waits a minute before trying
     * again, so a server with no outbound access doesn't stall every link request.
     */
    public synchronized String get() {
        long now = System.currentTimeMillis();
        if (cachedIp != null && now - cachedAt < CACHE_MS) return cachedIp;
        if (now - lastFailureAt < RETRY_AFTER_FAILURE_MS) return cachedIp;

        String ip = lookup();
        if (ip != null) {
            cachedIp = ip;
            cachedAt = now;
        } else {
            lastFailureAt = now;
        }
        return cachedIp;
    }

    private String lookup() {
        for (String url : SERVICES) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body().trim();
                if (response.statusCode() == 200 && IPV4.matcher(body).matches()) {
                    return body;
                }
            } catch (Exception ignored) {
                // Try the next service.
            }
        }
        return null;
    }
}
