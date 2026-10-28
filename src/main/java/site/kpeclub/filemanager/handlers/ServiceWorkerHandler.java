package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the service worker at /sw.js. The plugin version is written into it, so a new
 * plugin version gives browsers a new worker and a fresh cache.
 */
public class ServiceWorkerHandler implements HttpHandler {

    private static final String PLACEHOLDER = "__KPEFM_VERSION__";
    private final String version;
    private volatile byte[] script;

    public ServiceWorkerHandler(String version) {
        // Keep only characters that are safe inside a cache name.
        this.version = version == null ? "dev" : version.replaceAll("[^A-Za-z0-9._-]", "");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            SecurityHeaders.apply(exchange);
            exchange.getResponseHeaders().set("Service-Worker-Allowed", "/");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            ResponseUtil.send(exchange, 200, "application/javascript; charset=utf-8", script(), true);
        } finally {
            exchange.close();
        }
    }

    private byte[] script() throws IOException {
        byte[] cached = script;
        if (cached == null) {
            String text = new String(IndexHandler.readResource("/web/sw.js"), StandardCharsets.UTF_8);
            cached = text.replace(PLACEHOLDER, version).getBytes(StandardCharsets.UTF_8);
            script = cached;
        }
        return cached;
    }
}
