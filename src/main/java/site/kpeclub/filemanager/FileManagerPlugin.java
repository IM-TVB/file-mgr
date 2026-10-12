package site.kpeclub.filemanager;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class FileManagerPlugin extends JavaPlugin implements CommandExecutor {

    private WebServer webServer;
    private TokenManager tokenManager;
    private ConsoleManager consoleManager;
    private volatile String publicUrlBase;
    private volatile int port;
    private volatile boolean httpsEnabled;
    private volatile boolean usePublicIp;
    private final PublicIpResolver publicIp = new PublicIpResolver();
    private UpdateChecker updateChecker;
    private CloudflareTunnel tunnel;
    /** Random trycloudflare address from a quick tunnel, or null. Changes on every start. */
    private volatile String quickTunnelUrl;
    private volatile boolean shuttingDown = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addMissingConfigSettings();
        reloadConfig();
        webServer = new WebServer();
        startServices(null);
        getCommand("filemgr").setExecutor(this);
        startUpdateChecks();
        startCloudflareTunnel();
        // Without this, expired sessions and attack-rate entries would build up on a server
        // where nobody runs /filemgr for a long time. Every 10 minutes, asynchronously.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            TokenManager tm = tokenManager;
            if (tm != null) tm.cleanup();
        }, 20L * 60 * 10, 20L * 60 * 10);

        // Non-English file names need a UTF-8 locale. Warn the admin if the JVM is not using one.
        String jnu = System.getProperty("sun.jnu.encoding", "");
        if (!jnu.toUpperCase(java.util.Locale.ROOT).contains("UTF")) {
            getLogger().warning("Java file names use the " + jnu + " encoding. Files with non-English "
                    + "names (for example Khmer) will not open or show correctly. Add LANG=C.UTF-8 to the "
                    + "server's startup environment and restart.");
        }
    }

    /**
     * Starts cloudflared when cloudflare-tunnel-enabled is true. The tunnel needs no open
     * port: it connects out to Cloudflare, and the domain forwards to this server's port.
     */
    /**
     * Adds settings from the bundled config.yml that the server's config.yml does not have
     * yet (for example after an update). Existing lines, values and comments are kept.
     */
    private void addMissingConfigSettings() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) return;
        try (java.io.InputStream in = getResource("config.yml")) {
            if (in == null) return;
            String bundled = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String current = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            ConfigMerger.Result result = ConfigMerger.merge(current, bundled);
            if (!result.added.isEmpty()) {
                java.nio.file.Files.writeString(file.toPath(), result.text, java.nio.charset.StandardCharsets.UTF_8);
                getLogger().info("Added new settings to config.yml: " + String.join(", ", result.added));
            }
        } catch (IOException e) {
            getLogger().warning("Could not add new settings to config.yml: " + e.getMessage());
        }
    }

    private void startCloudflareTunnel() {
        if (!getConfig().getBoolean("cloudflare-tunnel-enabled", false)) return;
        // Downloading and starting can take a while, so keep it off the main server thread.
        runAsync(this::launchCloudflareTunnel);
    }

    private void launchCloudflareTunnel() {
        String token = getConfig().getString("cloudflare-tunnel-token", "").trim();
        String command = getConfig().getString("cloudflare-tunnel-command", "cloudflared").trim();
        boolean autoDownload = getConfig().getBoolean("cloudflare-tunnel-auto-download", true);
        CloudflareTunnel t = new CloudflareTunnel(getLogger());
        try {
            try {
                startTunnel(t, command, token);
            } catch (IOException notRunnable) {
                if (!autoDownload) throw notRunnable;
                // The command is missing, so fetch cloudflared once into the plugin folder.
                File binary = new File(getDataFolder(), "cloudflared");
                if (!binary.canExecute()) {
                    getLogger().info("cloudflared was not found (" + command + "). Downloading it now...");
                    binary = new CloudflaredInstaller().download(binary,
                            System.getProperty("os.name"), System.getProperty("os.arch"), getLogger());
                }
                startTunnel(t, binary.getAbsolutePath(), token);
            }
            if (shuttingDown) {
                t.stop();
                return;
            }
            tunnel = t;
        } catch (IOException e) {
            getLogger().warning("Could not start cloudflared: " + e.getMessage()
                    + ". You can install it yourself and set cloudflare-tunnel-command to its full path.");
        }
    }

    private void startTunnel(CloudflareTunnel t, String command, String token) throws IOException {
        if (token.isEmpty()) {
            // No token: a free random address like https://abc-xyz.trycloudflare.com.
            t.startQuick(command, "http://localhost:" + port, url -> {
                quickTunnelUrl = url;
                getLogger().info("Remote address: " + url + "  (changes each time the server restarts)");
                getLogger().info("Quick tunnels have no uptime guarantee. For a fixed domain, add a "
                        + "token from the Cloudflare dashboard to cloudflare-tunnel-token.");
            });
        } else {
            t.startNamed(command, token);
            getLogger().info("Cloudflare tunnel started. In the Cloudflare dashboard, the public hostname's "
                    + "service must be http://localhost:" + port + " (and public-url must be your domain).");
        }
    }

    /**
     * Checks GitHub for a newer release shortly after startup and then once a day.
     * The result is logged to the console and shown to online operators on join.
     * Turn it off with update-check: false in config.yml (takes effect on restart).
     */
    private void startUpdateChecks() {
        if (!getConfig().getBoolean("update-check", true)) return;
        final UpdateChecker checker = new UpdateChecker(getDescription().getVersion());
        updateChecker = checker;
        Bukkit.getPluginManager().registerEvents(new UpdateNoticeListener(this, checker), this);
        // 20 ticks per second: first check after 5 seconds, then every 24 hours.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            String before = checker.getLatestVersion();
            if (checker.check()) {
                String latest = checker.getLatestVersion();
                if (latest != null && !latest.equals(before)) {
                    String link = checker.getReleaseUrl() != null ? " " + checker.getReleaseUrl() : "";
                    getLogger().warning("A newer KPE File Manager release is available: " + latest
                            + " (you have " + getDescription().getVersion() + ")." + link);
                }
            }
        }, 100L, 20L * 60 * 60 * 24);
    }

    @Override
    public void onDisable() {
        // Each step runs even if an earlier one fails. A plugin reload (for example with
        // PlugManX) must always remove the console log hook, or it keeps pointing at this
        // unloaded copy of the plugin.
        shuttingDown = true;
        try {
            if (tunnel != null) {
                tunnel.stop();
                tunnel = null;
            }
        } catch (RuntimeException e) {
            getLogger().warning("Error while stopping cloudflared: " + e);
        }
        quickTunnelUrl = null;
        try {
            if (webServer != null) {
                webServer.stop();
                getLogger().info("KPEFileManager web server stopped.");
            }
        } catch (RuntimeException e) {
            getLogger().warning("Error while stopping the web server: " + e);
        }
        try {
            if (consoleManager != null) {
                consoleManager.stop();
            }
        } catch (RuntimeException e) {
            getLogger().warning("Error while stopping the console capture: " + e);
        }
        webServer = null;
        tokenManager = null;
        consoleManager = null;
    }

    /**
     * (Re)reads config.yml and (re)starts the internal web server with the
     * fresh values — no plugin reload, no server restart. This is plain
     * in-process Java: we're not asking Paper to reload the plugin at all,
     * so the "didn't load any plugin providers" issue that blocks
     * /plugman reload on this Paper build doesn't apply here.
     * <p>
     * tokenManager and consoleManager are kept alive across a reload rather
     * than recreated, so it never logs anyone out or drops the console's
     * log-handler hook — only their settings get updated live.
     *
     * @param feedbackTo who to message with the result; null during initial
     *                    onEnable(), since there's no command sender yet.
     */
    private boolean startServices(CommandSender feedbackTo) {
        port = getConfig().getInt("port", 8080);
        int portScan = getConfig().getBoolean("auto-port", true)
                ? getConfig().getInt("port-scan-limit", 10)
                : 0;
        String bindAddress = getConfig().getString("bind-address", "0.0.0.0");
        String rootDirConfig = getConfig().getString("root-directory", "");
        long tokenExpiryMinutes = getConfig().getLong("token-expiry-minutes", 5);
        long sessionExpiryHours = getConfig().getLong("session-expiry-hours", 12);
        long maxEditFileSizeKb = getConfig().getLong("max-edit-file-size-kb", 20480000);
        long maxUploadSizeMb = getConfig().getLong("max-upload-size-mb", 512);
        int consoleMaxLines = getConfig().getInt("console.max-lines", 400);
        String loginPassword = getConfig().getString("login-password", "");
        publicUrlBase = getConfig().getString("public-url", "");
        usePublicIp = getConfig().getBoolean("use-public-ip", true);

        httpsEnabled = getConfig().getBoolean("https.enabled", false);
        String keystorePathConfig = getConfig().getString("https.keystore-path", "keystore.jks");
        String keystorePassword = getConfig().getString("https.keystore-password", "");
        String keyPassword = getConfig().getString("https.key-password", "");
        File keystoreFile = new File(keystorePathConfig);
        if (!keystoreFile.isAbsolute()) {
            keystoreFile = new File(getDataFolder(), keystorePathConfig);
        }

        File serverRoot = new File(System.getProperty("user.dir"));
        File root = rootDirConfig == null || rootDirConfig.isBlank()
                ? serverRoot
                : new File(serverRoot, rootDirConfig);
        if (!root.exists()) {
            root.mkdirs();
        }

        if (tokenManager == null) {
            tokenManager = new TokenManager(tokenExpiryMinutes, sessionExpiryHours);
        } else {
            tokenManager.updateExpiries(tokenExpiryMinutes, sessionExpiryHours);
        }

        if (consoleManager == null) {
            consoleManager = new ConsoleManager(this, consoleMaxLines);
            consoleManager.start();
            Bukkit.getPluginManager().registerEvents(new PlayerCommandListener(consoleManager), this);
        } else {
            consoleManager.setMaxLines(consoleMaxLines);
        }

        try {
            webServer.setAllowedPorts(getConfig().getIntegerList("allowed-ports"));
            int extraWanted = getConfig().getInt("extra-port", 0);
            webServer.setExtraPort(extraWanted);
            webServer.setAppVersion(getDescription().getVersion());
            int configuredPort = port;
            port = webServer.start(bindAddress, port, portScan, root, tokenManager, sessionExpiryHours,
                    maxEditFileSizeKb, consoleManager, httpsEnabled, keystoreFile, keystorePassword,
                    keyPassword, maxUploadSizeMb, loginPassword);
            if (port != configuredPort) {
                getLogger().warning("Port " + configuredPort + " was already in use, so the file manager "
                        + "is listening on port " + port + " instead. If you use a reverse proxy, "
                        + "point it at port " + port + ".");
            }
            if (extraWanted > 0) {
                if (extraWanted == port) {
                    getLogger().warning("extra-port " + extraWanted + " is the same as the main port; ignored.");
                } else if (webServer.getExtraBoundPort() > 0) {
                    getLogger().info("Also listening on port " + webServer.getExtraBoundPort()
                            + ". Either port works, with the same login.");
                } else {
                    getLogger().warning("Could not open the second port " + extraWanted + " ("
                            + webServer.getExtraError() + "). Only port " + port + " is active. "
                            + "Check that your host allocated this port to the server.");
                }
            }
            String summary = (httpsEnabled ? "https://" : "http://") + bindAddress + ":" + port
                    + " (root: " + root.getPath() + ")";
            getLogger().info("KPEFileManager web server listening on " + summary);
            if (feedbackTo != null) {
                feedbackTo.sendMessage(ChatColor.GREEN + "File manager config reloaded — now serving " + summary);
            }
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to start web server: " + e.getMessage());
            if (httpsEnabled) {
                getLogger().severe("Check https.keystore-path / https.keystore-password / https.key-password in config.yml.");
            }
            if (feedbackTo != null) {
                feedbackTo.sendMessage(ChatColor.RED + "Reload failed: " + e.getMessage() + " (see console for details)");
            }
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.isOp() && !sender.hasPermission("kpe.filemanager.reload")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to reload the file manager.");
                return true;
            }
            sender.sendMessage(ChatColor.GRAY + "Reloading file manager config...");
            // Rebinding the socket and shutting down the old thread pool are
            // blocking calls (up to ~2s in the worst case) — do this off the
            // main thread so a reload can't stall the server tick.
            runAsync(() -> {
                addMissingConfigSettings();
                reloadConfig();
                startServices(sender);
            });
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game. Use '/filemgr reload' from console to reload config.");
            return true;
        }
        if (tokenManager == null) {
            player.sendMessage(ChatColor.RED + "The file manager is not ready yet, try again in a moment.");
            return true;
        }
        if (!player.isOp() && !player.hasPermission("kpe.filemanager.access")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use the file manager.");
            return true;
        }

        String token = tokenManager.generateLoginToken(player.getUniqueId(), player.getName());
        // The public IP lookup can take a few seconds, so build the link off the
        // main thread and send it back on the main thread.
        runAsync(() -> {
            // With the free Cloudflare address, the IP link would not work. Say it is
            // still starting instead of sending a dead link.
            if (quickModeActive() && quickTunnelUrl == null) {
                runOnMainThread(() -> player.sendMessage(ChatColor.YELLOW
                        + "Your remote address is still starting. Try /filemgr again in a few seconds. "
                        + "If it never appears, check the console for [cloudflared] errors."));
                return;
            }
            String url = resolveBaseUrl() + "/?token=" + token;
            // By default the link uses the main port only. The second port's link is added
            // only when show-extra-port-link is true.
            int extra = webServer != null ? webServer.getExtraBoundPort() : -1;
            boolean overridden = (publicUrlBase != null && !publicUrlBase.isBlank()) || quickTunnelUrl != null;
            boolean showExtra = getConfig().getBoolean("show-extra-port-link", false);
            String url2 = (showExtra && extra > 0 && !overridden)
                    ? resolveBaseUrl(extra) + "/?token=" + token : null;
            runOnMainThread(() -> sendLoginLink(player, url, url2));
        });

        return true;
    }

    /**
     * Background work may finish after this plugin was disabled (for example during a
     * PlugManX reload). Bukkit throws if a disabled plugin schedules a task, so these
     * helpers skip the work instead of logging errors.
     */
    private void runAsync(Runnable task) {
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler().runTaskAsynchronously(this, task);
        } catch (IllegalPluginAccessException ignored) {
            // Plugin disabled between the check and the call.
        }
    }

    private void runOnMainThread(Runnable task) {
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(this, task);
        } catch (IllegalPluginAccessException ignored) {
            // Plugin disabled between the check and the call.
        }
    }

    /** True when the free Cloudflare address is used: tunnel on, no token, no public-url. */
    private boolean quickModeActive() {
        return getConfig().getBoolean("cloudflare-tunnel-enabled", false)
                && getConfig().getString("cloudflare-tunnel-token", "").trim().isEmpty()
                && (publicUrlBase == null || publicUrlBase.isBlank());
    }

    private void sendLoginLink(Player player, String url, String url2) {
        if (!player.isOnline()) return;
        player.sendMessage(ChatColor.AQUA + "Your one-time file manager link (expires in a few minutes):");
        sendClickable(player, url);
        if (url2 != null) {
            player.sendMessage(ChatColor.AQUA + "Same login on the second port (either link works):");
            sendClickable(player, url2);
        }
    }

    private void sendClickable(Player player, String url) {
        try {
            TextComponent link = new TextComponent(url);
            link.setColor(ChatColor.GREEN);
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            player.spigot().sendMessage(link);
        } catch (Exception e) {
            player.sendMessage(ChatColor.GREEN + url);
        }
    }

    private String resolveBaseUrl() {
        return resolveBaseUrl(port);
    }

    private String resolveBaseUrl(int linkPort) {
        String defaultScheme = httpsEnabled ? "https://" : "http://";
        if (publicUrlBase != null && !publicUrlBase.isBlank()) {
            String base = publicUrlBase.trim();
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                base = defaultScheme + base;
            }
            return stripTrailingSlash(base);
        }
        String quick = quickTunnelUrl;
        if (quick != null && linkPort == port) {
            return quick;
        }
        if (usePublicIp) {
            String ip = publicIp.get();
            if (ip != null) {
                return defaultScheme + ip + ":" + linkPort;
            }
        }
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            return defaultScheme + host + ":" + linkPort;
        } catch (UnknownHostException e) {
            return defaultScheme + "localhost:" + linkPort;
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
