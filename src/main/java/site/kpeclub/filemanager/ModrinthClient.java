package site.kpeclub.filemanager;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small client for Modrinth's public API (https://docs.modrinth.com/api), used to search
 * for and install server plugins. No API key is required. Modrinth asks every client to send
 * a uniquely-identifying User-Agent, which this sets on every request.
 *
 * Only reads data: search results and version/file metadata. Downloading the actual plugin
 * file happens separately in PluginInstallHandler, which re-fetches the version by id itself
 * rather than trusting anything the browser sends, and verifies the file's SHA-1 against the
 * hash Modrinth reports for it.
 */
public class ModrinthClient {

    public static final String DEFAULT_BASE_URL = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "IM-TVB/file-mgr/KPEFileManager-plugin-installer "
            + "(+https://github.com/IM-TVB/file-mgr)";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public static final class SearchHit {
        public final String id;
        public final String slug;
        public final String title;
        public final String description;
        public final String icon;
        public final long downloads;
        public final List<String> loaders;

        SearchHit(String id, String slug, String title, String description, String icon,
                  long downloads, List<String> loaders) {
            this.id = id;
            this.slug = slug;
            this.title = title;
            this.description = description;
            this.icon = icon;
            this.downloads = downloads;
            this.loaders = loaders;
        }
    }

    public static final class SearchResult {
        public final List<SearchHit> hits;
        public final long totalHits;
        public final int offset;

        SearchResult(List<SearchHit> hits, long totalHits, int offset) {
            this.hits = hits;
            this.totalHits = totalHits;
            this.offset = offset;
        }
    }

    public static final class VersionFile {
        public final String filename;
        public final long size;
        public final boolean primary;
        public final String url;
        public final String sha1;

        VersionFile(String filename, long size, boolean primary, String url, String sha1) {
            this.filename = filename;
            this.size = size;
            this.primary = primary;
            this.url = url;
            this.sha1 = sha1;
        }
    }

    public static final class Version {
        public final String id;
        public final String versionNumber;
        public final String name;
        public final String datePublished;
        public final List<String> gameVersions;
        public final List<String> loaders;
        public final List<VersionFile> files;

        Version(String id, String versionNumber, String name, String datePublished,
                List<String> gameVersions, List<String> loaders, List<VersionFile> files) {
            this.id = id;
            this.versionNumber = versionNumber;
            this.name = name;
            this.datePublished = datePublished;
            this.gameVersions = gameVersions;
            this.loaders = loaders;
            this.files = files;
        }

        /**
         * The file to install. A plugin must be a .jar, so a primary .jar comes first, then any
         * .jar (a version can also carry a .zip, a data pack, say); then the primary file, then the first.
         */
        public VersionFile primaryFile() {
            if (files.isEmpty()) return null;
            for (VersionFile f : files) {
                if (f.primary && isJar(f)) return f;
            }
            for (VersionFile f : files) {
                if (isJar(f)) return f;
            }
            for (VersionFile f : files) {
                if (f.primary) return f;
            }
            return files.get(0);
        }

        private static boolean isJar(VersionFile f) {
            return f.filename != null && f.filename.toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
        }
    }

    /** Thrown for anything that stops a Modrinth call from succeeding: network, HTTP status, or bad JSON. */
    public static class ModrinthException extends IOException {
        public ModrinthException(String message) {
            super(message);
        }
    }

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ModrinthClient() {
        this(DEFAULT_BASE_URL);
    }

    public ModrinthClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Searches Modrinth, restricted to server plugins (project_type:plugin). limit is capped at 50. */
    public SearchResult search(String query, int offset, int limit) throws IOException {
        int cappedLimit = Math.max(1, Math.min(50, limit));
        int cappedOffset = Math.max(0, offset);
        // Plugins (and multi-loader projects that also have a plugin version) for this server's
        // software only. Projects that are only for Fabric, Forge and so on do not match.
        StringBuilder loaderFacet = new StringBuilder();
        for (String l : ServerLoaders.get()) {
            if (loaderFacet.length() > 0) loaderFacet.append(',');
            loaderFacet.append("\"categories:").append(l).append('"');
        }
        String facets = URLEncoder.encode(
                "[[\"project_type:plugin\",\"project_type:mod\"],[" + loaderFacet + "]]", StandardCharsets.UTF_8);
        String index = (query == null || query.isBlank()) ? "downloads" : "relevance";
        String q = (query == null) ? "" : query.trim();
        if (q.length() > 200) q = q.substring(0, 200); // a query this long cannot be a real search term
        String url = baseUrl + "/search?query=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&facets=" + facets
                + "&index=" + index
                + "&offset=" + cappedOffset
                + "&limit=" + cappedLimit;

        Map<String, Object> root = MiniJson.asMap(get(url));
        if (root == null) throw new ModrinthException("search response was not a JSON object");
        List<Object> hitsRaw = MiniJson.fieldList(root, "hits");
        List<SearchHit> hits = new ArrayList<>();
        if (hitsRaw != null) {
            for (Object o : hitsRaw) {
                Map<String, Object> h = MiniJson.asMap(o);
                if (h == null) continue;
                List<String> shownCategories = MiniJson.stringsOf(MiniJson.fieldList(h, "display_categories"));
                if (shownCategories == null || shownCategories.isEmpty()) {
                    shownCategories = MiniJson.stringsOf(MiniJson.fieldList(h, "categories"));
                }
                List<String> loaders = ServerLoaders.withoutModLoaders(shownCategories);
                hits.add(new SearchHit(
                        MiniJson.fieldString(h, "project_id", ""),
                        MiniJson.fieldString(h, "slug", ""),
                        MiniJson.fieldString(h, "title", "Untitled"),
                        MiniJson.fieldString(h, "description", ""),
                        MiniJson.fieldString(h, "icon_url"),
                        MiniJson.fieldLong(h, "downloads", 0),
                        loaders));
            }
        }
        long total = MiniJson.fieldLong(root, "total_hits", hits.size());
        return new SearchResult(hits, total, cappedOffset);
    }

