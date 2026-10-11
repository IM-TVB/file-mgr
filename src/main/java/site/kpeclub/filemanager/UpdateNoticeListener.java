package site.kpeclub.filemanager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;

/** Tells online operators when a newer release of the plugin is available. */
public class UpdateNoticeListener implements Listener {

    private final JavaPlugin plugin;
    private final UpdateChecker checker;

    public UpdateNoticeListener(JavaPlugin plugin, UpdateChecker checker) {
        this.plugin = plugin;
        this.checker = checker;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String latest = checker.getLatestVersion();
        if (latest == null || !player.isOp()) return;
        // Send a moment after join, so the message is not lost in the join spam.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage(ChatColor.AQUA + "KPE File Manager " + latest + " is available (you have "
                    + plugin.getDescription().getVersion() + ").");
            if (checker.getReleaseUrl() != null) {
                player.sendMessage(ChatColor.GRAY + checker.getReleaseUrl());
            }
        }, 60L);
    }
}
