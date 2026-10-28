package site.kpeclub.filemanager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Appends errors and warnings to error.txt in the plugin folder, so they do not flood the
 * server console. When the file grows past 1 MB it is moved to error.txt.1 and a new file
 * is started. Writes are synchronous and no background thread is used.
 */
public final class ErrorLog {

    public static final long MAX_BYTES = 1024L * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File file;

    public ErrorLog(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public synchronized void write(String message) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            if (file.exists() && file.length() > MAX_BYTES) {
                Files.move(file.toPath(), new File(file.getPath() + ".1").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.println("[" + LocalDateTime.now().format(TIME) + "] " + message);
            }
        } catch (IOException e) {
            // Logging must never break the plugin. Fall back to nothing.
        }
    }
}
