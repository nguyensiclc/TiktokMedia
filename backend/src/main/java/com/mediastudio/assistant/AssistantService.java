package com.mediastudio.assistant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobContext;
import com.mediastudio.job.JobService;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The chat assistant: every user message runs {@code claude -p --resume <session>} with one MCP server (app-mcp.mjs)
 * that can only call this app's REST API. The reply streams to the chat window as JSON log lines of an "assistant"
 * job: {"k":"text","v":…}, {"k":"tool","v":label}, {"k":"changed","projectId":…}. The conversation is kept in
 * {@code <workspace>/assistant/chat.json} so it survives restarts and is the same on every device.
 */
@Service
public class AssistantService {
    private final SettingsService settings;
    private final JobService jobs;
    private final ProjectService projects;
    private final Environment env;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ObjectMapper compact = new ObjectMapper();
    private Chat chat;

    public AssistantService(SettingsService settings, JobService jobs, ProjectService projects, Environment env) {
        this.settings = settings;
        this.jobs = jobs;
        this.projects = projects;
        this.env = env;
    }

    public static class Msg {
        /** user or assistant */
        public String role;
        public String text = "";
        /** Short labels of what the assistant did ("Viết kịch bản", "Chờ tác vụ"…). */
        public List<String> tools = new ArrayList<>();
        public String projectId;
        public boolean error;
        /** web or telegram */
        public String via;
        public Instant at = Instant.now();
    }

    public static class Chat {
        /** Claude CLI session id, resumed on the next message; null = a new conversation. */
        public String sessionId;
        public List<Msg> messages = new ArrayList<>();
        /** Job of the reply being written; null when idle. */
        public String jobId;
        public Double costUsd = 0.0;
        /** Project the conversation last worked on (Telegram has no "open project", so this stands in). */
        public String lastProjectId;
    }

    private Path dir() throws IOException {
        Path d = settings.get().workspace().resolve("assistant");
        Files.createDirectories(d);
        return d;
    }

    public synchronized Chat chat() {
        if (chat == null) {
            try {
                Path f = dir().resolve("chat.json");
                chat = Files.exists(f) ? mapper.readValue(f.toFile(), Chat.class) : new Chat();
            } catch (IOException e) {
                chat = new Chat();
            }
            // A reply that was running when the server stopped is over.
            if (chat.jobId != null) {
                try {
                    jobs.get(chat.jobId);
                } catch (Exception e) {
                    chat.jobId = null;
                }
            }
        }
        return chat;
    }

    private synchronized void save() {
        try {
            mapper.writeValue(dir().resolve("chat.json").toFile(), chat);
        } catch (IOException ignored) {
        }
    }

