package site.kpeclub.filemanager;

import java.io.IOException;
import java.io.InputStream;

/**
 * Undoes the x86 "BCJ" filter that 7-Zip and xz can apply to programs (.exe, .dll) before
 * compressing: it turned relative jump addresses into absolute ones, to compress better.
 */
final class BcjX86InputStream extends PullStream {

    private static final boolean[] ALLOWED = { true, true, true, false, true, false, false, false };
    private static final int[] BIT_NUMBER = { 0, 1, 2, 2, 3, 3, 3, 3 };

    private final InputStream in;
    private final byte[] data = new byte[1 << 16];
    private int have;
    private boolean eof;

    private int prevMask;
    private int prevPos = -5;
    private int nowPos;

    BcjX86InputStream(InputStream in) {
        this(in, 0);
    }

    /** startOffset is the address the filtered data started at (the filter's optional 4-byte property). */
    BcjX86InputStream(InputStream in, int startOffset) {
        this.in = in;
        this.nowPos = startOffset;
        this.prevPos = startOffset - 5;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    protected boolean fill() throws IOException {
        if (eof && have == 0) return false;
        while (!eof && have < 8192) {
            int n = in.read(data, have, data.length - have);
            if (n < 0) eof = true; else have += n;
        }
        int done = process(data, have);
        if (eof) done = have;                       // the last few bytes stay as they are
        if (done == 0) return !(eof && have == 0);
        buf.write(data, 0, done);
        System.arraycopy(data, done, data, 0, have - done);
        have -= done;
        return true;
    }

    private static boolean msb(int b) {
        return b == 0 || b == 0xFF;
    }

    /** Converts what it can of data[0..size) in place and returns how many bytes are final. */
    private int process(byte[] b, int size) {
        if (size < 5) return 0;
        if (Integer.compareUnsigned(nowPos - prevPos, 5) > 0) prevPos = nowPos - 5;
        int limit = size - 5;
        int pos = 0;
        while (pos <= limit) {
            int c = b[pos] & 0xff;
            if (c != 0xE8 && c != 0xE9) {
                pos++;
                continue;
            }
            int offset = nowPos + pos - prevPos;
            prevPos = nowPos + pos;
            if (Integer.compareUnsigned(offset, 5) > 0) {
                prevMask = 0;
            } else {
                for (int i = 0; i < offset; i++) {
                    prevMask &= 0x77;
                    prevMask <<= 1;
                }
            }
            int top = b[pos + 4] & 0xff;
            if (msb(top) && ALLOWED[(prevMask >>> 1) & 7] && (prevMask >>> 1) < 0x10) {
                int src = (top << 24) | ((b[pos + 3] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8) | (b[pos + 1] & 0xff);
                int dest;
                while (true) {
                    dest = src - (nowPos + pos + 5);
                    if (prevMask == 0) break;
                    int i = BIT_NUMBER[prevMask >>> 1];
                    int test = (dest >>> (24 - i * 8)) & 0xff;
                    if (!msb(test)) break;
                    src = dest ^ ((1 << (32 - i * 8)) - 1);
                }
                b[pos + 4] = (byte) ~(((dest >>> 24) & 1) - 1);
                b[pos + 3] = (byte) (dest >>> 16);
                b[pos + 2] = (byte) (dest >>> 8);
                b[pos + 1] = (byte) dest;
                pos += 5;
                prevMask = 0;
            } else {
                pos++;
                prevMask |= 1;
                if (msb(top)) prevMask |= 0x10;
            }
        }
        nowPos += pos;
        return pos;
    }
}
