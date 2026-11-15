package com.mediastudio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediastudio.job.JobContext;
import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs Claude Code headless:
 * <pre>claude -p --output-format stream-json --verbose --mcp-config cfg --strict-mcp-config
 *        --permission-mode acceptEdits --allowedTools ... --append-system-prompt ...  &lt; prompt</pre>
 * and turns the stream-json events into readable job log lines.
 */
@Component
public class ClaudeCli {
    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AgyCli agy;

    public ClaudeCli(SettingsService settings, AgyCli agy) {
        this.settings = settings;
        this.agy = agy;
    }

    /**
     * Writing/analysis jobs (no MCP): runs on the CLI chosen in Settings — agy, or Claude with the
     * "writing" model. Callers that need MCP tools keep using {@link #run}.
     */
    public Result runWriting(String prompt, String systemPrompt, Path cwd, List<String> claudeTools,
                             boolean needsVision, JobContext ctx) throws IOException, InterruptedException {
        return runWriting(prompt, systemPrompt, cwd, claudeTools, needsVision, null, null, ctx);
    }

    /**
     * @param ai    "claude" or "agy" chosen by the user for this run; null/blank = Settings' aiWriting.
     * @param model model for the chosen tool; null/blank = the tool's Settings model.
     */
    public Result runWriting(String prompt, String systemPrompt, Path cwd, List<String> claudeTools,
                             boolean needsVision, String ai, String model, JobContext ctx) throws IOException, InterruptedException {
        AppSettings s = settings.get();
        String tool = ai != null && !ai.isBlank() ? ai.trim() : s.aiWriting;
        if ("agy".equalsIgnoreCase(tool)) {
            // agy opens image files but cannot see them (it falls back to shell OCR, which headless mode denies).
            if (!needsVision) return agy.run(prompt, systemPrompt, cwd, model, ctx);
            ctx.log("ℹ Bước này cần xem ảnh → dùng Claude (agy không nhìn được ảnh)");
            model = null; // the agy model name means nothing to Claude
        }
        Path cfg = cwd.resolve(".mcp-empty.json");
        if (!java.nio.file.Files.exists(cfg)) java.nio.file.Files.writeString(cfg, "{\"mcpServers\":{}}");
        String m = model != null && !model.isBlank() ? model : s.claudeModelWriting;
        return run(prompt, systemPrompt, cwd, cfg, claudeTools, m, ctx);
    }

    /** @param tools tool names Claude had in this run (from the init event), incl. mcp__server__tool */
    public record Result(String text, Double costUsd, boolean error, List<String> tools) {
        public boolean hasServerTools(String server) {
            return tools.stream().anyMatch(t -> t.startsWith("mcp__" + server + "__"));
        }
    }

    public Result run(String prompt, String systemPrompt, Path cwd, Path mcpConfig,
                      List<String> allowedTools, JobContext ctx) throws IOException, InterruptedException {
        return run(prompt, systemPrompt, cwd, mcpConfig, allowedTools, null, ctx);
    }