    /** Starts a new conversation (the old one is kept as chat-<time>.json). */
    public synchronized Chat reset() throws IOException {
        Chat c = chat();
        if (c.jobId != null) jobs.cancel(c.jobId);
        Path f = dir().resolve("chat.json");
        if (Files.exists(f) && !c.messages.isEmpty()) {
            Files.move(f, dir().resolve("chat-" + Instant.now().toString().replace(":", "-") + ".json"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        chat = new Chat();
        save();
        return chat;
    }

    /** Gets the reply as it is written (Telegram uses this; the web window reads the job stream instead). */
    public interface Listener {
        default void text(String t) { }

        default void tool(String label) { }

        default void done(Msg reply) { }
    }

    /** Listener of the reply being written (one reply at a time); null = none. */
    private volatile Listener listener;

    private void emit(JobContext ctx, String k, String v) {
        ctx.log(line(k, v));
        Listener l = listener;
        if (l == null) return;
        try {
            if ("text".equals(k)) l.text(v);
            else l.tool(v);
        } catch (Exception ignored) {
        }
    }

    public Job send(String text, String projectId, boolean restricted) {
        return send(text, projectId, restricted, "web", null);
    }

    /** @param via "web" or "telegram" (shown in the chat, and Telegram replies are kept short) */
    public synchronized Job send(String text, String projectId, boolean restricted, String via, Listener l) {
        Chat c = chat();
        if (c.jobId != null) {
            try {
                if (jobs.get(c.jobId).status == Job.Status.RUNNING) {
                    throw new IllegalStateException("Trợ lý đang trả lời, đợi một chút hoặc bấm Dừng.");
                }
            } catch (java.util.NoSuchElementException ignored) {
            }
        }
        Msg user = new Msg();
        user.role = "user";
        user.text = text;
        user.projectId = blank(projectId) ? null : projectId;
        user.via = via;
        c.messages.add(user);
        Msg reply = new Msg();
        reply.role = "assistant";
        reply.projectId = user.projectId;
        reply.via = via;
        c.messages.add(reply);
        if (user.projectId != null) c.lastProjectId = user.projectId;

        // A long earlier conversation can outweigh the system prompt: repeat the way to speak with every message.
        String speak = "[Nhắc: em là nữ thư ký, xưng \"em\", gọi \"anh\", mở đầu bằng \"Dạ\", lễ phép, cuối câu \"ạ\".]\n";
        String prompt;
        if ("telegram".equals(via)) {
            prompt = speak + "[Nhắn qua Telegram: trả lời ngắn gọn, văn bản thường, không bảng. Muốn cho người dùng xem hình / video / "
                    + "bộ ảnh thì gửi bằng công cụ send_file.]\n"
                    + (c.lastProjectId != null ? context(c.lastProjectId).replace("[Đang mở dự án", "[Dự án đang làm gần nhất") : "")
                    + text;
        } else {
            prompt = speak + context(user.projectId) + text;
        }
        listener = l;
        Job job = jobs.start("assistant", "Trợ lý: " + abbrev(text, 60), null, null,
                ctx -> run(prompt, reply, restricted, ctx));
        c.jobId = job.id;
        save();
        return job;
    }

    public boolean busy() {
        Chat c = chat();
        if (c.jobId == null) return false;
        try {
            return jobs.get(c.jobId).status == Job.Status.RUNNING;
        } catch (java.util.NoSuchElementException e) {
            return false;
        }
    }

    public void cancel() {
        Chat c = chat();
        if (c.jobId != null) jobs.cancel(c.jobId);
    }

    private String context(String projectId) {
        if (projectId == null) return "";
        try {
            Project p = projects.get(projectId);
            return "[Đang mở dự án " + p.id + " \"" + p.name + "\"]\n";
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ running Claude

    private void run(String prompt, Msg reply, boolean restricted, JobContext ctx) throws Exception {
        try {
            String sid = chat().sessionId;
            boolean ok = runOnce(prompt, sid, reply, restricted, ctx);
            if (!ok && sid != null && reply.text.isBlank()) {
                // The saved session is gone (other machine, cleared ~/.claude): start a fresh one.
                emit(ctx, "tool", "Bắt đầu phiên mới");
                chat().sessionId = null;
                ok = runOnce(prompt, null, reply, restricted, ctx);
            }
            if (!ok && reply.text.isBlank()) {
                reply.error = true;
                reply.text = "Không chạy được Claude CLI. Kiểm tra Claude đã đăng nhập trên máy chủ (claude setup-token).";
                emit(ctx, "text", reply.text);
            }
        } catch (Exception e) {
            if (!ctx.cancelled()) {
                reply.error = true;
                reply.text = (reply.text.isBlank() ? "" : reply.text + "\n\n") + "⚠ " + e.getMessage();
            }
            throw e;
        } finally {
            if (ctx.cancelled() && reply.text.isBlank()) reply.text = "(đã dừng)";
            synchronized (this) {
                chat().jobId = null;
                save();
            }
            Listener l = listener;
            listener = null;
            if (l != null) {
                try {
                    l.done(reply);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** @return false when Claude ended without a successful result */
    private boolean runOnce(String prompt, String sessionId, Msg reply, boolean restricted, JobContext ctx) throws Exception {
        AppSettings s = settings.get();
        Path dir = dir();
        Path script = dir.resolve("app-mcp.mjs");
        try (InputStream in = getClass().getResourceAsStream("/assistant/app-mcp.mjs")) {
            if (in == null) throw new IOException("Thiếu app-mcp.mjs trong bản build");
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("command", blank(s.nodePath) ? "node" : s.nodePath);
        app.put("args", List.of(script.toAbsolutePath().toString()));
        app.put("env", Map.of("APP_URL", "http://127.0.0.1:" + port(), "APP_RESTRICTED", restricted ? "1" : "0"));
        Path cfg = dir.resolve("mcp.json");
        mapper.writeValue(cfg.toFile(), Map.of("mcpServers", Map.of("app", app)));

        String model = !blank(s.claudeModelAssistant) ? s.claudeModelAssistant : s.claudeModel;
        List<String> cmd = new ArrayList<>(List.of(s.claudePath, "-p",
                "--output-format", "stream-json", "--verbose",
                "--mcp-config", cfg.toString(), "--strict-mcp-config",
                "--allowedTools", "mcp__app,WebSearch",
                "--disallowedTools", "Bash,Edit,Write,NotebookEdit,Task",
                "--append-system-prompt", AssistantPrompts.SYSTEM));
        if (!blank(model)) cmd.addAll(List.of("--model", model.trim()));
        if (sessionId != null) cmd.addAll(List.of("--resume", sessionId));

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = pb.start();
        ctx.attach(p);
        try (OutputStream in = p.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder err = new StringBuilder();
        Thread errPump = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) if (err.length() < 4000) err.append(l).append('\n');
            } catch (IOException ignored) {
            }
        });

        boolean success = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.isBlank()) continue;
                JsonNode ev;
                try {
                    ev = compact.readTree(l);
                } catch (Exception e) {
                    continue;
                }
                String type = ev.path("type").asText();
                if (ev.hasNonNull("session_id")) {
                    synchronized (this) {
                        chat().sessionId = ev.path("session_id").asText();
                    }
                }
                if ("assistant".equals(type)) {
                    for (JsonNode c : ev.path("message").path("content")) {
                        String ct = c.path("type").asText();
                        if ("text".equals(ct)) {
                            String t = c.path("text").asText().trim();
                            if (t.isEmpty()) continue;
                            synchronized (this) {
                                reply.text = reply.text.isBlank() ? t : reply.text + "\n\n" + t;
                                save();
                            }
                            emit(ctx, "text", t);
                        } else if ("tool_use".equals(ct)) {
                            if ("ToolSearch".equals(c.path("name").asText())) continue; // CLI-internal, not an action
                            String label = label(c.path("name").asText(), c.path("input"));
                            synchronized (this) {
                                reply.tools.add(label);
                                save();
                            }
                            emit(ctx, "tool", label);
                            String changed = changedProject(c.path("name").asText(), c.path("input"));
                            if (changed != null) {
                                if (!"*".equals(changed)) {
                                    synchronized (this) {
                                        chat().lastProjectId = changed;
                                    }
                                }
                                Map<String, String> m = new LinkedHashMap<>();
                                m.put("k", "changed");
                                m.put("projectId", changed);
                                ctx.log(compact.writeValueAsString(m));
                            }
                        }
                    }
                } else if ("result".equals(type)) {
                    success = !ev.path("is_error").asBoolean() && "success".equals(ev.path("subtype").asText());
                    if (ev.has("total_cost_usd")) {
                        synchronized (this) {
                            Chat c = chat();
                            c.costUsd = (c.costUsd == null ? 0 : c.costUsd) + ev.path("total_cost_usd").asDouble();
                        }
                    }
                    String res = ev.path("result").asText("");
                    if (!success && !res.isBlank() && sessionId == null) {
                        synchronized (this) {
                            reply.error = true;
                            reply.text = reply.text.isBlank() ? res : reply.text + "\n\n⚠ " + res;
                        }
                        emit(ctx, "text", "⚠ " + res);
                    }
                }
            }
        }
        p.waitFor();
        errPump.join(2000);
        if (ctx.cancelled()) return true;
        if (!success && err.length() > 0 && reply.text.isBlank() && sessionId == null) {
            throw new IOException("Claude CLI: " + abbrev(err.toString(), 600));
        }
        return success;
    }

    private int port() {
        String p = env.getProperty("local.server.port");
        if (p == null) p = env.getProperty("server.port", "8787");
        return Integer.parseInt(p);
    }

    private String line(String k, String v) {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("k", k);
            m.put("v", v);
            return compact.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------ friendly labels

    private record Rule(String method, Pattern path, String label) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("POST", Pattern.compile("^/api/projects$"), "Tạo dự án mới"),
            new Rule("GET", Pattern.compile("^/api/projects$"), "Xem danh sách dự án"),
            new Rule("GET", Pattern.compile("^/api/projects/[^/]+$"), "Đọc dự án"),
            new Rule("PUT", Pattern.compile("^/api/projects/[^/]+$"), "Lưu thay đổi dự án"),
            new Rule("DELETE", Pattern.compile("^/api/projects/[^/]+$"), "Xoá dự án"),
            new Rule("POST", Pattern.compile("/storyboard$"), "Viết kịch bản"),
            new Rule("POST", Pattern.compile("/analyze$"), "Phân tích ảnh nguồn"),
            new Rule("POST", Pattern.compile("/drama/"), "Viết phim ngắn"),
            new Rule("POST", Pattern.compile("/(flow-gen|flow-all|flow)$"), "Tạo hình bằng Google Flow"),
            new Rule("POST", Pattern.compile("/(canva-all|canva)$"), "Tạo hình bằng Canva"),
            new Rule("POST", Pattern.compile("/characters/portraits$"), "Vẽ chân dung nhân vật"),
            new Rule("POST", Pattern.compile("/voice$"), "Tạo giọng đọc"),
            new Rule("POST", Pattern.compile("/render$"), "Xuất video"),
            new Rule("POST", Pattern.compile("/slides/cover$"), "Làm lại ảnh bìa"),
            new Rule("POST", Pattern.compile("/slides$"), "Xuất bộ ảnh lướt"),
            new Rule("POST", Pattern.compile("/tiktok/caption$"), "Viết mô tả TikTok"),
            new Rule("POST", Pattern.compile("/tiktok/(studio-post|publish)$"), "Đăng lên TikTok"),
            new Rule("POST", Pattern.compile("^/api/queue$"), "Thêm vào danh sách đăng"),
            new Rule("POST", Pattern.compile("^/api/queue/"), "Cập nhật danh sách đăng"),
            new Rule("GET", Pattern.compile("^/api/queue/status"), "Xem hàng đợi đăng bài"),
            new Rule("GET", Pattern.compile("^/api/jobs"), "Xem tác vụ"),
            new Rule("POST", Pattern.compile("/cancel$"), "Huỷ tác vụ"),
            new Rule("GET", Pattern.compile("^/api/settings"), "Đọc cài đặt"),
            new Rule("PUT", Pattern.compile("^/api/settings"), "Đổi cài đặt"),
            new Rule("GET", Pattern.compile("^/api/system/check"), "Kiểm tra hệ thống"),
            new Rule("GET", Pattern.compile("^/api/voice/profiles"), "Xem danh sách giọng"),
            new Rule("DELETE", Pattern.compile("/media"), "Xoá media của cảnh"),
            new Rule("POST", Pattern.compile("/duplicate$"), "Nhân đôi cảnh"));

    static String label(String tool, JsonNode in) {
        if (tool.endsWith("__wait_job")) return "Chờ tác vụ chạy xong";
        if (tool.endsWith("__view_image")) return "Xem hình " + in.path("name").asText("");
        if (tool.endsWith("__send_file")) return "Gửi file qua Telegram";
        if (tool.equals("WebSearch")) return "Tìm trên web: " + abbrev(in.path("query").asText(""), 60);
        if (tool.endsWith("__api")) {
            String m = in.path("method").asText("GET").toUpperCase();
            String path = in.path("path").asText("").split("\\?")[0];
            for (Rule r : RULES) if (r.method.equals(m) && r.path.matcher(path).find()) return r.label;
            return m + " " + path;
        }
        return tool;
    }

    /** Project an API call changed (so an open page reloads), or null for reads. */
    static String changedProject(String tool, JsonNode in) {
        if (!tool.endsWith("__api")) return null;
        String m = in.path("method").asText("GET").toUpperCase();
        if ("GET".equals(m)) return null;
        var mt = Pattern.compile("^/api/(?:queue/)?projects/([^/?]+)").matcher(in.path("path").asText(""));
        return mt.find() ? mt.group(1) : "*";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbrev(String s, int max) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
