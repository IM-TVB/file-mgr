package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.ConsoleManager;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ConsoleHistoryHandler extends BaseApiHandler {

    private final ConsoleManager consoleManager;

    public ConsoleHistoryHandler(File root, TokenManager tokenManager, ConsoleManager consoleManager) {
        super(root, tokenManager);
        this.consoleManager = consoleManager;
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        Map<String, String> params = queryParams(exchange);
        long since = 0;
        try {
            since = Long.parseLong(params.getOrDefault("since", "0"));
        } catch (NumberFormatException ignored) {
        }

        List<ConsoleManager.Line> lines = consoleManager.getLinesSince(since);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            ConsoleManager.Line l = lines.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"seq\":").append(l.seq).append(",\"text\":").append(jsonString(l.text)).append('}');
        }
        sb.append("],\"lastSeq\":").append(consoleManager.getLatestSeq()).append('}');
        sendJson(exchange, 200, sb.toString());
    }
}
