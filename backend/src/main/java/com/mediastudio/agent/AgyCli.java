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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the Antigravity CLI (agy) headless for plain writing/analysis jobs (no MCP):
 * <pre>agy --output-format stream-json --mode accept-edits --model M -p="Read ./.agy-prompt.md …"</pre>
 * The real prompt goes into a file in the working directory: agy takes the prompt as an argument, and
 * Windows command lines mangle long or non-ASCII text.
 */
@Component
public class AgyCli {
    static final String PROMPT_FILE = ".agy-prompt.md";

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgyCli(SettingsService settings) {
        this.settings = settings;
    }

    public ClaudeCli.Result run(String prompt, String systemPrompt, Path cwd, JobContext ctx)
            throws IOException, InterruptedException {
        return run(prompt, systemPrompt, cwd, null, ctx);
    }

    /** @param modelOverride model for this run; null/blank = Settings' agyModel. */
    public ClaudeCli.Result run(String prompt, String systemPrompt, Path cwd, String modelOverride, JobContext ctx)
            throws IOException, InterruptedException {
        AppSettings s = settings.get();
        String model = modelOverride != null && !modelOverride.isBlank() ? modelOverride.trim()
                : s.agyModel == null ? "" : s.agyModel.trim();

        StringBuilder full = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) full.append(systemPrompt.trim()).append("\n\n---\n\n");
        full.append(prompt);
        Path pf = cwd.resolve(PROMPT_FILE);
        Files.writeString(pf, full.toString(), StandardCharsets.UTF_8);

        List<String> cmd = new ArrayList<>(List.of(s.agyPath, "--output-format", "stream-json", "--mode", "accept-edits"));
        if (!model.isEmpty()) cmd.addAll(List.of("--model", model));
        cmd.add("-p=Read the file ./" + PROMPT_FILE + " in the current directory, then carry out every instruction in it "
                + "exactly (including writing the output files it names). Do not ask questions.");

        ctx.log("▶ agy" + (model.isEmpty() ? "" : " --model " + model) + " (cwd: " + cwd + ")");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        Process p = pb.start();
        ctx.attach(p);
        p.getOutputStream().close();

        Thread errPump = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (!line.isBlank()) ctx.log("  [stderr] " + line);
            } catch (IOException ignored) {
            }
        });

        ClaudeCli.Result result = null;
        List<String> tools = new ArrayList<>();
        StringBuilder said = new StringBuilder();
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
                switch (ev.path("event").asText()) {
                    case "init" -> {
                        for (JsonNode t : ev.path("init").path("tools")) tools.add(t.asText());
                        ctx.log("  model: " + ev.path("init").path("model").asText());
                    }
                    case "step_update" -> step(ev.path("step_update"), said, ctx);
                    case "result" -> {
                        JsonNode res = ev.path("result");
                        boolean ok = "SUCCESS".equals(res.path("status").asText());
                        JsonNode u = res.path("usage");
                        ctx.log("■ Kết thúc: " + res.path("status").asText() + ", " + res.path("num_turns").asInt() + " lượt, "
                                + u.path("total_tokens").asLong() + " token (vào " + u.path("input_tokens").asLong()
                                + ", ra " + u.path("output_tokens").asLong() + ")");
                        result = new ClaudeCli.Result(res.path("response").asText(""), null, !ok, List.copyOf(tools));
                    }
                    default -> { }
                }
            }
        }
        int code = p.waitFor();
        errPump.join(2000);
        Files.deleteIfExists(pf);
        if (ctx.cancelled()) throw new IOException("Đã huỷ");
        if (result == null) throw new IOException("agy kết thúc (mã " + code + ") mà không trả kết quả");
        if (result.error()) throw new IOException("agy báo lỗi: " + result.text());
        return result;
    }

    /** Agent text streams as deltas; log a whole reply at once, and each non-text step by its type. */
    private void step(JsonNode su, StringBuilder said, JobContext ctx) {
        String type = su.path("step_type").asText();
        String state = su.path("state").asText();
        if ("agent_response".equals(type)) {
            said.append(su.path("text_delta").asText(""));
            if ("DONE".equals(state)) {
                String t = said.toString().trim();
                said.setLength(0);
                if (!t.isEmpty()) ctx.log("💬 " + t);
            }
        } else if (!"user_input".equals(type) && "DONE".equals(state)) {
            ctx.log("🔧 " + type);
        }
    }
}
