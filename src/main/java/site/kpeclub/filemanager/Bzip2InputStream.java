package site.kpeclub.filemanager;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads bzip2 data (.bz2, and the .tar.bz2 inside). Written from the public format description,
 * so the plugin needs no outside library. Several streams one after another (as made by
 * pbzip2) are read as one. Every block and the whole stream are checked against their CRC.
 */
public final class Bzip2InputStream extends InputStream {

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++) c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            CRC_TABLE[i] = c;
        }
    }

    private final InputStream in;

    // Bit reader: bits come from the top of each byte.
    private long bitBuf;
    private int bitCount;

    private boolean streamOpen;      // between the "BZh" header and the end-of-stream marker
    private boolean finished;
    private int blockSizeMax;
    private int streamCrc;

    // Current block.
    private int[] tt;
    private int blockLen;
    private int blockPos;
    private int tPos;
    private int storedBlockCrc;
    private int blockCrc;

    // Undoing the first run-length step: 4 equal bytes are followed by a repeat count.
    private int lastCh = -1;
    private int runLen;
    private int repeatLeft;
    private int repeatCh;

    public Bzip2InputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len == 0) return 0;
        int count = 0;
        while (count < len) {
            int b = nextByte();
            if (b < 0) {
                if (!openNextBlock()) break;
                continue;
            }
            dst[off + count++] = (byte) b;
        }
        return count == 0 ? -1 : count;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ---------------------------------------------------------------- bits

    private int readBit() throws IOException {
        return readBits(1);
    }

    private int readBits(int n) throws IOException {
        while (bitCount < n) {
            int b = in.read();
            if (b < 0) throw new IOException("bzip2 data is cut off");
            bitBuf = (bitBuf << 8) | b;
            bitCount += 8;
        }
        int result = (int) ((bitBuf >>> (bitCount - n)) & ((1L << n) - 1));
        bitCount -= n;
        bitBuf &= (1L << bitCount) - 1;
        return result;
    }

    // ---------------------------------------------------------------- blocks

    /** Reads the header of the next block. Returns false at the real end of the data. */
    private boolean openNextBlock() throws IOException {
        if (finished) return false;
        while (true) {
            if (!streamOpen) {
                int b = in.read();
                if (b < 0) {
                    if (!anyStreamDone) throw new IOException("not a bzip2 file");   // an empty file is no bzip2 data
                    finished = true;                                      // end after a finished stream
                    return false;
                }
                int z = in.read();
                int h = in.read();
                int level = in.read();
                if (b != 'B' || z != 'Z' || h != 'h' || level < '1' || level > '9') {
                    if (!anyStreamDone) throw new IOException("not a bzip2 file");
                    finished = true;                                      // trailing bytes after the data
                    return false;
                }
                blockSizeMax = (level - '0') * 100_000;
                streamOpen = true;
                streamCrc = 0;
                bitBuf = 0;
                bitCount = 0;
            }
            long magic = ((long) readBits(24) << 24) | readBits(24);
            if (magic == 0x177245385090L) {                               // end of this stream
                int stored = readBits(32);
                if (stored != streamCrc) throw new IOException("bzip2 stream checksum does not match (damaged file)");
                bitCount = 0;                                             // the rest of the byte is padding
                bitBuf = 0;
                streamOpen = false;
                anyStreamDone = true;
                continue;
            }
            if (magic != 0x314159265359L) throw new IOException("bzip2 data is damaged (bad block header)");
            storedBlockCrc = readBits(32);
            readBlock();
            return true;
        }
    }

    private boolean anyStreamDone;

    private void readBlock() throws IOException {
        if (readBit() != 0) throw new IOException("old bzip2 randomised blocks are not supported");
        int origPtr = readBits(24);

        // Which byte values occur in the block.
        int used16 = readBits(16);
        byte[] seqToUnseq = new byte[256];
        int nInUse = 0;
        for (int i = 0; i < 16; i++) {
            if ((used16 & (0x8000 >> i)) != 0) {
                int bits = readBits(16);
                for (int j = 0; j < 16; j++) {
                    if ((bits & (0x8000 >> j)) != 0) seqToUnseq[nInUse++] = (byte) (i * 16 + j);
                }
            }
        }
        if (nInUse == 0) throw new IOException("bzip2 data is damaged (empty block)");
        int alphaSize = nInUse + 2;

        int nGroups = readBits(3);
        if (nGroups < 2 || nGroups > 6) throw new IOException("bzip2 data is damaged (tables)");
        int nSelectors = readBits(15);
        if (nSelectors < 1) throw new IOException("bzip2 data is damaged (selectors)");

        byte[] selectors = new byte[nSelectors];
        byte[] order = new byte[nGroups];
        for (int i = 0; i < nGroups; i++) order[i] = (byte) i;
        for (int i = 0; i < nSelectors; i++) {
            int j = 0;
            while (readBit() == 1) {
                if (++j >= nGroups) throw new IOException("bzip2 data is damaged (selector)");
            }
            byte v = order[j];
            System.arraycopy(order, 0, order, 1, j);
            order[0] = v;
            selectors[i] = v;
        }

        // Code lengths of each Huffman table, then the decoding tables.
        int[][] limit = new int[nGroups][23];
        int[][] base = new int[nGroups][24];
        int[][] perm = new int[nGroups][alphaSize];
        int[] minLens = new int[nGroups];
        for (int t = 0; t < nGroups; t++) {
            int[] len = new int[alphaSize];
            int curr = readBits(5);
            for (int i = 0; i < alphaSize; i++) {
                while (true) {
                    if (curr < 1 || curr > 20) throw new IOException("bzip2 data is damaged (code length)");
                    if (readBit() == 0) break;
                    curr += readBit() == 0 ? 1 : -1;
                }
                len[i] = curr;
            }
            int minLen = 32, maxLen = 0;
            for (int l : len) { if (l > maxLen) maxLen = l; if (l < minLen) minLen = l; }
            int pp = 0;
            for (int i = minLen; i <= maxLen; i++) {
                for (int j = 0; j < alphaSize; j++) if (len[j] == i) perm[t][pp++] = j;
            }
            int[] b = base[t];
            for (int i = 0; i < alphaSize; i++) b[len[i] + 1]++;
            for (int i = 1; i < 24; i++) b[i] += b[i - 1];
            int vec = 0;
            for (int i = minLen; i <= maxLen; i++) {
                vec += b[i + 1] - b[i];
                limit[t][i] = vec - 1;
                vec <<= 1;
            }
            for (int i = minLen + 1; i <= maxLen; i++) b[i] = ((limit[t][i - 1] + 1) << 1) - b[i];
            minLens[t] = minLen;
        }

        // Decode the symbols: run lengths of the first byte, and move-to-front positions.
        if (tt == null || tt.length < blockSizeMax) tt = new int[blockSizeMax];
        int[] counts = new int[256];
        byte[] mtf = new byte[256];
        for (int i = 0; i < 256; i++) mtf[i] = (byte) i;
        int eob = nInUse + 1;
        int nblock = 0;
        int groupNo = -1;
        int groupPos = 0;
        int sym = -2;

        int runBits = 0;     // accumulating RUNA/RUNB run length
        int runAdd = 1;
        boolean inRun = false;

        while (true) {
            // next symbol
            if (groupPos == 0) {
                groupNo++;
                if (groupNo >= nSelectors) throw new IOException("bzip2 data is damaged (ran out of selectors)");
                groupPos = 50;
            }
            groupPos--;
            int g = selectors[groupNo];
            int zn = minLens[g];
            int zvec = readBits(zn);
            while (zvec > limit[g][zn]) {
                if (++zn > 20) throw new IOException("bzip2 data is damaged (symbol)");
                zvec = (zvec << 1) | readBit();
            }
            int idx = zvec - base[g][zn];
            if (idx < 0 || idx >= alphaSize) throw new IOException("bzip2 data is damaged (symbol index)");
            sym = perm[g][idx];

            if (sym == 0 || sym == 1) {                   // RUNA / RUNB
                if (!inRun) { inRun = true; runBits = 0; runAdd = 1; }
                if (runAdd > (1 << 21)) throw new IOException("bzip2 data is damaged (run too long)");
                runBits += sym == 0 ? runAdd : runAdd << 1;
                runAdd <<= 1;
                continue;
            }
            if (inRun) {
                int uc = seqToUnseq[mtf[0] & 0xff] & 0xff;
                if (nblock + runBits > blockSizeMax) throw new IOException("bzip2 data is damaged (block too big)");
                counts[uc] += runBits;
                for (int k = 0; k < runBits; k++) tt[nblock++] = uc;
                inRun = false;
            }
            if (sym == eob) break;
            int nn = sym - 1;
            byte v = mtf[nn];
            System.arraycopy(mtf, 0, mtf, 1, nn);
            mtf[0] = v;
            int uc = seqToUnseq[v & 0xff] & 0xff;
            if (nblock >= blockSizeMax) throw new IOException("bzip2 data is damaged (block too big)");
            counts[uc]++;
            tt[nblock++] = uc;
        }
        if (origPtr < 0 || origPtr >= nblock) throw new IOException("bzip2 data is damaged (start position)");

        // Undo the Burrows-Wheeler transform.
        int[] cftab = new int[257];
        for (int i = 0; i < 256; i++) cftab[i + 1] = cftab[i] + counts[i];
        for (int i = 0; i < nblock; i++) {
            int uc = tt[i] & 0xff;
            tt[cftab[uc]++] |= i << 8;
        }
        blockLen = nblock;
        blockPos = 0;
        tPos = tt[origPtr] >>> 8;
        lastCh = -1;
        runLen = 0;
        repeatLeft = 0;
        blockCrc = 0xFFFFFFFF;
    }

    /** Next byte of the current block, or -1 when the block is used up (after checking its CRC). */
    private int nextByte() throws IOException {
        if (tt == null || blockLen < 0) return -1;
        if (repeatLeft > 0) {
            repeatLeft--;
            return emit(repeatCh);
        }
        if (blockPos >= blockLen) {
            int crc = ~blockCrc;
            if (crc != storedBlockCrc) throw new IOException("bzip2 block checksum does not match (damaged file)");
            streamCrc = ((streamCrc << 1) | (streamCrc >>> 31)) ^ crc;
            blockLen = -1;
            return -1;
        }
        tPos = tt[tPos];
        int ch = tPos & 0xff;
        tPos >>>= 8;
        blockPos++;

        if (runLen == 4) {                               // this byte is a repeat count
            runLen = 0;
            lastCh = -1;
            repeatLeft = ch;
            if (repeatLeft > 0) {
                repeatLeft--;
                return emit(repeatCh);
            }
            return nextByte();
        }
        if (ch == lastCh) runLen++; else { runLen = 1; lastCh = ch; }
        repeatCh = ch;
        return emit(ch);
    }

    private int emit(int ch) {
        blockCrc = (blockCrc << 8) ^ CRC_TABLE[((blockCrc >>> 24) ^ ch) & 0xff];
        return ch;
    }
}
