package site.kpeclub.filemanager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs cloudflared as a child process. The tunnel makes an outbound connection to
 * Cloudflare, so the server needs no inbound port.
 *
 * Two modes:
 *  - Named tunnel (startNamed): uses a token from the Cloudflare dashboard, and your own
 *    domain. The public hostname and its target are set in the dashboard.
 *  - Quick tunnel (startQuick): no account. Cloudflare assigns a random
 *    https://<name>.trycloudflare.com address, which is reported through a callback.
 *    It changes on every start and has no uptime guarantee.
 */
public class CloudflareTunnel {

    /** cloudflared log level as printed: "... ERR ...", "... WRN ...", "... FTL ...". */
    private static final Pattern ERROR_LEVEL = Pattern.compile("\\s(ERR|WRN|FTL)\\s");

    private static final Pattern QUICK_URL =
            Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com", Pattern.CASE_INSENSITIVE);

    private final Logger log;
    private Process process;
    private volatile boolean stopping = false;
    private volatile ErrorLog errors;
    private volatile ErrorLog info;

    /** Where normal cloudflared messages go (tunnel.log), instead of the console. */
    public void setInfoLog(ErrorLog info) {
        this.info = info;
    }

    /** Where cloudflared errors and warnings go (error.txt), instead of the console. */
    public void setErrorLog(ErrorLog errors) {
        this.errors = errors;
    }

    public CloudflareTunnel(Logger log) {
        this.log = log;
    }

    /** Named tunnel with a dashboard token. Throws IOException if cloudflared cannot be run. */
    public synchronized void startNamed(String command, String token) throws IOException {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        // The token goes in the environment, so it does not appear in the process list.
        env.put("TUNNEL_TOKEN", token);
        launch(new String[]{command, "tunnel", "--no-autoupdate", "run"}, env, null);
    }

    /**
     * Quick tunnel for localUrl (for example http://localhost:8080). The public address is
     * passed to onPublicUrl as soon as cloudflared prints it.
     */
    public synchronized void startQuick(String command, String localUrl, Consumer<String> onPublicUrl)
            throws IOException {
        launch(new String[]{command, "tunnel", "--no-autoupdate", "--url", localUrl}, null, onPublicUrl);
    }

    private void launch(String[] args, java.util.Map<String, String> env, Consumer<String> onPublicUrl)
            throws IOException {
        if (process != null && process.isAlive()) return;
        stopping = false;
        ProcessBuilder pb = new ProcessBuilder(args);
        if (env != null) pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        process = p;

        Thread reader = new Thread(() -> {
            String announced = null;
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (onPublicUrl != null && announced == null) {
                        Matcher m = QUICK_URL.matcher(line);
                        while (m.find()) {
                            // cloudflared also names its own service, api.trycloudflare.com, in messages.
                            if (m.group().equalsIgnoreCase("https://api.trycloudflare.com")) continue;
                            announced = m.group();
                            onPublicUrl.accept(announced);
                            break;
                        }
                    }
                    // "context canceled" is normal: the browser cancelled a folder wait.
                    if (line.contains("context canceled")) continue;
                    ErrorLog sink = errors;
                    if (sink != null && ERROR_LEVEL.matcher(line).find()) {
                        sink.write("[cloudflared] " + line);
                        continue;
                    }
                    ErrorLog infoSink = info;
                    if (infoSink != null) {
                        infoSink.write("[cloudflared] " + line);
                    } else {
                        log.info("[cloudflared] " + line);
                    }
                }
            } catch (IOException ignored) {
                // The stream closes when the process exits.
            }
            if (!stopping) {
                String message = "cloudflared stopped (exit code " + exitCode(p)
                        + "). Remote access will not work until the server is restarted.";
                ErrorLog sink = errors;
                if (sink != null) sink.write(message);
                else log.warning(message);
            }
        }, "cloudflared-output");
        reader.setDaemon(true);
        reader.start();
    }

    /** Stops the tunnel and waits briefly for it to exit. */
    public synchronized void stop() {
        if (process == null) return;
        stopping = true;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    private static String exitCode(Process p) {
        return p.isAlive() ? "running" : String.valueOf(p.exitValue());
    }
}