    /** @param model model alias/id for this run; null or blank = Settings' claudeModel (or CLI default). */
    public Result run(String prompt, String systemPrompt, Path cwd, Path mcpConfig,
                      List<String> allowedTools, String model, JobContext ctx) throws IOException, InterruptedException {
        AppSettings s = settings.get();
        String m = model != null && !model.isBlank() ? model.trim() : s.claudeModel;
        List<String> cmd = new ArrayList<>(List.of(s.claudePath, "-p",
                "--output-format", "stream-json", "--verbose",
                "--permission-mode", "acceptEdits"));
        if (mcpConfig != null) cmd.addAll(List.of("--mcp-config", mcpConfig.toString(), "--strict-mcp-config"));
        if (!allowedTools.isEmpty()) cmd.addAll(List.of("--allowedTools", String.join(",", allowedTools)));
        if (systemPrompt != null && !systemPrompt.isBlank()) cmd.addAll(List.of("--append-system-prompt", systemPrompt));
        if (m != null && !m.isBlank()) cmd.addAll(List.of("--model", m));

        ctx.log("▶ claude -p" + (m != null && !m.isBlank() ? " --model " + m : "") + " (cwd: " + cwd + ")");
        ctx.log("  tools: " + String.join(", ", allowedTools));

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = pb.start();
        ctx.attach(p);

        // Prompt goes through stdin so the variadic --allowedTools flag can't swallow it.
        try (OutputStream in = p.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
        }

        Thread errPump = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (!line.isBlank()) ctx.log("  [stderr] " + line);
            } catch (IOException ignored) {
            }
        });

        Result result = null;
        List<String> tools = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode ev;
                try {
                    ev = mapper.readTree(line);
                } catch (Exception e) {
                    ctx.log("  " + line);
                    continue;
                }
                if ("system".equals(ev.path("type").asText()) && "init".equals(ev.path("subtype").asText())) {
                    for (JsonNode t : ev.path("tools")) tools.add(t.asText());
                }
                Result maybe = handle(ev, ctx);
                if (maybe != null) result = new Result(maybe.text(), maybe.costUsd(), maybe.error(), List.copyOf(tools));
            }
        }
        int code = p.waitFor();
        errPump.join(2000);
        if (ctx.cancelled()) throw new IOException("Đã huỷ");
        if (result == null) throw new IOException("Claude CLI kết thúc (mã " + code + ") mà không trả kết quả");
        if (result.error()) throw new IOException("Claude báo lỗi: " + result.text());
        return result;
    }

    private Result handle(JsonNode ev, JobContext ctx) {
        String type = ev.path("type").asText();
        switch (type) {
            case "system" -> {
                if ("init".equals(ev.path("subtype").asText())) {
                    List<String> servers = new ArrayList<>();
                    for (JsonNode m : ev.path("mcp_servers")) {
                        servers.add(m.path("name").asText() + "=" + m.path("status").asText());
                    }
                    ctx.log("  model: " + ev.path("model").asText() + "  mcp: " + String.join(", ", servers));
                }
            }
            case "assistant" -> {
                for (JsonNode c : ev.path("message").path("content")) {
                    switch (c.path("type").asText()) {
                        case "text" -> {
                            String t = c.path("text").asText().trim();
                            if (!t.isEmpty()) ctx.log("💬 " + t);
                        }
                        case "tool_use" -> ctx.log("🔧 " + c.path("name").asText() + " " + abbrev(c.path("input").toString(), 300));
                        default -> { }
                    }
                }
            }
            case "user" -> {
                for (JsonNode c : ev.path("message").path("content")) {
                    if ("tool_result".equals(c.path("type").asText())) {
                        JsonNode content = c.path("content");
                        String text = content.isTextual() ? content.asText() : textOf(content);
                        ctx.log((c.path("is_error").asBoolean() ? "  ↳ ✖ " : "  ↳ ") + abbrev(text, 400));
                    }
                }
            }
            case "result" -> {
                boolean err = ev.path("is_error").asBoolean() || !"success".equals(ev.path("subtype").asText());
                Double cost = ev.has("total_cost_usd") ? ev.path("total_cost_usd").asDouble() : null;
                String text = ev.path("result").asText("");
                ctx.log(String.format(Locale.ROOT, "■ Kết thúc: %s, %d lượt%s",
                        ev.path("subtype").asText(), ev.path("num_turns").asInt(),
                        cost == null ? "" : String.format(Locale.ROOT, ", $%.4f", cost)));
                return new Result(text, cost, err, List.of());
            }
            default -> { }
        }
        return null;
    }

    private static String textOf(JsonNode arr) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : arr) {
            if (n.has("text")) sb.append(n.path("text").asText()).append(' ');
            else if ("image".equals(n.path("type").asText())) sb.append("[image] ");
        }
        return sb.toString();
    }

    private static String abbrev(String s, int max) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
