package site.kpeclub.filemanager;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Log4j appender on the root logger. Paper prints the server console through
 * Log4j, so this sees everything the real console shows: server messages,
 * plugin messages, and the output of commands run from the web console.
 * Lines use the same "[HH:mm:ss LEVEL]: message" layout Paper uses.
 */
public class ConsoleLogAppender extends AbstractAppender {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConsoleManager consoleManager;

    public ConsoleLogAppender(String name, ConsoleManager consoleManager) {
        super(name, null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        this.consoleManager = consoleManager;
    }

    @Override
    public void append(LogEvent event) {
        String level = event.getLevel().name();
        String time = LocalTime.ofInstant(Instant.ofEpochMilli(event.getTimeMillis()), ZoneId.systemDefault())
                .format(TIME_FMT);
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();

        String[] lines = message.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String clean = stripFormatting(lines[i]);
            if (clean.isBlank()) continue;
            // Like the real console, only the first line of a multi-line message gets the prefix.
            consoleManager.addLine(level, i == 0 ? "[" + time + " " + level + "]: " + clean : clean);
        }

        if (event.getThrown() != null) {
            StringWriter sw = new StringWriter();
            event.getThrown().printStackTrace(new PrintWriter(sw));
            for (String line : sw.toString().split("\n")) {
                String clean = stripFormatting(line);
                if (!clean.isBlank()) consoleManager.addLine(level, clean);
            }
        }
    }

    /**
     * Keeps color codes: Minecraft "§x" codes and ANSI escapes are shown as colors by
     * the web console. Only carriage returns are removed.
     */
    private static String stripFormatting(String s) {
        return s.replace("\r", "");
    }
}
