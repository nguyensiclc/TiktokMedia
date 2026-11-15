package com.mediastudio.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the --mcp-config file passed to Claude CLI.
 * <ul>
 *   <li>playwright — drives Google Flow in a persistent Chrome profile (stdio, via npx)</li>
 *   <li>canva — Canva's remote MCP server (HTTP, OAuth through Claude CLI's /mcp)</li>
 *   <li>voicebox — Voicebox's built-in MCP server (HTTP, at /mcp)</li>
 * </ul>
 */
@Component
public class McpConfigWriter {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public McpConfigWriter(SettingsService settings) {
        this.settings = settings;
    }

    /** Server names enabled in the current settings, e.g. [playwright, canva, voicebox]. */
    public List<String> enabledServers() {
        AppSettings s = settings.get();
        List<String> names = new ArrayList<>();
        if (s.enableFlow) names.add("playwright");
        if (s.enableCanva) names.add("canva");
        if (voiceboxMcp(s)) names.add("voicebox");
        return names;
    }

    /**
     * @param target    file to write
     * @param outputDir where Playwright saves downloads/screenshots
     */
    public Path write(Path target, Path outputDir) throws IOException {
        AppSettings s = settings.get();
        Map<String, Object> servers = new LinkedHashMap<>();

        if (s.enableFlow) {
            Files.createDirectories(s.browserProfile());
            Files.createDirectories(outputDir);
            List<String> args = new ArrayList<>();
            if (WINDOWS) args.addAll(List.of("/c", "npx"));
            args.addAll(List.of("-y", s.playwrightMcpPackage,
                    "--browser", s.playwrightBrowser,
                    "--user-data-dir", s.browserProfile().toAbsolutePath().toString(),
                    "--output-dir", outputDir.toAbsolutePath().toString()));
            servers.put("playwright", Map.of("command", WINDOWS ? "cmd" : "npx", "args", args));
        }
        if (s.enableCanva) {
            servers.put("canva", Map.of("type", "http", "url", s.canvaMcpUrl));
        }
        if (voiceboxMcp(s)) {
            String base = s.voiceboxUrl.endsWith("/") ? s.voiceboxUrl : s.voiceboxUrl + "/";
            servers.put("voicebox", Map.of("type", "http", "url", base + "mcp",
                    "headers", Map.of("X-Voicebox-Client-Id", "media-studio")));
        }

        Files.createDirectories(target.getParent());
        mapper.writeValue(target.toFile(), Map.of("mcpServers", servers));
        return target;
    }

    private static boolean voiceboxMcp(AppSettings s) {
        return s.enableVoiceboxMcp && "voicebox".equals(s.voiceProvider);
    }

    /** Shared config at <workspace>/mcp.json, used for the interactive login console. */
    public Path writeGlobal() throws IOException {
        Path ws = settings.get().workspace();
        return write(ws.resolve("mcp.json"), ws.resolve("downloads"));
    }
}
