package site.kpeclub.filemanager;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * LZMA decoder (the compression inside .xz and .7z), written from the public LZMA format
 * description so the plugin needs no outside library. It keeps the window of recent output
 * (the "dictionary") and hands decoded bytes to a sink. LZMA2 reuses it chunk by chunk.
 */
final class LzmaDecoder {

    private static final int PROB_INIT = 1 << 10;
    private static final int STATES = 12;
    private static final int POS_STATES_MAX = 16;
    private static final int MATCH_MIN_LEN = 2;
    private static final int END_POS_MODEL_INDEX = 14;
    private static final int FULL_DISTANCES = 1 << (END_POS_MODEL_INDEX >> 1);
    private static final int ALIGN_BITS = 4;

    // ---- window
    private final byte[] win;
    private final int winSize;
    private int winPos;
    private int streamPos;
    private final OutputStream sink;
    private final int dictSize;
    /** Bytes decoded since the last dictionary reset: a match cannot reach back further. */
    private long pos;

    // ---- range decoder
    private InputStream rcIn;
    private final byte[] inBuf = new byte[1 << 14];
    private int inPos, inLen;
    private int range, code;

    // ---- model
    private int lc, lp, pb;
    private int posMask;
    private short[] literalProbs;
    private final short[] isMatch = new short[STATES << 4];
    private final short[] isRep = new short[STATES];
    private final short[] isRepG0 = new short[STATES];
    private final short[] isRepG1 = new short[STATES];
    private final short[] isRepG2 = new short[STATES];
    private final short[] isRep0Long = new short[STATES << 4];
    private final short[][] posSlot = new short[4][1 << 6];
    private final short[] posProbs = new short[1 + FULL_DISTANCES - END_POS_MODEL_INDEX];
    private final short[] align = new short[1 << ALIGN_BITS];
    private final LenDecoder lenDec = new LenDecoder();
    private final LenDecoder repLenDec = new LenDecoder();

    private int state;
    private int rep0, rep1, rep2, rep3;
    private boolean sawEndMarker;

    LzmaDecoder(int dictSize, int windowSize, OutputStream sink) {
        this.dictSize = dictSize;
        this.winSize = Math.max(4096, windowSize);
        this.win = new byte[winSize];
        this.sink = sink;
    }

    boolean sawEndMarker() {
        return sawEndMarker;
    }

    void setProps(int lc, int lp, int pb) {
        if (lc > 8 || lp > 4 || pb > 4) throw new IllegalArgumentException("bad LZMA properties");
        this.lc = lc;
        this.lp = lp;
        this.pb = pb;
        this.posMask = (1 << pb) - 1;
        literalProbs = new short[0x300 << (lc + lp)];
    }

    void setPropsByte(int d) throws IOException {
        if (d >= 9 * 5 * 5) throw new IOException("LZMA properties are damaged");
        int lcv = d % 9;
        d /= 9;
        setProps(lcv, d % 5, d / 5);
    }

    void resetState() {
        java.util.Arrays.fill(literalProbs, (short) PROB_INIT);
        java.util.Arrays.fill(isMatch, (short) PROB_INIT);
        java.util.Arrays.fill(isRep, (short) PROB_INIT);
        java.util.Arrays.fill(isRepG0, (short) PROB_INIT);
        java.util.Arrays.fill(isRepG1, (short) PROB_INIT);
        java.util.Arrays.fill(isRepG2, (short) PROB_INIT);
        java.util.Arrays.fill(isRep0Long, (short) PROB_INIT);
        for (short[] s : posSlot) java.util.Arrays.fill(s, (short) PROB_INIT);
        java.util.Arrays.fill(posProbs, (short) PROB_INIT);
        java.util.Arrays.fill(align, (short) PROB_INIT);
        lenDec.reset();
        repLenDec.reset();
        state = 0;
        rep0 = rep1 = rep2 = rep3 = 0;
        sawEndMarker = false;
    }

    void resetDict() {
        pos = 0;
    }

    // ---------------------------------------------------------------- range decoder

