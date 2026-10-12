package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.FileUtils;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * POST /api/zip?dest=<relative path ending in .zip>
 * Body: newline-separated list of relative paths (files or folders) to include.
 */
public class ZipHandler extends BaseApiHandler {

    public ZipHandler(File root, TokenManager tokenManager) {
        super(root, tokenManager);
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        Map<String, String> params = queryParams(exchange);
        String destParam = params.get("dest");
        if (destParam == null || destParam.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"missing dest\"}");
            return;
        }

        File destFile = FileUtils.resolveSafe(root, destParam);
        if (!destFile.getName().toLowerCase().endsWith(".zip")) {
            sendJson(exchange, 400, "{\"error\":\"destination must end in .zip\"}");
            return;
        }
        if (destFile.exists()) {
            sendJson(exchange, 409, "{\"error\":\"a file with that name already exists\"}");
            return;
        }

        String bodyStr = new String(readBody(exchange), StandardCharsets.UTF_8);
        List<File> sources = new ArrayList<>();
        for (String line : bodyStr.split("\n")) {
            String rel = line.trim();
            if (rel.isEmpty()) continue;
            File f = FileUtils.resolveSafe(root, rel);
            if (!f.exists()) {
                sendJson(exchange, 404, "{\"error\":" + jsonString(rel + " does not exist") + "}");
                return;
            }
            sources.add(f);
        }
        if (sources.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"nothing selected to compress\"}");
            return;
        }

        String destCanonical = destFile.getCanonicalPath();
        for (File src : sources) {
            if (src.isDirectory()) {
                String srcCanonical = src.getCanonicalPath();
                if (destCanonical.equals(srcCanonical) || destCanonical.startsWith(srcCanonical + File.separator)) {
                    sendJson(exchange, 400, "{\"error\":\"cannot save the archive inside a folder being compressed\"}");
                    return;
                }
            }
        }

        File destParent = destFile.getParentFile();
        if (destParent != null && !destParent.exists()) destParent.mkdirs();

        try (FileOutputStream fos = new FileOutputStream(destFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (File src : sources) {
                addToZip(zos, src, src.getName());
            }
        } catch (IOException e) {
            destFile.delete();
            sendJson(exchange, 500, "{\"error\":" + jsonString("failed to create zip: " + e.getMessage()) + "}");
            return;
        }

        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private void addToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        // Symbolic links are skipped: following one could copy files from outside the root,
        // or loop forever when a link points back at a parent folder.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return;
        if (file.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entryName + "/"));
            zos.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(zos, child, entryName + "/" + child.getName());
                }
            }
        } else {
            zos.putNextEntry(new ZipEntry(entryName));
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.transferTo(zos);
            }
            zos.closeEntry();
        }
    }
}
