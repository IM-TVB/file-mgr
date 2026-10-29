package site.kpeclub.filemanager;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Reads LZMA2 data (used by xz and 7z): a series of LZMA or stored chunks. */
final class Lzma2InputStream extends PullStream {

    private static final long MAX_DICT = 1_500_000_000L;

    private final InputStream in;
    private final LzmaDecoder dec;
    private boolean needDictReset = true;
    private boolean needProps = true;
    private boolean finished;

    Lzma2InputStream(InputStream in, long dictSize, long unpackSizeHint) throws IOException {
        if (dictSize > MAX_DICT) throw new IOException("LZMA2 dictionary is too large (" + (dictSize >> 20) + " MB)");
        this.in = in;
        long window = unpackSizeHint >= 0 ? Math.min(dictSize, unpackSizeHint) : dictSize;
        dec = new LzmaDecoder((int) Math.max(dictSize, 4096), (int) Math.max(window, 4096), buf);
    }

    /** The dictionary size an LZMA2 property byte stands for. */
    static long dictSizeFromProp(int b) throws IOException {
        if (b > 40) throw new IOException("LZMA2 properties are damaged");
        if (b == 40) return 0xFFFFFFFFL;
        return (2L | (b & 1)) << (b / 2 + 11);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private int u8() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("compressed data is cut off");
        return b;
    }

    @Override
    protected boolean fill() throws IOException {
        if (finished) return false;
        int control = u8();
        if (control == 0) {
            finished = true;
            return false;
        }
        if (control == 1 || control >= 0xE0) {
            dec.resetDict();
            needDictReset = false;
        } else if (needDictReset) {
            throw new IOException("LZMA2 data is damaged (no dictionary reset)");
        }

        if (control < 0x80) {                                 // stored chunk
            if (control > 2) throw new IOException("LZMA2 data is damaged (bad chunk)");
            int size = ((u8() << 8) | u8()) + 1;
            dec.copyStored(in, size);
            dec.flush();
            return true;
        }

        int unpack = ((control & 0x1F) << 16) + (u8() << 8) + u8() + 1;
        int pack = ((u8() << 8) | u8()) + 1;
        int mode = (control >> 5) & 3;
        if (mode >= 2) {
            dec.setPropsByte(u8());
            needProps = false;
        } else if (needProps) {
            throw new IOException("LZMA2 data is damaged (no properties)");
        }
        if (mode >= 1) dec.resetState();

        byte[] data = new byte[pack];
        int got = 0;
        while (got < pack) {
            int n = in.read(data, got, pack - got);
            if (n < 0) throw new EOFException("compressed data is cut off");
            got += n;
        }
        dec.startRange(new ByteArrayInputStream(data));
        long produced = dec.decode(unpack);
        if (produced != unpack) throw new IOException("LZMA2 data is damaged (chunk size)");
        return true;
    }
}