    void startRange(InputStream in) throws IOException {
        rcIn = in;
        inPos = inLen = 0;
        range = 0xFFFFFFFF;
        code = 0;
        for (int i = 0; i < 5; i++) code = (code << 8) | readByte();
    }

    private int readByte() throws IOException {
        if (inPos >= inLen) {
            int n = rcIn.read(inBuf, 0, inBuf.length);
            if (n <= 0) throw new EOFException("compressed data is cut off");
            inLen = n;
            inPos = 0;
        }
        return inBuf[inPos++] & 0xff;
    }

    private int bit(short[] probs, int i) throws IOException {
        int p = probs[i];
        int bound = (range >>> 11) * p;
        int result;
        if (Integer.compareUnsigned(code, bound) < 0) {
            range = bound;
            probs[i] = (short) (p + ((2048 - p) >>> 5));
            result = 0;
        } else {
            range -= bound;
            code -= bound;
            probs[i] = (short) (p - (p >>> 5));
            result = 1;
        }
        if (Integer.compareUnsigned(range, 1 << 24) < 0) {
            range <<= 8;
            code = (code << 8) | readByte();
        }
        return result;
    }

    private int directBits(int count) throws IOException {
        int result = 0;
        for (int i = count; i > 0; i--) {
            range >>>= 1;
            int t = (code - range) >>> 31;
            code -= range & (t - 1);
            result = (result << 1) | (1 - t);
            if (Integer.compareUnsigned(range, 1 << 24) < 0) {
                range <<= 8;
                code = (code << 8) | readByte();
            }
        }
        return result;
    }

    private int tree(short[] probs, int bits) throws IOException {
        int m = 1;
        for (int i = bits; i > 0; i--) m = (m << 1) + bit(probs, m);
        return m - (1 << bits);
    }

    private int reverseTree(short[] probs, int start, int bits) throws IOException {
        int m = 1, symbol = 0;
        for (int i = 0; i < bits; i++) {
            int b = bit(probs, start + m);
            m = (m << 1) + b;
            symbol |= b << i;
        }
        return symbol;
    }

    private final class LenDecoder {
        final short[] choice = new short[2];
        final short[][] low = new short[POS_STATES_MAX][1 << 3];
        final short[][] mid = new short[POS_STATES_MAX][1 << 3];
        final short[] high = new short[1 << 8];

        void reset() {
            java.util.Arrays.fill(choice, (short) PROB_INIT);
            for (short[] s : low) java.util.Arrays.fill(s, (short) PROB_INIT);
            for (short[] s : mid) java.util.Arrays.fill(s, (short) PROB_INIT);
            java.util.Arrays.fill(high, (short) PROB_INIT);
        }

        int decode(int posState) throws IOException {
            if (bit(choice, 0) == 0) return tree(low[posState], 3);
            if (bit(choice, 1) == 0) return 8 + tree(mid[posState], 3);
            return 16 + tree(high, 8);
        }
    }

    // ---------------------------------------------------------------- window

    private void putByte(int b) throws IOException {
        win[winPos++] = (byte) b;
        if (winPos >= winSize) flush();
    }

    private int getByte(int distance) {
        int p = winPos - distance - 1;
        if (p < 0) p += winSize;
        return win[p] & 0xff;
    }

    private void copyMatch(int distance, int len) throws IOException {
        int p = winPos - distance - 1;
        if (p < 0) p += winSize;
        for (; len > 0; len--) {
            if (p >= winSize) p = 0;
            win[winPos++] = win[p++];
            if (winPos >= winSize) flush();
        }
    }

    /** Sends everything decoded so far to the sink. */
    void flush() throws IOException {
        int n = winPos - streamPos;
        if (n > 0) sink.write(win, streamPos, n);
        if (winPos >= winSize) winPos = 0;
        streamPos = winPos;
    }

