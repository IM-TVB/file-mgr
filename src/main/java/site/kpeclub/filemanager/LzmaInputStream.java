package site.kpeclub.filemanager;

import java.io.IOException;
import java.io.InputStream;

/** Reads raw LZMA data as found in 7z archives: 5 property bytes plus the packed stream. */
final class LzmaInputStream extends PullStream {

    private static final long MAX_DICT = 1_500_000_000L;

    private final InputStream in;
    private final LzmaDecoder dec;
    private long remaining;       // bytes still to deliver, or -1 if unknown

    LzmaInputStream(InputStream in, byte[] props, long unpackSize) throws IOException {
        if (props == null || props.length < 5) throw new IOException("LZMA properties are missing");
        long dict = (props[1] & 0xffL) | ((props[2] & 0xffL) << 8) | ((props[3] & 0xffL) << 16) | ((props[4] & 0xffL) << 24);
        if (dict > MAX_DICT) throw new IOException("LZMA dictionary is too large (" + (dict >> 20) + " MB)");
        long window = unpackSize >= 0 ? Math.min(dict, unpackSize) : dict;
        dec = new LzmaDecoder((int) Math.max(dict, 4096), (int) Math.max(window, 4096), buf);
        dec.setPropsByte(props[0] & 0xff);
        dec.resetState();
        this.in = in;
        // An empty stream has nothing to read, not even the 5 start bytes of the range coder.
        if (unpackSize != 0) dec.startRange(in);
        remaining = unpackSize;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    protected boolean fill() throws IOException {
        if (remaining == 0) return false;
        long want = remaining < 0 ? 65536 : Math.min(remaining, 65536);
        int before = buf.size2();
        dec.decode(want);
        int added = buf.size2() - before;
        if (remaining >= 0 && added > remaining) {      // a match that ran past the end
            buf.cut(before + (int) remaining);
            added = (int) remaining;
        }
        if (remaining >= 0) remaining -= added;
        if (dec.sawEndMarker()) remaining = 0;
        return added > 0;
    }
}
