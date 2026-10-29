package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

/** Standard hardening headers applied to every response this plugin sends. */
public final class SecurityHeaders {

    private SecurityHeaders() {
    }

    public static void apply(HttpExchange exchange) {
        Headers h = exchange.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        // 'unsafe-inline' on style-src only, for the small inline <style> block
        // on the "link expired" / "not logged in" pages. Everything else
        // (index.html, app.js) is external and satisfies script-src 'self'.
        h.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data: https://cdn.modrinth.com; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    }
}
