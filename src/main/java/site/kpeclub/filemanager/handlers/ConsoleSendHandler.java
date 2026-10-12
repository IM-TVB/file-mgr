package site.kpeclub.filemanager.handlers;

import com.sun.net.httpserver.HttpExchange;
import site.kpeclub.filemanager.ConsoleManager;
import site.kpeclub.filemanager.TokenManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ConsoleSendHandler extends BaseApiHandler {

    private final ConsoleManager consoleManager;

    public ConsoleSendHandler(File root, TokenManager tokenManager, ConsoleManager consoleManager) {
        super(root, tokenManager);
        this.consoleManager = consoleManager;
    }

    @Override
    protected void handleAuthed(HttpExchange exchange, String player) throws IOException {
        byte[] body = readBody(exchange);
        String command = new String(body, StandardCharsets.UTF_8).trim();
        if (command.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"empty command\"}");
            return;
        }
        if (command.startsWith("/")) command = command.substring(1);

        consoleManager.sendCommand(command, player);
        sendJson(exchange, 200, "{\"ok\":true}");
    }
}
