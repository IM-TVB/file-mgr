package site.kpeclub.filemanager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** An InputStream that decodes more data into a reusable buffer whenever the reader has used it all. */
abstract class PullStream extends InputStream {

    /** The buffer decoded bytes go to. Exposed so a decoder can write into it as its output sink. */
    static final class Buf extends ByteArrayOutputStream {
        byte[] array() { return buf; }
        int size2() { return count; }
        void cut(int newSize) { count = newSize; }
    }

    protected final Buf buf = new Buf();
    private int pos;

    /** Decodes more bytes into {@link #buf}. Returns false when there is nothing more. */
    protected abstract boolean fill() throws IOException;

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (pos >= buf.size2()) {
            buf.reset();
            pos = 0;
            if (!fill()) return -1;
        }
        int n = Math.min(len, buf.size2() - pos);
        System.arraycopy(buf.array(), pos, dst, off, n);
        pos += n;
        return n;
    }
}
