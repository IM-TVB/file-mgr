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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WebServer {

    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean running = false;

    /**
     * Starts the HTTP(S) server. Safe to call even if a previous instance of
     * this class failed to clean up (defensive against odd reload sequencing
     * from plugin managers like PlugManX) — it will stop itself first.
     */
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
        int lastPort = Math.min(65535, port + Math.max(0, maxPortScan));
        IOException lastError = null;
        int boundPort = -1;
        scan:
        for (int candidate = port; candidate <= lastPort; candidate++) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    if (httpsEnabled) {
                        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(bindAddress, candidate), 0);
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
                        server = httpsServer;
                    } else {
                        server = HttpServer.create(new InetSocketAddress(bindAddress, candidate), 0);
                    }
                    boundPort = candidate;
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

        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);

        server.createContext("/", new IndexHandler(tokenManager, sessionExpiryHours, httpsEnabled, loginPassword));
        server.createContext("/style.css", new StaticAssetHandler("/web/style.css", "text/css; charset=utf-8"));
        server.createContext("/app.js", new StaticAssetHandler("/web/app.js", "application/javascript; charset=utf-8"));

        server.createContext("/api/list", new ListHandler(root, tokenManager));
        server.createContext("/api/read", new ReadHandler(root, tokenManager, maxEditBytes));
        server.createContext("/api/write", new WriteHandler(root, tokenManager, maxEditBytes));
        server.createContext("/api/delete", new DeleteHandler(root, tokenManager));
        server.createContext("/api/mkdir", new MkdirHandler(root, tokenManager));
        server.createContext("/api/rename", new RenameHandler(root, tokenManager));
        server.createContext("/api/upload", new UploadHandler(root, tokenManager, maxUploadSizeMb));
        server.createContext("/api/download", new DownloadHandler(root, tokenManager));
        server.createContext("/api/console/history", new ConsoleHistoryHandler(root, tokenManager, consoleManager));
        server.createContext("/api/console/send", new ConsoleSendHandler(root, tokenManager, consoleManager));
        server.createContext("/api/logout", new LogoutHandler(root, tokenManager));
        server.createContext("/api/verify-password", new PasswordVerifyHandler(tokenManager, loginPassword, sessionExpiryHours, httpsEnabled));
        server.createContext("/api/zip", new ZipHandler(root, tokenManager));
        server.createContext("/api/unzip", new UnzipHandler(root, tokenManager));
        server.createContext("/api/search", new SearchHandler(root, tokenManager, maxEditBytes));

        server.start();
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
