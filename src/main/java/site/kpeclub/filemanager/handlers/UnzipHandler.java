package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.ArchiveFormats;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.SevenZExtractor;
import site.kpeclub.filemanager.TarExtractor;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * POST /api/unzip?path=<relative path to an archive>[&dest=<relative target folder>]
 * Archives: .zip, .7z, .tar, .tar.gz/.tgz, .tar.bz2, .tar.xz. Extracts into a new sibling folder
 * named after the archive (or into `dest` if given). A single compressed file (.gz, .bz2, .xz)
 * is unpacked next to itself, without the ending, instead of into a folder. If the folder already exists, the request is refused unless
 * overwrite=true, which writes the zip's files over same-named files in it and
 * leaves other files in the folder alone.
 */
public class UnzipHandler extends BaseApiHandler {

    // Defense against zip bombs: a small malicious/corrupt zip can expand to
    // an enormous size or file count. These are hard caps, not configurable —
    // if a legitimate archive is bigger than this, extract it some other way.
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB
    private static final int MAX_ENTRIES = 50_000;

    // Older Windows tools store file names in the DOS code page, not UTF-8.
    private static final Charset LEGACY_NAME_CHARSET = Charset.forName("IBM437");

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
        boolean isZip = ArchiveFormats.isZip(pathParam);
        boolean isTar = ArchiveFormats.isTar(pathParam);
        boolean isSevenZ = ArchiveFormats.isSevenZ(pathParam);
        boolean isSingle = ArchiveFormats.isSingleCompressed(pathParam);
        if (!isZip && !isTar && !isSevenZ && !isSingle) {
            sendJson(exchange, 400, "{\"error\":\"not a supported archive (" + ArchiveFormats.SUPPORTED_TEXT + ")\"}");
            return;
        }

        File zipFile = FileUtils.resolveSafe(root, pathParam);
        if (!zipFile.isFile()) {
            sendJson(exchange, 404, "{\"error\":\"zip file not found\"}");
            return;
        }

        String destParam = params.get("dest");
        if (isSingle) {
            extractSingle(exchange, zipFile, destParam, "true".equalsIgnoreCase(params.get("overwrite")));
            return;
        }
        File destDir;
        if (destParam != null && !destParam.isBlank()) {
            destDir = FileUtils.resolveSafe(root, destParam);
        } else {
            String base = zipFile.getName();
            int suffix = ArchiveFormats.suffixLength(base); // ".zip", ".7z", ".tar.gz", ".tgz", ".tar.xz" ...
            // A name like "...zip" would give ".." (the parent folder) after cutting the ending off.
            String folderName = safeBaseName(base.substring(0, base.length() - suffix), base + "-extracted");
            destDir = new File(zipFile.getParentFile(), folderName);
            String rootCanonical = root.getCanonicalPath();
            String destCanonicalCheck = destDir.getCanonicalPath();
            if (!destCanonicalCheck.equals(rootCanonical) && !destCanonicalCheck.startsWith(rootCanonical + File.separator)) {
                sendJson(exchange, 400, "{\"error\":\"target folder is outside the server root\"}");
                return;
            }
        }

