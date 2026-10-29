package site.kpeclub.filemanager;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Extracts .tar archives, plain or compressed with gzip, bzip2 or xz, without any outside library.
 *
 * Files and folders are written; symbolic and hard links and special files (devices, pipes)
 * are skipped on purpose, since a link could point outside the target folder. Entry names
 * that would leave the target folder are refused. The same size and entry limits as zip apply.
 */
public final class TarExtractor {

    private static final int BLOCK = 512;

    private TarExtractor() {}

    /** True for names ending in .tar.gz, .tgz, .tar.bz2, .tbz2, .tar.xz, .txz or .tar (any letter case). */
    public static boolean isTarName(String name) {
        return ArchiveFormats.isTar(name);
    }

    /** Length of the archive suffix (.tar.gz = 7, .tgz = 4, .tar = 4 ...), or 0 if it is not a tar name. */
    public static int tarSuffixLength(String name) {
        return ArchiveFormats.tarSuffixLength(name);
    }

    /** Extracts into destDir and returns the number of files written. */
    public static int extract(File archive, File destDir, long maxBytes, int maxEntries) throws IOException {
        try (InputStream in = ArchiveFormats.openDecompressed(archive)) {
            return extract(in, destDir, maxBytes, maxEntries);
        }
    }

    static int extract(InputStream in, File destDir, long maxBytes, int maxEntries) throws IOException {
        String destCanonical = destDir.getCanonicalPath();
        byte[] header = new byte[BLOCK];
        String longName = null;   // from a GNU 'L' entry or a pax "path" record, for the next entry
        int files = 0;
        int entries = 0;
        long total = 0;

        while (true) {
            if (!readBlock(in, header)) break;           // end of file without end marker: accept
            if (isZeroBlock(header)) break;              // end-of-archive marker
            if (!checksumOk(header)) throw new IOException("not a valid tar archive (bad header)");

            String name = cString(header, 0, 100);
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty() && "ustar".equals(cString(header, 257, 5))) name = prefix + "/" + name;
            long size = octal(header, 124, 12);
            int mode = (int) octal(header, 100, 8);
            char type = header[156] == 0 ? '0' : (char) (header[156] & 0xff);

            if (type == 'L') {                            // GNU long file name
                longName = cString(readData(in, size, 1 << 20), 0, (int) Math.min(size, 1 << 20));
                continue;
            }
            if (type == 'x') {                            // pax header for the next entry
                String pax = paxPath(readData(in, size, 1 << 20));
                if (pax != null) longName = pax;
                continue;
            }
            if (type == 'g' || type == 'K') {             // global pax header, long link name: not needed
                skipFully(in, padded(size));
                continue;
            }

            if (longName != null) { name = longName; longName = null; }
            if (++entries > maxEntries) {
                throw new IOException("archive has too many entries (limit " + maxEntries + ")");
            }

            boolean isDir = type == '5' || (type == '0' && name.endsWith("/"));
            boolean isFile = type == '0' || type == '7';  // '7' = contiguous file
            if (!isDir && !isFile) {                      // links, devices, pipes: skipped
                skipFully(in, padded(size));
                continue;
            }

            // Windows tools may write "folder\\file.txt"; treat backslashes as separators.
            String clean = name.replace('\\', '/');
            if (clean.startsWith("./")) clean = clean.substring(2);
            File out = new File(destDir, clean);
            String outCanonical = out.getCanonicalPath();
            if (!outCanonical.equals(destCanonical) && !outCanonical.startsWith(destCanonical + File.separator)) {
                throw new IOException("tar entry escapes target directory: " + name);
            }

            if (outCanonical.equals(destCanonical)) {          // "." or "./": the folder itself
                if (!isDir) throw new IOException("tar entry has an invalid name: " + name);
                skipFully(in, padded(size));
                continue;
            }
            if (isDir) {
                out.mkdirs();
                skipFully(in, padded(size));
                continue;
            }

            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (total + size > maxBytes) {
                throw new IOException("archive is too large when extracted (limit " + (maxBytes / (1024 * 1024)) + " MB)");
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[16 * 1024];
                long left = size;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new EOFException("archive ended in the middle of " + name);
                    fos.write(buf, 0, n);
                    left -= n;
                }
            }
            total += size;
            skipFully(in, padded(size) - size);
            // Keep scripts runnable (start.sh and similar).
            if ((mode & 0100) != 0) out.setExecutable(true, false);
            files++;
        }
        return files;
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int got = 0;
        while (got < BLOCK) {
            int n = in.read(block, got, BLOCK - got);
            if (n < 0) {
                if (got == 0) return false;
                throw new EOFException("archive is cut off");
            }
            got += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static boolean checksumOk(byte[] h) {
        long expected = octal(h, 148, 8);
        long sum = 0, signedSum = 0;
        for (int i = 0; i < BLOCK; i++) {
            boolean checksumField = i >= 148 && i < 156;
            sum += checksumField ? ' ' : (h[i] & 0xff);
            signedSum += checksumField ? ' ' : h[i];     // very old tar programs summed signed bytes
        }
        return sum == expected || signedSum == expected;
    }

    /** Reads a small data section (long name, pax records) and its padding. */
    private static byte[] readData(InputStream in, long size, int limit) throws IOException {
        if (size < 0 || size > limit) throw new IOException("tar header data is too large");
        byte[] data = new byte[(int) size];
        int got = 0;
        while (got < size) {
            int n = in.read(data, got, (int) size - got);
            if (n < 0) throw new EOFException("archive is cut off");
            got += n;
        }
        skipFully(in, padded(size) - size);
        return data;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        byte[] sink = new byte[8192];
        while (count > 0) {
            long s = in.skip(count);
            if (s <= 0) {
                int n = in.read(sink, 0, (int) Math.min(sink.length, count));
                if (n < 0) throw new EOFException("archive is cut off");
                s = n;
            }
            count -= s;
        }
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /** Octal number field, or the base-256 form used for very large values. */
    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7f;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xff);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') { if (v == 0 && c == ' ') continue; else break; }
            if (c < '0' || c > '7') break;
            v = (v << 3) + (c - '0');
        }
        return v;
    }

    /** Finds the "path" record in pax data ("<length> path=<value>\n" records). */
    private static String paxPath(byte[] data) {
        int pos = 0;
        String result = null;
        while (pos < data.length) {
            int space = pos;
            while (space < data.length && data[space] != ' ') space++;
            if (space >= data.length) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || pos + len > data.length) break;
            int valueLen = pos + len - space - 2;                    // without the final "\n"
            if (valueLen < 0) break;
            String record = new String(data, space + 1, valueLen, StandardCharsets.UTF_8);
            if (record.startsWith("path=")) result = record.substring(5);
            pos += len;
        }
        return result;
    }
}
