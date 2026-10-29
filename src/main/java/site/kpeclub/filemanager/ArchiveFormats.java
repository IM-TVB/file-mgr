package site.kpeclub.filemanager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Which archive and compressed-file types the file manager can extract, found by file name. */
public final class ArchiveFormats {

    public static final String SUPPORTED_TEXT = ".zip, .7z, .tar.gz, .tgz, .tar.bz2, .tar.xz, .tar, .gz, .bz2, .xz";

    private ArchiveFormats() {}

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean isZip(String name) {
        return lower(name).endsWith(".zip");
    }

    public static boolean isSevenZ(String name) {
        return lower(name).endsWith(".7z");
    }

    /** A tar archive, plain or compressed with gzip, bzip2 or xz. */
    public static boolean isTar(String name) {
        return tarSuffixLength(name) > 0;
    }

    /** One compressed file (not a tar): .gz, .bz2 or .xz. */
    public static boolean isSingleCompressed(String name) {
        String n = lower(name);
        return !isTar(name) && (n.endsWith(".gz") || n.endsWith(".bz2") || n.endsWith(".xz"));
    }

    public static boolean isSupported(String name) {
        return isZip(name) || isSevenZ(name) || isTar(name) || isSingleCompressed(name);
    }

    /** Length of the tar suffix at the end of the name (.tar.gz = 7 ...), or 0 if it is not a tar name. */
    public static int tarSuffixLength(String name) {
        String n = lower(name);
        String[] suffixes = { ".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz", ".tar" };
        for (String s : suffixes) if (n.endsWith(s)) return s.length();
        return 0;
    }

    /** Length of the ending to cut off to get the name of the extracted folder or file. */
    public static int suffixLength(String name) {
        if (isZip(name) || isSevenZ(name)) return name.toLowerCase(Locale.ROOT).endsWith(".7z") ? 3 : 4;
        if (isTar(name)) return tarSuffixLength(name);
        String n = lower(name);
        if (n.endsWith(".bz2")) return 4;
        if (n.endsWith(".gz") || n.endsWith(".xz")) return 3;
        return 0;
    }

    /** Opens a .tar, .tar.gz, .tar.bz2, .tar.xz, .gz, .bz2 or .xz file and returns the decompressed bytes. */
    public static InputStream openDecompressed(File file) throws IOException {
        String n = lower(file.getName());
        InputStream raw = new BufferedInputStream(new FileInputStream(file), 64 * 1024);
        try {
            if (n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") || n.endsWith(".bz2")) {
                return new Bzip2InputStream(raw);
            }
            if (n.endsWith(".tar.xz") || n.endsWith(".txz") || n.endsWith(".xz")) {
                return new XzInputStream(raw);
            }
            if (n.endsWith(".tar")) return raw;
            return new GZIPInputStream(raw, 64 * 1024);        // .tar.gz, .tgz, .gz
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }
}