        boolean overwrite = "true".equalsIgnoreCase(params.get("overwrite"));
        if (destDir.exists() && !destDir.isDirectory()) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("A file named \"" + destDir.getName()
                    + "\" is in the way. Rename or remove it, then try again.") + "}");
            return;
        }
        if (destDir.isDirectory() && !overwrite) {
            // The client can offer to replace files, then retry with overwrite=true.
            sendJson(exchange, 409, "{\"error\":" + jsonString("A folder with this name already exists here.")
                    + ",\"canOverwrite\":true}");
            return;
        }
        // Only a folder this request creates may be removed after a failure. An existing
        // folder holds the user's files, so it is left as it is.
        boolean createdDir = !destDir.exists();
        destDir.mkdirs();

        int extractedFiles;
        try {
            if (isTar) {
                extractedFiles = TarExtractor.extract(zipFile, destDir, MAX_EXTRACTED_BYTES, MAX_ENTRIES);
            } else if (isSevenZ) {
                extractedFiles = SevenZExtractor.extract(zipFile, destDir, MAX_EXTRACTED_BYTES, MAX_ENTRIES);
            } else try {
                extractedFiles = extractAll(zipFile, destDir, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException badNames) {
                // Entry names are not valid UTF-8, which ZipInputStream reports as an
                // IllegalArgumentException (not an IOException). Start over with the
                // legacy DOS code page.
                if (createdDir) {
                    clearDir(destDir);
                    destDir.mkdirs();
                }
                extractedFiles = extractAll(zipFile, destDir, LEGACY_NAME_CHARSET);
            }
        } catch (Exception e) {
            // Don't leave a half-extracted mess behind after a failed/aborted attempt.
            if (createdDir) clearDir(destDir);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            sendJson(exchange, 500, "{\"error\":" + jsonString("failed to extract: " + message) + "}");
            return;
        }

        sendJson(exchange, 200, "{\"ok\":true,\"extracted\":" + extractedFiles + "}");
    }

    /** .gz, .bz2 or .xz: one compressed file becomes one file next to it (or in `dest`), without the ending. */
    private void extractSingle(HttpExchange exchange, File compressed, String destParam, boolean overwrite) throws IOException {
        String base = compressed.getName();
        String outName = safeBaseName(base.substring(0, base.length() - ArchiveFormats.suffixLength(base)), base + ".out");
        File outDir = (destParam != null && !destParam.isBlank())
                ? FileUtils.resolveSafe(root, destParam)
                : compressed.getParentFile();
        if (outDir.exists() && !outDir.isDirectory()) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("The destination is a file, not a folder.") + "}");
            return;
        }
        File outFile = new File(outDir, outName);
        if (outFile.isDirectory()) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("A folder named \"" + outName
                    + "\" is in the way. Rename or remove it, then try again.") + "}");
            return;
        }
        if (outFile.exists() && !overwrite) {
            sendJson(exchange, 409, "{\"error\":" + jsonString("A file with this name already exists here.")
                    + ",\"canOverwrite\":true}");
            return;
        }
        outDir.mkdirs();
        // Written under a temporary name first, so a failed attempt never destroys an existing file.
        File part = new File(outDir, outName + ".part");
        long total = 0;
        try (java.io.InputStream in = ArchiveFormats.openDecompressed(compressed);
             FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_EXTRACTED_BYTES) {
                    throw new IOException("file is too large when extracted (limit "
                            + (MAX_EXTRACTED_BYTES / (1024 * 1024)) + " MB)");
                }
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        } catch (Exception e) {
            part.delete();
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            sendJson(exchange, 500, "{\"error\":" + jsonString("failed to extract: " + message) + "}");
            return;
        }
        java.nio.file.Files.move(part.toPath(), outFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        sendJson(exchange, 200, "{\"ok\":true,\"extracted\":1}");
    }

    /** The name to use for the extracted folder or file: never blank and never only dots ("." or ".."). */
    private static String safeBaseName(String name, String fallback) {
        String stripped = name.replace(".", "").trim();
        return stripped.isEmpty() ? fallback : name;
    }

    /** Extracts every entry into destDir and returns the number of files written. */
    private static int extractAll(File zipFile, File destDir, Charset nameCharset) throws IOException {
        String destCanonical = destDir.getCanonicalPath();
        int extractedFiles = 0;
        long totalBytes = 0;
        int totalEntries = 0;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile), nameCharset)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > MAX_ENTRIES) {
                    throw new IOException("archive has too many entries (limit " + MAX_ENTRIES + ")");
                }

                // Some Windows tools write "folder\file.txt"; treat backslashes as separators.
                String name = entry.getName().replace('\\', '/');
                File outFile = new File(destDir, name);
                String outCanonical = outFile.getCanonicalPath();
                if (!outCanonical.equals(destCanonical) && !outCanonical.startsWith(destCanonical + File.separator)) {
                    throw new IOException("zip entry escapes target directory: " + entry.getName());
                }

                if (outCanonical.equals(destCanonical)) {          // "." or "./": the folder itself
                    if (!entry.isDirectory()) throw new IOException("zip entry has an invalid name: " + entry.getName());
                    zis.closeEntry();
                    continue;
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
        }
        return extractedFiles;
    }

    /** Removes everything inside destDir (and destDir itself) after a failed attempt. */
    private static void clearDir(File destDir) {
        try {
            FileUtils.deleteRecursive(destDir);
        } catch (IOException ignored) {
            // Best effort: a leftover folder is reported on the next attempt.
        }
    }
}
