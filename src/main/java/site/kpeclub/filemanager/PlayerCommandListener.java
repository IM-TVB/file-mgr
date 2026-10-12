package site.kpeclub.filemanager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** Logs every command a player runs to the day's cmd-history file. */
public class PlayerCommandListener implements Listener {

    private final ConsoleManager consoleManager;

    public PlayerCommandListener(ConsoleManager consoleManager) {
        this.consoleManager = consoleManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        consoleManager.logPlayerCommand(player.getName(), event.getMessage());
    }
}
