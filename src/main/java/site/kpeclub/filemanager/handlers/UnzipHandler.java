package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * POST /api/unzip?path=<relative path to a .zip file>[&dest=<relative target folder>]
 * Extracts into a new sibling folder named after the zip (or into `dest` if
 * given). Refuses to overwrite an existing folder — rename/delete it first.
 */
public class UnzipHandler extends BaseApiHandler {

    // Defense against zip bombs: a small malicious/corrupt zip can expand to
    // an enormous size or file count. These are hard caps, not configurable —
    // if a legitimate archive is bigger than this, extract it some other way.
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB
    private static final int MAX_ENTRIES = 50_000;

    public UnzipHandler(File root, TokenManager tokenManager) {
        super(root, tokenManager);
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        Map<String, String> params = queryParams(exchange);
        String pathParam = params.get("path");
        if (pathParam == null || pathParam.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"missing path\"}");
            return;
        }
        if (!pathParam.toLowerCase().endsWith(".zip")) {
            sendJson(exchange, 400, "{\"error\":\"not a .zip file\"}");
            return;
        }

        File zipFile = FileUtils.resolveSafe(root, pathParam);
        if (!zipFile.isFile()) {
            sendJson(exchange, 404, "{\"error\":\"zip file not found\"}");
            return;
        }

        String destParam = params.get("dest");
        File destDir;
        if (destParam != null && !destParam.isBlank()) {
            destDir = FileUtils.resolveSafe(root, destParam);
        } else {
            String base = zipFile.getName();
            String folderName = base.substring(0, base.length() - 4); // strip ".zip"
            if (folderName.isBlank()) folderName = base + "-extracted";
            destDir = new File(zipFile.getParentFile(), folderName);
        }

        if (destDir.exists()) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("A folder named \"" + destDir.getName()
                    + "\" already exists here. Rename or remove it, then try again.") + "}");
            return;
        }
        destDir.mkdirs();

        String destCanonical = destDir.getCanonicalPath();
        int extractedFiles = 0;
        long totalBytes = 0;
        int totalEntries = 0;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > MAX_ENTRIES) {
                    throw new IOException("archive has too many entries (limit " + MAX_ENTRIES + ")");
                }

                File outFile = new File(destDir, entry.getName());
                String outCanonical = outFile.getCanonicalPath();
                if (!outCanonical.equals(destCanonical) && !outCanonical.startsWith(destCanonical + File.separator)) {
                    throw new IOException("zip entry escapes target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();

                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            totalBytes += n;
                            if (totalBytes > MAX_EXTRACTED_BYTES) {
                                throw new IOException("archive is too large when extracted (limit "
                                        + (MAX_EXTRACTED_BYTES / (1024 * 1024)) + " MB)");
                            }
                            fos.write(buf, 0, n);
                        }
                    }
                    extractedFiles++;
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            // Don't leave a half-extracted mess behind after a failed/aborted attempt.
            try {
                FileUtils.deleteRecursive(destDir);
            } catch (IOException ignored) {
            }
            sendJson(exchange, 500, "{\"error\":" + jsonString("failed to extract: " + e.getMessage()) + "}");
            return;
        }

        sendJson(exchange, 200, "{\"ok\":true,\"extracted\":" + extractedFiles + "}");
    }
}
