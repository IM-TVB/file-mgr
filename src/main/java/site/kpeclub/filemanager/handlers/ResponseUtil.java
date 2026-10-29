package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Sends responses compressed when the browser accepts gzip (every modern browser does).
 * Text such as HTML, CSS, JavaScript and JSON shrinks to a fraction of its size, which
 * matters on mobile data. Small responses and downloads are sent as they are.
 */
final class ResponseUtil {

    /** Below this size, gzip adds more overhead than it saves. */
    static final int MIN_COMPRESS_BYTES = 860;

    private ResponseUtil() {}

    static boolean acceptsGzip(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.toLowerCase().contains("gzip");
    }

    /** A short fingerprint of the body, quoted for use as an ETag. Changes when the body changes. */
    static String etagOf(byte[] body) throws IOException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return "\"" + hex + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    /** Writes the body, compressed if allowed and worthwhile. The caller closes the exchange. */
    static void send(HttpExchange exchange, int status, String contentType, byte[] body, boolean textLike)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (textLike) {
            // Caches must key on Accept-Encoding, since the same URL can be sent either way.
            exchange.getResponseHeaders().set("Vary", "Accept-Encoding");
        }
        if (textLike && body.length >= MIN_COMPRESS_BYTES && acceptsGzip(exchange)) {
            byte[] packed = gzip(body);
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(status, 0); // length unknown in advance: chunked
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(packed);
            }
        } else if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
