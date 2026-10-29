package site.kpeclub.filemanager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Extracts .7z archives, written from the public 7z format description so the plugin needs
 * no outside library. Supported: LZMA, LZMA2, BZip2, Deflate, stored data and the x86 (BCJ) filter, packed headers,
 * solid and non-solid archives. Not supported: passwords (AES) and BCJ2. Links are skipped,
 * names that leave the target folder are refused, and every file is checked with its CRC32.
 */
public final class SevenZExtractor {

    private static final byte[] SIGNATURE = { '7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C };
    private static final int MAX_HEADER = 64 * 1024 * 1024;

    private SevenZExtractor() {}

    public static boolean isSevenZName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".7z");
    }

    // ---------------------------------------------------------------- data model

    private static final class Coder {
        byte[] id;
        int numIn = 1, numOut = 1;
        byte[] props = new byte[0];
    }

    private static final class Folder {
        Coder[] coders;
        int[] bindIn, bindOut;
        int[] packed;            // in-stream index of each packed stream
        long[] unpackSizes;      // per out stream
        boolean hasCrc;
        long crc;
        int firstPackStream;
        int numSubStreams = 1;
        int mainOut() {
            for (int o = 0; o < unpackSizes.length; o++) {
                boolean bound = false;
                for (int b : bindOut) if (b == o) bound = true;
                if (!bound) return o;
            }
            return 0;
        }
        long unpackSize() { return unpackSizes[mainOut()]; }
    }

    private static final class Streams {
        long packPos;
        long[] packSizes = new long[0];
        Folder[] folders = new Folder[0];
        long[][] subSizes;       // per folder
        boolean[][] subHasCrc;
        long[][] subCrc;
    }

    private static final class Reader {
        final byte[] d;
        int p;
        Reader(byte[] d) { this.d = d; }
        int u8() throws IOException {
            if (p >= d.length) throw damaged();
            return d[p++] & 0xff;
        }
        long u32() throws IOException {
            long v = 0;
            for (int i = 0; i < 4; i++) v |= (long) u8() << (8 * i);
            return v;
        }
        long num() throws IOException {
            int first = u8();
            int mask = 0x80;
            long value = 0;
            for (int i = 0; i < 8; i++) {
                if ((first & mask) == 0) {
                    long high = first & (mask - 1);
                    return value + (high << (8 * i));
                }
                value |= (long) u8() << (8 * i);
                mask >>>= 1;
            }
            return value;
        }
        int count(long max) throws IOException {
            long n = num();
            if (n < 0 || n > max) throw damaged();
            return (int) n;
        }
        byte[] bytes(long n) throws IOException {
            if (n < 0 || n > d.length - p) throw damaged();
            byte[] out = new byte[(int) n];
            System.arraycopy(d, p, out, 0, (int) n);
            p += (int) n;
            return out;
        }
        void skip(long n) throws IOException {
            if (n < 0 || n > d.length - p) throw damaged();
            p += (int) n;
        }
    }

    private static IOException damaged() {
        return new IOException("7z archive is damaged (bad header)");
    }

    // ---------------------------------------------------------------- reading the headers

    public static int extract(File archive, File destDir, long maxBytes, int maxEntries) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            byte[] start = new byte[32];
            raf.readFully(start);
            for (int i = 0; i < 6; i++) if (start[i] != SIGNATURE[i]) throw new IOException("not a 7z archive");
            CRC32 crc = new CRC32();
            crc.update(start, 12, 20);
            if (crc.getValue() != le(start, 8, 4)) throw new IOException("7z start header is damaged");
            long nextOffset = le(start, 12, 8);
            long nextSize = le(start, 20, 8);
            long nextCrc = le(start, 28, 4);
            if (nextSize == 0) return 0;                       // empty archive
            if (nextSize < 0 || nextSize > MAX_HEADER || nextOffset < 0 || 32 + nextOffset + nextSize > raf.length()) {
                throw new IOException("7z archive is damaged or cut off");
            }
            byte[] header = new byte[(int) nextSize];
            raf.seek(32 + nextOffset);
            raf.readFully(header);
            crc.reset();
            crc.update(header);
            if (crc.getValue() != nextCrc) throw new IOException("7z header checksum does not match (damaged file)");

            // The header may itself be packed (possibly more than once).
            for (int round = 0; round < 4; round++) {
                Reader r = new Reader(header);
                int id = r.u8();
                if (id == 0x17) {
                    Streams s = readStreamsInfo(r);
                    if (s.folders.length == 0) throw damaged();
                    header = readFolderFully(raf, s, 0, MAX_HEADER);
                    continue;
                }
                if (id != 0x01) throw damaged();
                return readHeaderAndExtract(raf, r, destDir, maxBytes, maxEntries);
            }
            throw damaged();
        }
    }

    private static byte[] readFolderFully(RandomAccessFile raf, Streams s, int folderIdx, long limit) throws IOException {
        Folder f = s.folders[folderIdx];
        long size = f.unpackSize();
        if (size < 0 || size > limit) throw new IOException("7z header is too large");
        byte[] out = new byte[(int) size];
        try (InputStream in = openFolder(raf, s, folderIdx)) {
            int got = 0;
            while (got < out.length) {
                int n = in.read(out, got, out.length - got);
                if (n < 0) throw new IOException("7z archive is damaged or cut off");
                got += n;
            }
        }
        if (f.hasCrc) {
            CRC32 c = new CRC32();
            c.update(out);
            if (c.getValue() != f.crc) throw new IOException("7z header checksum does not match (damaged file)");
        }
        return out;
    }

    private static Streams readStreamsInfo(Reader r) throws IOException {
        Streams s = new Streams();
        int id = r.u8();
        if (id == 0x06) {                                      // pack info
            s.packPos = r.num();
            int n = r.count(1 << 24);
            s.packSizes = new long[n];
            while (true) {
                int t = r.u8();
                if (t == 0) break;
                if (t == 0x09) {
                    for (int i = 0; i < n; i++) s.packSizes[i] = r.num();
                } else if (t == 0x0A) {
                    readDigests(r, n);
                } else {
                    throw damaged();
                }
            }
            id = r.u8();
        }
        if (id == 0x07) {                                      // unpack info
            if (r.u8() != 0x0B) throw damaged();
            int nf = r.count(1 << 22);
            if (r.u8() != 0) throw new IOException("7z archive uses external headers, which are not supported");
            s.folders = new Folder[nf];
            int packBase = 0;
            for (int i = 0; i < nf; i++) {
                s.folders[i] = readFolder(r);
                s.folders[i].firstPackStream = packBase;
                packBase += s.folders[i].packed.length;
            }
            if (r.u8() != 0x0C) throw damaged();
            for (Folder f : s.folders) {
                int outs = 0;
                for (Coder c : f.coders) outs += c.numOut;
                f.unpackSizes = new long[outs];
                for (int o = 0; o < outs; o++) f.unpackSizes[o] = r.num();
            }
            while (true) {
                int t = r.u8();
                if (t == 0) break;
                if (t == 0x0A) {
                    boolean[] def = new boolean[nf];
                    long[] crcs = readDigests(r, nf, def);
                    for (int i = 0; i < nf; i++) { s.folders[i].hasCrc = def[i]; s.folders[i].crc = crcs[i]; }
                } else {
                    throw damaged();
                }
            }
            id = r.u8();
        }
        // Sub streams: how a folder's data splits into files.
        int nf = s.folders.length;
        s.subSizes = new long[nf][];
        s.subHasCrc = new boolean[nf][];
        s.subCrc = new long[nf][];
        if (id == 0x08) {
            id = r.u8();
            if (id == 0x0D) {
                for (Folder f : s.folders) f.numSubStreams = r.count(1 << 24);
                id = r.u8();
            }
            for (int i = 0; i < nf; i++) {
                int n = s.folders[i].numSubStreams;
                s.subSizes[i] = new long[n];
                if (n == 0) continue;
                long sum = 0;
                if (id == 0x09) {
                    for (int j = 0; j < n - 1; j++) { s.subSizes[i][j] = r.num(); sum += s.subSizes[i][j]; }
                } else if (n > 1) {
                    throw damaged();
                }
                s.subSizes[i][n - 1] = s.folders[i].unpackSize() - sum;
                if (s.subSizes[i][n - 1] < 0) throw damaged();
            }
            if (id == 0x09) id = r.u8();
            int unknown = 0;
            for (int i = 0; i < nf; i++) {
                Folder f = s.folders[i];
                s.subHasCrc[i] = new boolean[f.numSubStreams];
                s.subCrc[i] = new long[f.numSubStreams];
                if (f.numSubStreams == 1 && f.hasCrc) {
                    s.subHasCrc[i][0] = true;
                    s.subCrc[i][0] = f.crc;
                } else {
                    unknown += f.numSubStreams;
                }
            }
            while (id != 0x00) {
                if (id == 0x0A) {
                    boolean[] def = new boolean[unknown];
                    long[] crcs = readDigests(r, unknown, def);
                    int k = 0;
                    for (int i = 0; i < nf; i++) {
                        Folder f = s.folders[i];
                        if (f.numSubStreams == 1 && f.hasCrc) continue;
                        for (int j = 0; j < f.numSubStreams; j++, k++) {
                            s.subHasCrc[i][j] = def[k];
                            s.subCrc[i][j] = crcs[k];
                        }
                    }
                } else {
                    throw damaged();
                }
                id = r.u8();
            }
            id = r.u8();
        } else {
            for (int i = 0; i < nf; i++) {
                Folder f = s.folders[i];
                s.subSizes[i] = new long[] { f.unpackSize() };
                s.subHasCrc[i] = new boolean[] { f.hasCrc };
                s.subCrc[i] = new long[] { f.crc };
            }
        }
        if (id != 0x00) throw damaged();
        return s;
    }

    private static long[] readDigests(Reader r, int n) throws IOException {
        return readDigests(r, n, new boolean[n]);
    }

    private static long[] readDigests(Reader r, int n, boolean[] defined) throws IOException {
        int all = r.u8();
        if (all != 0) {
            java.util.Arrays.fill(defined, true);
        } else {
            readBits(r, defined);
        }
        long[] crcs = new long[n];
        for (int i = 0; i < n; i++) if (defined[i]) crcs[i] = r.u32();
        return crcs;
    }

    private static void readBits(Reader r, boolean[] out) throws IOException {
        int mask = 0, cur = 0;
        for (int i = 0; i < out.length; i++) {
            if (mask == 0) { cur = r.u8(); mask = 0x80; }
            out[i] = (cur & mask) != 0;
            mask >>>= 1;
        }
    }

    private static Folder readFolder(Reader r) throws IOException {
        Folder f = new Folder();
        int nc = r.count(32);
        if (nc == 0) throw damaged();
        f.coders = new Coder[nc];
        int totalIn = 0, totalOut = 0;
        for (int i = 0; i < nc; i++) {
            int flag = r.u8();
            Coder c = new Coder();
            if ((flag & 0x80) != 0) throw new IOException("7z archive uses a feature that is not supported");
            c.id = r.bytes(flag & 0x0f);
            if ((flag & 0x10) != 0) {
                c.numIn = r.count(32);
                c.numOut = r.count(32);
            }
            if ((flag & 0x20) != 0) c.props = r.bytes(r.count(1 << 16));
            f.coders[i] = c;
            totalIn += c.numIn;
            totalOut += c.numOut;
        }
        int nb = totalOut - 1;
        if (nb < 0) throw damaged();
        f.bindIn = new int[nb];
        f.bindOut = new int[nb];
        for (int i = 0; i < nb; i++) {
            f.bindIn[i] = r.count(totalIn);
            f.bindOut[i] = r.count(totalOut);
        }
        int np = totalIn - nb;
        if (np < 1) throw damaged();
        f.packed = new int[np];
        if (np == 1) {
            boolean found = false;
            for (int i = 0; i < totalIn && !found; i++) {
                boolean bound = false;
                for (int b : f.bindIn) if (b == i) bound = true;
                if (!bound) { f.packed[0] = i; found = true; }
            }
            if (!found) throw damaged();
        } else {
            for (int i = 0; i < np; i++) f.packed[i] = r.count(totalIn);
        }
        return f;
    }

    // ---------------------------------------------------------------- decoding a folder

    /** The decoded data of one folder (all its files one after another). */
    private static InputStream openFolder(RandomAccessFile raf, Streams s, int folderIdx) throws IOException {
        Folder f = s.folders[folderIdx];
        if (f.firstPackStream < 0 || (long) f.firstPackStream + f.packed.length > s.packSizes.length) throw damaged();
        long[] packOffsets = new long[f.packed.length];
        long[] packLens = new long[f.packed.length];
        long before = 0;
        for (int i = 0; i < f.firstPackStream; i++) before += s.packSizes[i];
        for (int k = 0; k < f.packed.length; k++) {
            int idx = f.firstPackStream + k;
            if (idx >= s.packSizes.length) throw damaged();
            packOffsets[k] = 32 + s.packPos + before;
            packLens[k] = s.packSizes[idx];
            before += s.packSizes[idx];
        }
        return buildStream(raf, f, f.mainOut(), packOffsets, packLens, 0);
    }

    private static InputStream buildStream(RandomAccessFile raf, Folder f, int outIndex,
                                           long[] packOffsets, long[] packLens, int depth) throws IOException {
        if (depth > f.coders.length) throw damaged();      // coders that point at each other in a circle
        int outBase = 0, inBase = 0;
        Coder coder = null;
        for (Coder c : f.coders) {
            if (outIndex < outBase + c.numOut) { coder = c; break; }
            outBase += c.numOut;
            inBase += c.numIn;
        }
        if (coder == null) throw damaged();
        if (coder.numIn != 1 || coder.numOut != 1) {
            throw new IOException("this 7z archive uses a method that is not supported (" + hex(coder.id) + ")");
        }
        InputStream src = null;
        for (int b = 0; b < f.bindIn.length; b++) {
            if (f.bindIn[b] == inBase) src = buildStream(raf, f, f.bindOut[b], packOffsets, packLens, depth + 1);
        }
        if (src == null) {
            for (int k = 0; k < f.packed.length; k++) {
                if (f.packed[k] == inBase) src = new FileRegion(raf, packOffsets[k], packLens[k]);
            }
        }
        if (src == null) throw damaged();
        return decodeWith(coder, src, f.unpackSizes[outIndex]);
    }

    private static InputStream decodeWith(Coder c, InputStream src, long size) throws IOException {
        String id = hex(c.id);
        switch (id) {
            case "00":
                return new Limited(src, size);
            case "030101":
                return new Limited(new LzmaInputStream(src, c.props, size), size);
            case "21":
                if (c.props.length < 1) throw damaged();
                return new Limited(new Lzma2InputStream(src, Lzma2InputStream.dictSizeFromProp(c.props[0] & 0xff), size), size);
            case "03030103":
                if (c.props.length != 0 && c.props.length != 4) throw damaged();
                return new Limited(new BcjX86InputStream(src, c.props.length == 4 ? (int) le(c.props, 0, 4) : 0), size);
            case "040202":
                return new Limited(new Bzip2InputStream(src), size);
            case "040108":
                return new Limited(new InflaterInputStream(src, new Inflater(true)), size);
            case "06f10701":
                throw new IOException("this 7z archive is password protected, which is not supported");
            default:
                throw new IOException("this 7z archive uses a method that is not supported (" + id + ")");
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** A stretch of the archive file, read through one shared file handle. */
    private static final class FileRegion extends InputStream {
        private final RandomAccessFile raf;
        private long pos;
        private final long end;
        private final byte[] buf = new byte[1 << 16];
        private int bufPos, bufLen;
        FileRegion(RandomAccessFile raf, long offset, long length) {
            this.raf = raf;
            this.pos = offset;
            this.end = offset + length;
        }
        @Override public int read() throws IOException {
            if (bufPos >= bufLen && !refill()) return -1;
            return buf[bufPos++] & 0xff;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (bufPos >= bufLen && !refill()) return -1;
            int n = Math.min(len, bufLen - bufPos);
            System.arraycopy(buf, bufPos, b, off, n);
            bufPos += n;
            return n;
        }
        private boolean refill() throws IOException {
            if (pos >= end) return false;
            raf.seek(pos);
            int n = raf.read(buf, 0, (int) Math.min(buf.length, end - pos));
            if (n <= 0) return false;
            pos += n;
            bufPos = 0;
            bufLen = n;
            return true;
        }
    }

    /** Stops after exactly {@code limit} bytes. */
    private static final class Limited extends InputStream {
        private final InputStream in;
        private long left;
        Limited(InputStream in, long limit) { this.in = in; this.left = limit; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }

    // ---------------------------------------------------------------- files

    private static int readHeaderAndExtract(RandomAccessFile raf, Reader r, File destDir,
                                            long maxBytes, int maxEntries) throws IOException {
        Streams s = new Streams();
        int id = r.u8();
        if (id == 0x02) {                                      // archive properties: not needed
            while (true) {
                long t = r.num();
                if (t == 0) break;
                r.skip(r.num());
            }
            id = r.u8();
        }
        if (id == 0x03) {                                      // additional streams: not needed
            readStreamsInfo(r);
            id = r.u8();
        }
        if (id == 0x04) {
            s = readStreamsInfo(r);
            id = r.u8();
        }
        if (id == 0x00) return 0;                              // no files
        if (id != 0x05) throw damaged();

        int numFiles = r.count(Math.max(maxEntries, 1) + 1L);
        if (numFiles > maxEntries) throw new IOException("archive has too many entries (limit " + maxEntries + ")");
        boolean[] emptyStream = new boolean[numFiles];
        boolean[] emptyFile = null;
        boolean[] anti = null;
        String[] names = new String[numFiles];
        boolean[] hasAttr = new boolean[numFiles];
        long[] attr = new long[numFiles];
        int numEmpty = 0;

        while (true) {
            long type = r.num();
            if (type == 0) break;
            long size = r.num();
            byte[] data = r.bytes(size);
            Reader p = new Reader(data);
            if (type == 0x0E) {
                readBits(p, emptyStream);
                numEmpty = 0;
                for (boolean b : emptyStream) if (b) numEmpty++;
            } else if (type == 0x0F) {
                emptyFile = new boolean[numEmpty];
                readBits(p, emptyFile);
            } else if (type == 0x10) {
                anti = new boolean[numEmpty];
                readBits(p, anti);
            } else if (type == 0x11) {
                if (p.u8() != 0) throw new IOException("7z archive uses external names, which are not supported");
                int at = p.p;
                for (int i = 0; i < numFiles; i++) {
                    int e = at;
                    while (true) {
                        if (e + 1 >= data.length) throw damaged();
                        if (data[e] == 0 && data[e + 1] == 0) break;
                        e += 2;
                    }
                    names[i] = new String(data, at, e - at, StandardCharsets.UTF_16LE);
                    at = e + 2;
                }
            } else if (type == 0x15) {
                boolean[] def = new boolean[numFiles];
                if (p.u8() != 0) java.util.Arrays.fill(def, true); else readBits(p, def);
                if (p.u8() != 0) throw new IOException("7z archive uses external attributes, which are not supported");
                for (int i = 0; i < numFiles; i++) {
                    if (def[i]) { hasAttr[i] = true; attr[i] = p.u32(); }
                }
            }
            // other properties (times, padding) are not needed
        }

        String destCanonical = destDir.getCanonicalPath();
        int files = 0;
        long total = 0;
        int emptyIdx = 0;
        int folderIdx = -1;
        int subIdx = 0;
        InputStream folderIn = null;
        byte[] buf = new byte[32 * 1024];
        try {
            for (int i = 0; i < numFiles; i++) {
                String name = names[i];
                if (name == null || name.isEmpty()) throw new IOException("7z archive has an entry without a name");
                name = name.replace('\\', '/');
                boolean isEmptyStream = emptyStream[i];
                boolean isDir = false, isAnti = false;
                if (isEmptyStream) {
                    boolean emptyIsFile = emptyFile != null && emptyIdx < emptyFile.length && emptyFile[emptyIdx];
                    isAnti = anti != null && emptyIdx < anti.length && anti[emptyIdx];
                    isDir = !emptyIsFile;
                    emptyIdx++;
                }
                long mode = hasAttr[i] && (attr[i] & 0x8000) != 0 ? (attr[i] >>> 16) : 0;
                boolean isLink = (mode & 0xF000) == 0xA000;
                if (hasAttr[i] && (attr[i] & 0x10) != 0 && isEmptyStream) isDir = true;
                if (isLink) isDir = false;                         // a link is never made into a folder

                File out = new File(destDir, name);
                String outCanonical = out.getCanonicalPath();
                if (!outCanonical.equals(destCanonical) && !outCanonical.startsWith(destCanonical + File.separator)) {
                    throw new IOException("7z entry escapes target directory: " + name);
                }

                long fileSize = 0;
                long expectCrc = 0;
                boolean hasCrc = false;
                if (!isEmptyStream) {
                    // Move to the folder (and file inside it) this entry's data belongs to.
                    while (folderIn == null || subIdx >= s.folders[folderIdx].numSubStreams) {
                        if (folderIn != null) { folderIn.close(); folderIn = null; }
                        folderIdx++;
                        if (folderIdx >= s.folders.length) throw damaged();
                        subIdx = 0;
                        if (s.folders[folderIdx].numSubStreams > 0) folderIn = openFolder(raf, s, folderIdx);
                    }
                    fileSize = s.subSizes[folderIdx][subIdx];
                    hasCrc = s.subHasCrc[folderIdx][subIdx];
                    expectCrc = s.subCrc[folderIdx][subIdx];
                    subIdx++;
                }

                if (isAnti) continue;
                if (outCanonical.equals(destCanonical) && !isDir) {  // "." names the folder itself
                    throw new IOException("7z entry has an invalid name: " + name);
                }
                if (isDir) {
                    out.mkdirs();
                    continue;
                }
                boolean skip = isLink;
                OutputStream os = null;
                if (!skip) {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    if (total + fileSize > maxBytes) {
                        throw new IOException("archive is too large when extracted (limit " + (maxBytes / (1024 * 1024)) + " MB)");
                    }
                    os = new FileOutputStream(out);
                }
                CRC32 crc = new CRC32();
                try {
                    long left = fileSize;
                    while (left > 0) {
                        int n = folderIn.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("7z archive is damaged or cut off (" + name + ")");
                        crc.update(buf, 0, n);
                        if (os != null) os.write(buf, 0, n);
                        left -= n;
                    }
                } finally {
                    if (os != null) os.close();
                }
                if (hasCrc && crc.getValue() != expectCrc) {
                    throw new IOException("checksum of " + name + " does not match (damaged archive)");
                }
                if (!skip) {
                    total += fileSize;
                    if ((mode & 0100) != 0) out.setExecutable(true, false);
                    files++;
                }
            }
        } finally {
            if (folderIn != null) folderIn.close();
        }
        return files;
    }

    private static long le(byte[] b, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v |= (b[off + i] & 0xffL) << (8 * i);
        return v;
    }
}
