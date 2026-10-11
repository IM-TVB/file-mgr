package site.kpeclub.filemanager;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        webServer = new WebServer();
        startServices(null);
        getCommand("filemgr").setExecutor(this);
        startUpdateChecks();
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
        if (webServer != null) {
            webServer.stop();
            getLogger().info("KPEFileManager web server stopped.");
        }
        if (consoleManager != null) {
            consoleManager.stop();
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
            int configuredPort = port;
            port = webServer.start(bindAddress, port, portScan, root, tokenManager, sessionExpiryHours,
                    maxEditFileSizeKb, consoleManager, httpsEnabled, keystoreFile, keystorePassword,
                    keyPassword, maxUploadSizeMb, loginPassword);
            if (port != configuredPort) {
                getLogger().warning("Port " + configuredPort + " was already in use, so the file manager "
                        + "is listening on port " + port + " instead. If you use a reverse proxy, "
                        + "point it at port " + port + ".");
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
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
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
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String url = resolveBaseUrl() + "/?token=" + token;
            Bukkit.getScheduler().runTask(this, () -> sendLoginLink(player, url));
        });

        return true;
    }

    private void sendLoginLink(Player player, String url) {
        if (!player.isOnline()) return;
        player.sendMessage(ChatColor.AQUA + "Your one-time file manager link (expires in a few minutes):");
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
        String defaultScheme = httpsEnabled ? "https://" : "http://";
        if (publicUrlBase != null && !publicUrlBase.isBlank()) {
            String base = publicUrlBase.trim();
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                base = defaultScheme + base;
            }
            return stripTrailingSlash(base);
        }
        if (usePublicIp) {
            String ip = publicIp.get();
            if (ip != null) {
                return defaultScheme + ip + ":" + port;
            }
        }
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            return defaultScheme + host + ":" + port;
        } catch (UnknownHostException e) {
            return defaultScheme + "localhost:" + port;
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
