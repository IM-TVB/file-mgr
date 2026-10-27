package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class StaticAssetHandler implements HttpHandler {

    /** The file's bytes and its ETag, loaded once: files inside the jar never change while the server runs. */
    private static final class Loaded {
        final byte[] body;
        final String etag;

        Loaded(byte[] body, String etag) {
            this.body = body;
            this.etag = etag;
        }
    }

    private final String resourcePath;
    private final String contentType;
    private volatile Loaded loaded;

    public StaticAssetHandler(String resourcePath, String contentType) {
        this.resourcePath = resourcePath;
        this.contentType = contentType;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            SecurityHeaders.apply(exchange);
            Loaded asset = load();
            exchange.getResponseHeaders().set("ETag", asset.etag);
            // "no-cache" means the browser checks with the server each time, but a
            // matching ETag gets a tiny 304 reply instead of the whole file again.
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            if (asset.etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            ResponseUtil.send(exchange, 200, contentType, asset.body, true);
        } finally {
            exchange.close();
        }
    }

    private Loaded load() throws IOException {
        Loaded current = loaded;
        if (current == null) {
            byte[] body = IndexHandler.readResource(resourcePath);
            current = new Loaded(body, etagOf(body));
            loaded = current;
        }
        return current;
    }

    private static String etagOf(byte[] body) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return "\"" + hex + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }
}
