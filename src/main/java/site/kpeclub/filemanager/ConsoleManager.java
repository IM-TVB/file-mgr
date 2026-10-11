package site.kpeclub.filemanager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures the server console into a capped in-memory ring buffer, backed by a
 * periodically-saved console.txt snapshot, and logs every player-issued command
 * to a per-day cmd-history-yyyy-MM-dd.txt audit file.
 *
 * Capture happens through a Log4j appender on the root logger (see
 * ConsoleLogAppender). That appender is global state owned by the Log4j context,
 * not by Bukkit, so it MUST be removed in stop(), or a plugin reload leaks the
 * old classloader.
 */
public class ConsoleManager {

    public static class Line {
        public final long seq;
        public final String level;
        public final String text;

        Line(long seq, String level, String text) {
            this.seq = seq;
            this.level = level;
            this.text = text;
        }
    }

    private static final String APPENDER_NAME = "KPEFileManagerConsole";
    private static final Pattern LEVEL_IN_TEXT = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2} (\\w+)\\]");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JavaPlugin plugin;
    private volatile int maxLines;
    private final File dataFolder;
    private final File consoleFile;

    private final Deque<Line> buffer = new ArrayDeque<>();
    private final AtomicLong seqCounter = new AtomicLong(0);
    private volatile boolean dirty = false;

    private ConsoleLogAppender appender;

    public ConsoleManager(JavaPlugin plugin, int maxLines) {
        this.plugin = plugin;
        this.maxLines = Math.max(1, maxLines);
        this.dataFolder = plugin.getDataFolder();
        this.consoleFile = new File(dataFolder, "console.txt");
    }

    /** Updates the line cap live (used by /filemgr reload) without touching the log hook. */
    public void setMaxLines(int newMaxLines) {
        this.maxLines = Math.max(1, newMaxLines);
        synchronized (buffer) {
            trimBuffer();
        }
        dirty = true;
    }

    /** Starts capturing console output. Call once from onEnable(). */
    public void start() {
        if (!dataFolder.exists()) dataFolder.mkdirs();
        loadExisting();

        appender = new ConsoleLogAppender(APPENDER_NAME, this);
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getRootLogger().addAppender(appender, null, null);
        ctx.updateLoggers();

        // Bukkit auto-cancels plugin-owned scheduled tasks on disable, so this
        // doesn't need manual cleanup the way the appender does.
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushIfDirty, 100L, 100L);
    }

    /** Stops capturing and does a final flush. Call once from onDisable(). */
    public void stop() {
        if (appender != null) {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeAppender(APPENDER_NAME);
            ctx.updateLoggers();
            appender.stop();
            appender = null;
        }
        flushIfDirty();
    }

    private void loadExisting() {
        if (!consoleFile.exists()) return;
        try {
            List<String> lines = Files.readAllLines(consoleFile.toPath(), StandardCharsets.UTF_8);
            synchronized (buffer) {
                for (String l : lines) {
                    buffer.addLast(new Line(seqCounter.incrementAndGet(), levelFromText(l), l));
                }
                trimBuffer();
            }
        } catch (IOException ignored) {
            // Corrupt or unreadable snapshot — start fresh rather than fail plugin enable.
        }
    }

    /** Restores the level of a saved line from its "[HH:mm:ss LEVEL]" prefix. */
    private static String levelFromText(String text) {
        if (text.contains("(web console, ")) return "CMD";
        Matcher m = LEVEL_IN_TEXT.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    void addLine(String level, String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (buffer) {
            buffer.addLast(new Line(seqCounter.incrementAndGet(), level == null ? "" : level, text));
            trimBuffer();
        }
        dirty = true;
    }

    private void trimBuffer() {
        while (buffer.size() > maxLines) {
            buffer.removeFirst();
        }
    }

    /** Lines with seq strictly greater than sinceSeq, oldest first. */
    public List<Line> getLinesSince(long sinceSeq) {
        synchronized (buffer) {
            List<Line> out = new ArrayList<>();
            for (Line l : buffer) {
                if (l.seq > sinceSeq) out.add(l);
            }
            return out;
        }
    }

    public long getLatestSeq() {
        synchronized (buffer) {
            return buffer.isEmpty() ? 0 : buffer.peekLast().seq;
        }
    }

    private void flushIfDirty() {
        if (!dirty) return;
        dirty = false;
        List<String> snapshot;
        synchronized (buffer) {
            snapshot = new ArrayList<>(buffer.size());
            for (Line l : buffer) snapshot.add(l.text);
        }
        try (BufferedWriter w = Files.newBufferedWriter(consoleFile.toPath(), StandardCharsets.UTF_8)) {
            for (String line : snapshot) {
                w.write(line);
                w.newLine();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save console.txt: " + e.getMessage());
        }
    }

    /** Dispatches a command as the console sender, on the main thread, and logs it. */
    public void sendCommand(String command, String issuedByPlayer) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) return;

        addLine("CMD", "[" + LocalDateTime.now().format(TIME_FMT) + "] (web console, " + issuedByPlayer + ") " + trimmed);
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), trimmed));
    }

    /** Appends a player-issued in-game command to today's cmd-history file. */
    public void logPlayerCommand(String playerName, String fullMessage) {
        String date = LocalDate.now().format(DATE_FMT);
        File file = new File(dataFolder, "cmd-history-" + date + ".txt");
        String line = "[" + LocalDateTime.now().format(TIME_FMT) + "] " + playerName + ": " + fullMessage;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file, true))) {
            w.write(line);
            w.newLine();
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write cmd-history: " + e.getMessage());
        }
    }
}
