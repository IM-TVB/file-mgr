package site.kpeclub.filemanager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * A JDK logging Handler attached to the root logger to mirror everything
 * that reaches the server console (this plugin's own logs, other plugins',
 * and the server's) into ConsoleManager's ring buffer.
 */
public class ConsoleLogHandler extends Handler {

    private final ConsoleManager consoleManager;

    public ConsoleLogHandler(ConsoleManager consoleManager) {
        this.consoleManager = consoleManager;
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() < Level.INFO.intValue()) return;

        String message = record.getMessage();
        if (message == null) message = "";

        String loggerName = record.getLoggerName();
        String prefix = (loggerName == null || loggerName.isBlank()) ? "" : "[" + loggerName + "] ";

        for (String line : message.split("\n", -1)) {
            String clean = stripFormatting(line);
            if (!clean.isBlank()) {
                consoleManager.addLine(prefix + clean);
            }
        }

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringWriter sw = new StringWriter();
            thrown.printStackTrace(new PrintWriter(sw));
            for (String line : sw.toString().split("\n", -1)) {
                String clean = stripFormatting(line);
                if (!clean.isBlank()) consoleManager.addLine(clean);
            }
        }
    }

    private static String stripFormatting(String s) {
        // Minecraft "§"-style color codes and raw ANSI escape sequences.
        return s.replaceAll("\u00A7[0-9A-FK-ORa-fk-or]", "")
                .replaceAll("\u001B\\[[;\\d]*m", "");
    }

    @Override
    public void flush() {
        // No buffering here — ConsoleManager owns its own persistence timing.
    }

    @Override
    public void close() throws SecurityException {
        // Nothing to release; ConsoleManager removes this handler on stop().
    }
}
