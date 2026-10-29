package site.kpeclub.filemanager;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Reads .xz files (and the .tar.xz inside) that use LZMA2, which is what the xz tool makes by
 * default. Several streams one after another are read as one. Block headers are checked with
 * their CRC32; files made with extra filters (BCJ, delta) are refused with a clear message.
 */
public final class XzInputStream extends PullStream {

    private static final byte[] MAGIC = { (byte) 0xFD, '7', 'z', 'X', 'Z', 0 };

    private static final class Counting extends FilterInputStream {
        long count;
        Counting(InputStream in) { super(in); }
        @Override public int read() throws IOException { int b = in.read(); if (b >= 0) count++; return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    private final Counting in;
    private int checkSize;
    private boolean streamOpen;
    private boolean anyStream;
    private boolean finished;
    private InputStream block;

    public XzInputStream(InputStream raw) {
        this.in = new Counting(raw);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    protected boolean fill() throws IOException {
        while (true) {
            if (finished) return false;
            if (block != null) {
                if (pumpBlock()) return true;
                continue;
            }
            if (!streamOpen) {
                if (!openStream()) { finished = true; return false; }
                streamOpen = true;
            }
            int first = in.read();
            if (first < 0) throw new EOFException("xz data is cut off");
            if (first == 0) {                    // index: the blocks are over
                readIndexAndFooter();
                streamOpen = false;
                continue;
            }
            startBlock(first);
        }
    }

    /** Decodes one more piece of the current block. Returns false when the block is complete. */
    private boolean pumpBlock() throws IOException {
        byte[] tmp = new byte[65536];
        int n = block.read(tmp, 0, tmp.length);
        if (n > 0) {
            buf.write(tmp, 0, n);
            return true;
        }
        // Block finished: padding to a multiple of 4, then the integrity check.
        long used = in.count - blockStart;
        skipBytes((4 - (used & 3)) & 3);
        skipBytes(checkSize);
        block = null;
        return false;
    }

    private long blockStart;

    private boolean openStream() throws IOException {
        // Zero padding (a multiple of 4 bytes) may sit between streams.
        int b;
        do {
            b = in.read();
        } while (b == 0 && anyStream);
        if (b < 0) {
            if (!anyStream) throw new IOException("not an xz file");
            return false;
        }
        if (b != (MAGIC[0] & 0xff)) {
            if (!anyStream) throw new IOException("not an xz file");
            return false;                                        // trailing garbage
        }
        byte[] rest = new byte[5];
        readFully(rest);
        for (int i = 1; i < 6; i++) {
            if (rest[i - 1] != MAGIC[i]) throw new IOException("not an xz file");
        }
        byte[] flags = new byte[2];
        readFully(flags);
        byte[] crc = new byte[4];
        readFully(crc);
        CRC32 c = new CRC32();
        c.update(flags);
        if (c.getValue() != le32(crc, 0) || flags[0] != 0) throw new IOException("xz header is damaged");
        int check = flags[1] & 0x0f;
        checkSize = check == 0 ? 0 : 4 << ((check - 1) / 3);
        anyStream = true;
        return true;
    }

    private void startBlock(int sizeByte) throws IOException {
        int headerLen = (sizeByte + 1) * 4;
        byte[] h = new byte[headerLen];
        h[0] = (byte) sizeByte;
        readFully(h, 1, headerLen - 1);
        CRC32 c = new CRC32();
        c.update(h, 0, headerLen - 4);
        if (c.getValue() != le32(h, headerLen - 4)) throw new IOException("xz block header is damaged");

        int flags = h[1] & 0xff;
        int filters = (flags & 3) + 1;
        int[] p = { 2 };
        long compressed = -1, uncompressed = -1;
        if ((flags & 0x40) != 0) compressed = vli(h, p);
        if ((flags & 0x80) != 0) uncompressed = vli(h, p);
        long id = -1;
        boolean bcj = false;
        int bcjStart = 0;
        byte[] props = new byte[0];
        for (int i = 0; i < filters; i++) {
            id = vli(h, p);
            int ps = (int) vli(h, p);
            if (ps < 0 || p[0] + ps > headerLen - 4) throw new IOException("xz block header is damaged");
            props = java.util.Arrays.copyOfRange(h, p[0], p[0] + ps);
            p[0] += ps;
            boolean last = i == filters - 1;
            if (!last && id == 0x04 && (ps == 0 || ps == 4) && i == 0) {          // x86 filter in front
                bcj = true;
                bcjStart = ps == 4 ? (int) le32(props, 0) : 0;
                continue;
            }
            if (!last || id != 0x21) {
                throw new IOException("this xz file uses a filter that is not supported (id 0x" + Long.toHexString(id) + ")");
            }
        }
        if (props.length != 1) throw new IOException("xz block header is damaged");
        long dict = Lzma2InputStream.dictSizeFromProp(props[0] & 0xff);
        blockStart = in.count;
        InputStream lz = new Lzma2InputStream(in, dict, uncompressed);
        block = bcj ? new BcjX86InputStream(lz, bcjStart) : lz;
    }

    private void readIndexAndFooter() throws IOException {
        long start = in.count - 1;                 // the indicator byte was already read
        long records = readVli();
        for (long i = 0; i < records; i++) {
            readVli();
            readVli();
        }
        long used = in.count - start;
        skipBytes((4 - (used & 3)) & 3);
        skipBytes(4);                               // CRC32 of the index
        byte[] footer = new byte[12];
        readFully(footer);
        if (footer[10] != 'Y' || footer[11] != 'Z') throw new IOException("xz footer is damaged");
    }

    // ---- small helpers

    private void readFully(byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    private void readFully(byte[] b, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(b, off + got, len - got);
            if (n < 0) throw new EOFException("xz data is cut off");
            got += n;
        }
    }

    private void skipBytes(long n) throws IOException {
        for (long i = 0; i < n; i++) {
            if (in.read() < 0) throw new EOFException("xz data is cut off");
        }
    }

    private long readVli() throws IOException {
        long v = 0;
        for (int i = 0; i < 9; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException("xz data is cut off");
            v |= (long) (b & 0x7f) << (7 * i);
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("xz data is damaged (number too long)");
    }

    private static long vli(byte[] h, int[] p) throws IOException {
        long v = 0;
        for (int i = 0; i < 9; i++) {
            if (p[0] >= h.length) throw new IOException("xz block header is damaged");
            int b = h[p[0]++] & 0xff;
            v |= (long) (b & 0x7f) << (7 * i);
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("xz block header is damaged");
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8) | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }
}