    /** Every published version of a project, newest first (as Modrinth returns them). */
    public List<Version> listVersions(String projectIdOrSlug) throws IOException {
        return listVersions(projectIdOrSlug, null);
    }

    /**
     * Versions of a project that run on the given loaders (null = all), newest first. Versions for
     * Fabric, Forge and other mod loaders are left out.
     */
    public List<Version> listVersions(String projectIdOrSlug, List<String> onlyLoaders) throws IOException {
        String url = baseUrl + "/project/" + URLEncoder.encode(projectIdOrSlug, StandardCharsets.UTF_8) + "/version";
        if (onlyLoaders != null && !onlyLoaders.isEmpty()) {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < onlyLoaders.size(); i++) {
                if (i > 0) arr.append(',');
                arr.append('"').append(onlyLoaders.get(i)).append('"');
            }
            url += "?loaders=" + URLEncoder.encode(arr.append(']').toString(), StandardCharsets.UTF_8);
        }
        List<Object> raw = MiniJson.asList(get(url));
        if (raw == null) throw new ModrinthException("versions response was not a JSON array");
        List<Version> out = new ArrayList<>();
        for (Object o : raw) {
            Version v = parseVersion(MiniJson.asMap(o));
            if (v == null) continue;
            // Check again here, in case Modrinth ignored the loaders filter.
            if (onlyLoaders != null && !onlyLoaders.isEmpty() && !ServerLoaders.matches(v.loaders, onlyLoaders)) continue;
            out.add(v);
        }
        return out;
    }

    /** A single version by its id, used at install time so the server never trusts a client-given URL. */
    public Version getVersion(String versionId) throws IOException {
        String url = baseUrl + "/version/" + URLEncoder.encode(versionId, StandardCharsets.UTF_8);
        Version v = parseVersion(MiniJson.asMap(get(url)));
        if (v == null) throw new ModrinthException("version response was not a JSON object");
        return v;
    }

    private Version parseVersion(Map<String, Object> m) {
        if (m == null) return null;
        List<VersionFile> files = new ArrayList<>();
        List<Object> filesRaw = MiniJson.fieldList(m, "files");
        if (filesRaw != null) {
            for (Object fo : filesRaw) {
                Map<String, Object> f = MiniJson.asMap(fo);
                if (f == null) continue;
                Map<String, Object> hashes = MiniJson.field(f, "hashes");
                files.add(new VersionFile(
                        MiniJson.fieldString(f, "filename", ""),
                        MiniJson.fieldLong(f, "size", 0),
                        MiniJson.fieldBool(f, "primary", false),
                        MiniJson.fieldString(f, "url", ""),
                        MiniJson.fieldString(hashes, "sha1")));
            }
        }
        return new Version(
                MiniJson.fieldString(m, "id", ""),
                MiniJson.fieldString(m, "version_number", ""),
                MiniJson.fieldString(m, "name", ""),
                MiniJson.fieldString(m, "date_published", ""),
                MiniJson.stringsOf(MiniJson.fieldList(m, "game_versions")),
                MiniJson.stringsOf(MiniJson.fieldList(m, "loaders")),
                files);
    }

    private Object get(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new ModrinthException("not found");
            }
            if (response.statusCode() == 429) {
                throw new ModrinthException("rate limited by Modrinth, try again in a moment");
            }
            if (response.statusCode() != 200) {
                throw new ModrinthException("Modrinth returned HTTP " + response.statusCode());
            }
            try {
                return MiniJson.parse(response.body());
            } catch (IllegalArgumentException badJson) {
                throw new ModrinthException("could not read Modrinth's response: " + badJson.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModrinthException("request was interrupted");
        }
    }
}
