package site.kpeclub.filemanager;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import site.kpeclub.filemanager.handlers.*;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class WebServer {

    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean running = false;

    /** Optional second port (exact, not scanned). 0 = off. */
    private volatile int extraPort = 0;
    /** Plugin version, written into the service worker so updates replace its cache. */
    private volatile String appVersion = "dev";

    public void setAppVersion(String version) {
        this.appVersion = version;
    }
    private volatile int extraBoundPort = -1;
    private volatile String extraError = null;
    private HttpServer extraServer;

    public void setExtraPort(int port) {
        this.extraPort = Math.max(0, port);
    }

    /** The second port actually in use, or -1 if it is off or could not be opened. */
    public int getExtraBoundPort() {
        return extraBoundPort;
    }

    /** Why the second port could not be opened, or null. */
    public String getExtraError() {
        return extraError;
    }

    /** One listener on an exact port, plain HTTP or HTTPS depending on sslContext. */
    private static HttpServer newListener(String bindAddress, int port, SSLContext sslContext) throws IOException {
        InetSocketAddress address = new InetSocketAddress(bindAddress, port);
        if (sslContext == null) {
            return HttpServer.create(address, 0);
        }
        HttpsServer httpsServer = HttpsServer.create(address, 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLEngine engine = sslContext.createSSLEngine();
                params.setNeedClientAuth(false);
                params.setCipherSuites(engine.getEnabledCipherSuites());
                params.setProtocols(engine.getEnabledProtocols());
                params.setSSLParameters(sslContext.getDefaultSSLParameters());
            }
        });
        return httpsServer;
    }

    /**
     * Starts the HTTP(S) server. Safe to call even if a previous instance of
     * this class failed to clean up (defensive against odd reload sequencing
     * from plugin managers like PlugManX) — it will stop itself first.
     */
    /** Ports the host allocated to this server. When set, only these are ever used. */
    private volatile List<Integer> allowedPorts = new ArrayList<>();

    public void setAllowedPorts(List<Integer> ports) {
        this.allowedPorts = ports == null ? new ArrayList<>() : new ArrayList<>(ports);
    }

    /**
     * Ports to try, in order: the configured port first, then the nearest ports above and
     * below it (port+1, port-1, port+2, port-2, ...), up to maxPortScan extra ports.
     * If allowedPorts is set, only those ports are tried, nearest first.
     */
    static List<Integer> candidatePorts(int port, int maxPortScan, List<Integer> allowed) {
        List<Integer> out = new ArrayList<>();
        if (allowed != null && !allowed.isEmpty()) {
            List<Integer> sorted = new ArrayList<>(allowed);
            sorted.sort(Comparator.comparingInt(p -> Math.abs(p - port)));
            for (int p : sorted) {
                if (p >= 1 && p <= 65535 && !out.contains(p)) out.add(p);
            }
            return out;
        }
        int maxCount = 1 + Math.max(0, maxPortScan);
        out.add(port);
        for (int d = 1; out.size() < maxCount; d++) {
            boolean up = port + d <= 65535;
            boolean down = port - d >= 1;
            if (!up && !down) break;
            if (up && out.size() < maxCount) out.add(port + d);
            if (down && out.size() < maxCount) out.add(port - d);
        }
        return out;
    }

    public synchronized int start(String bindAddress, int port, int maxPortScan, File root, TokenManager tokenManager,
                                    long sessionExpiryHours, long maxEditFileSizeKb,
                                    ConsoleManager consoleManager,
                                    boolean httpsEnabled, File keystoreFile,
                                    String keystorePassword, String keyPassword,
                                    long maxUploadSizeMb, String loginPassword) throws Exception {
        if (running) {
            stop();
        }

        long maxEditBytes = maxEditFileSizeKb * 1024L;

        // Build the SSL context up front so a bad keystore/password fails
        // fast and clearly, instead of getting swallowed by the bind-retry
        // loop below (which is only meant to ride out port TIME_WAIT).
        final SSLContext sslContext = httpsEnabled
                ? buildSslContext(keystoreFile, keystorePassword, keyPassword)
                : null;

        // A plugin reload/restart can race with the OS releasing the port the
        // previous instance was bound to (TIME_WAIT), and another program may
        // already hold the configured port. Try the configured port with a few
        // short retries, then move up through the next ports until one binds.
        List<Integer> candidates = candidatePorts(port, maxPortScan, allowedPorts);
        IOException lastError = null;
        int boundPort = -1;
        scan:
        for (int candidate : candidates) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    server = newListener(bindAddress, candidate, httpsEnabled ? sslContext : null);
                    boundPort = server.getAddress().getPort();   // the real port when 0 (any free port) was asked for
                    break scan;
                } catch (BindException e) {
                    lastError = e;
                    server = null;
                    if (attempt < 2) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                }
            }
        }
        if (server == null) {
            throw lastError;
        }

        // A few threads more than before: a waiting folder watch holds one while it waits.
        executor = Executors.newFixedThreadPool(16);
        server.setExecutor(executor);

        Consumer<HttpServer> routes = target -> {
            target.createContext("/", new IndexHandler(tokenManager, sessionExpiryHours, httpsEnabled, loginPassword));
            target.createContext("/style.css", new StaticAssetHandler("/web/style.css", "text/css; charset=utf-8"));
            target.createContext("/app.js", new StaticAssetHandler("/web/app.js", "application/javascript; charset=utf-8"));
            target.createContext("/sw.js", new ServiceWorkerHandler(appVersion));
    
            target.createContext("/api/list", new ListHandler(root, tokenManager));
            target.createContext("/api/read", new ReadHandler(root, tokenManager, maxEditBytes));
            target.createContext("/api/write", new WriteHandler(root, tokenManager, maxEditBytes));
            target.createContext("/api/delete", new DeleteHandler(root, tokenManager));
            target.createContext("/api/mkdir", new MkdirHandler(root, tokenManager));
            target.createContext("/api/rename", new RenameHandler(root, tokenManager));
            target.createContext("/api/upload", new UploadHandler(root, tokenManager, maxUploadSizeMb));
            target.createContext("/api/download", new DownloadHandler(root, tokenManager));
            target.createContext("/api/console/history", new ConsoleHistoryHandler(root, tokenManager, consoleManager));
            target.createContext("/api/console/send", new ConsoleSendHandler(root, tokenManager, consoleManager));
            target.createContext("/api/logout", new LogoutHandler(root, tokenManager));
            target.createContext("/api/verify-password", new PasswordVerifyHandler(tokenManager, loginPassword, sessionExpiryHours, httpsEnabled));
            target.createContext("/api/zip", new ZipHandler(root, tokenManager));
            target.createContext("/api/unzip", new UnzipHandler(root, tokenManager));
            target.createContext("/api/search", new SearchHandler(root, tokenManager, maxEditBytes));
            target.createContext("/api/plugins/search", new PluginSearchHandler(root, tokenManager));
            target.createContext("/api/plugins/versions", new PluginVersionsHandler(root, tokenManager));
            target.createContext("/api/plugins/install", new PluginInstallHandler(root, tokenManager, maxUploadSizeMb));
        };
        routes.accept(server);

        server.start();

        // Optional second port, exact and never scanned. If it cannot be opened the main
        // listener keeps running and the reason is kept for the caller to log.
        extraBoundPort = -1;
        extraError = null;
        if (extraPort > 0 && extraPort != boundPort) {
            try {
                extraServer = newListener(bindAddress, extraPort, httpsEnabled ? sslContext : null);
                extraServer.setExecutor(executor);
                routes.accept(extraServer);
                extraServer.start();
                extraBoundPort = extraPort;
            } catch (IOException e) {
                extraServer = null;
                extraError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        running = true;
        return boundPort;
    }

    /**
     * Loads a keystore (.jks or .p12, detected by extension) and builds a
     * TLS context from it. keyPassword falls back to keystorePassword when
     * blank, since that's the common case for both `keytool -genkeypair`
     * (same password unless -keypass is given) and `openssl pkcs12 -export`
     * (one password for both by default).
     */
    private static SSLContext buildSslContext(File keystoreFile, String keystorePassword, String keyPassword) throws Exception {
        if (!keystoreFile.exists()) {
            throw new IOException("HTTPS keystore not found: " + keystoreFile.getPath());
        }
        String type = keystoreFile.getName().toLowerCase().endsWith(".p12") ? "PKCS12" : "JKS";
        KeyStore ks = KeyStore.getInstance(type);
        try (FileInputStream fis = new FileInputStream(keystoreFile)) {
            ks.load(fis, keystorePassword.toCharArray());
        }

        String effectiveKeyPassword = (keyPassword == null || keyPassword.isEmpty()) ? keystorePassword : keyPassword;

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, effectiveKeyPassword.toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }

    /**
     * Stops the HTTP(S) server and, critically, shuts down its executor.
     * HttpServer#stop() does NOT shut down a custom executor set via
     * setExecutor() — leaving it running keeps every class it touched (and
     * therefore this plugin's whole classloader) alive in memory. On a normal
     * server stop the JVM exiting hides that leak, but plugin managers like
     * PlugManX reload the plugin in-process, so a leaked executor here is
     * exactly why the file manager can come back broken (or fail to rebind
     * the port) after `/plugman reload` or `/plugman restart`.
     */
    public synchronized void stop() {
        if (extraServer != null) {
            extraServer.stop(0);
            extraServer = null;
        }
        extraBoundPort = -1;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