    /** LZMA2 stored chunk: copies bytes straight into the window. */
    void copyStored(InputStream from, int len) throws IOException {
        byte[] tmp = new byte[Math.min(len, 8192)];
        while (len > 0) {
            int n = from.read(tmp, 0, Math.min(tmp.length, len));
            if (n < 0) throw new EOFException("compressed data is cut off");
            for (int i = 0; i < n; i++) putByte(tmp[i] & 0xff);
            pos += n;
            len -= n;
        }
    }

    // ---------------------------------------------------------------- decoding

    /**
     * Decodes until at least {@code atLeast} bytes were produced or the end marker is met.
     * A match may run a few bytes past the target. Returns the number of bytes produced.
     */
    long decode(long atLeast) throws IOException {
        long start = pos;
        int prevByte = pos > 0 ? getByte(0) : 0;
        while (pos - start < atLeast) {
            int posState = (int) pos & posMask;
            if (bit(isMatch, (state << 4) + posState) == 0) {
                int litState = (((int) pos & ((1 << lp) - 1)) << lc) + (prevByte >>> (8 - lc));
                int base = 0x300 * litState;
                int symbol = 1;
                if (state >= 7) {
                    int matchByte = getByte(rep0);
                    do {
                        int matchBit = (matchByte >> 7) & 1;
                        matchByte <<= 1;
                        int b = bit(literalProbs, base + ((1 + matchBit) << 8) + symbol);
                        symbol = (symbol << 1) | b;
                        if (matchBit != b) break;
                    } while (symbol < 0x100);
                }
                while (symbol < 0x100) symbol = (symbol << 1) | bit(literalProbs, base + symbol);
                prevByte = symbol & 0xff;
                putByte(prevByte);
                pos++;
                state = state < 4 ? 0 : (state < 10 ? state - 3 : state - 6);
                continue;
            }

            int len;
            if (bit(isRep, state) == 1) {
                if (pos == 0) throw new IOException("LZMA data is damaged");
                if (bit(isRepG0, state) == 0) {
                    if (bit(isRep0Long, (state << 4) + posState) == 0) {
                        state = state < 7 ? 9 : 11;           // short repeat: one byte
                        prevByte = getByte(rep0);
                        putByte(prevByte);
                        pos++;
                        continue;
                    }
                } else {
                    int distance;
                    if (bit(isRepG1, state) == 0) {
                        distance = rep1;
                    } else {
                        if (bit(isRepG2, state) == 0) {
                            distance = rep2;
                        } else {
                            distance = rep3;
                            rep3 = rep2;
                        }
                        rep2 = rep1;
                    }
                    rep1 = rep0;
                    rep0 = distance;
                }
                len = repLenDec.decode(posState) + MATCH_MIN_LEN;
                state = state < 7 ? 8 : 11;
            } else {
                rep3 = rep2;
                rep2 = rep1;
                rep1 = rep0;
                len = lenDec.decode(posState) + MATCH_MIN_LEN;
                state = state < 7 ? 7 : 10;
                int lenState = len - MATCH_MIN_LEN;
                if (lenState > 3) lenState = 3;
                int slot = tree(posSlot[lenState], 6);
                if (slot >= 4) {
                    int directBits = (slot >> 1) - 1;
                    rep0 = (2 | (slot & 1)) << directBits;
                    if (slot < END_POS_MODEL_INDEX) {
                        rep0 += reverseTree(posProbs, rep0 - slot, directBits);
                    } else {
                        rep0 += directBits(directBits - ALIGN_BITS) << ALIGN_BITS;
                        rep0 += reverseTree(align, 0, ALIGN_BITS);
                        if (rep0 == -1) {                     // end marker
                            sawEndMarker = true;
                            break;
                        }
                    }
                } else {
                    rep0 = slot;
                }
            }
            if (Integer.compareUnsigned(rep0, winSize) >= 0 || rep0 < 0
                    || (rep0 & 0xFFFFFFFFL) >= pos || (rep0 & 0xFFFFFFFFL) >= (dictSize & 0xFFFFFFFFL)) {
                throw new IOException("LZMA data is damaged (bad distance)");
            }
            copyMatch(rep0, len);
            pos += len;
            prevByte = getByte(0);
        }
        flush();
        return pos - start;
    }
}
