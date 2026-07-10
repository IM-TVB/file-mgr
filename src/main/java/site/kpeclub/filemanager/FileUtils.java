package site.kpeclub.filemanager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class FileUtils {

    /**
     * Resolves a user-supplied relative path against the configured root,
     * guaranteeing the result cannot escape the root via "../" traversal.
     * Throws SecurityException if the path would escape the root.
     */
    public static File resolveSafe(File root, String relativePath) throws IOException {
        if (relativePath == null) relativePath = "";
        relativePath = relativePath.replace("\\", "/");
        while (relativePath.startsWith("/")) relativePath = relativePath.substring(1);

        File target = new File(root, relativePath);
        Path rootCanonical = root.getCanonicalFile().toPath();
        Path targetCanonical = target.getCanonicalFile().toPath();

        if (!targetCanonical.startsWith(rootCanonical)) {
            throw new SecurityException("Path escapes root directory");
        }
        return targetCanonical.toFile();
    }

    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public static void deleteRecursive(File f) throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        if (!f.delete()) {
            throw new IOException("Failed to delete: " + f.getPath());
        }
    }

    public static String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), unit);
    }
}
