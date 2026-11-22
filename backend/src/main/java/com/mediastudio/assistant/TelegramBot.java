package com.mediastudio.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediastudio.project.ProjectService;
import com.mediastudio.settings.SettingsService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram front-end of the chat assistant. Long polling (getUpdates), so the server needs no public address:
 * it works behind home Wi-Fi / NAT. Only user ids in Settings (or TELEGRAM_ALLOWED_IDS) may talk to it; anyone else
 * is told their id so the owner can add it. Messages go into the same conversation as the web chat window.
 */
@Service
public class TelegramBot {
    private static final String API = "https://api.telegram.org/bot";
    private static final long MAX_PHOTO = 10L << 20, MAX_UPLOAD = 50L << 20;

    private final SettingsService settings;
    private final AssistantService assistant;
    private final ProjectService projects;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    /** Chats of allowed users that wrote to the bot: where send_file delivers. Saved in assistant/telegram.json. */
    private final Set<Long> chats = ConcurrentHashMap.newKeySet();
    private volatile String botName;
    private volatile String error;
    private volatile boolean running;

    public TelegramBot(SettingsService settings, AssistantService assistant, ProjectService projects) {
        this.settings = settings;
        this.assistant = assistant;
        this.projects = projects;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        loadChats();
        Thread.ofVirtual().name("telegram").start(this::loop);
    }

    String token() {
        String env = System.getenv("TELEGRAM_BOT_TOKEN");
        if (env != null && !env.isBlank()) return env.trim();
        String t = settings.get().telegramBotToken;
        return t == null ? "" : t.trim();
    }

    Set<Long> allowed() {
        Set<Long> ids = new HashSet<>();
        for (String src : new String[]{settings.get().telegramAllowedIds, System.getenv("TELEGRAM_ALLOWED_IDS")}) {
            if (src == null) continue;
            for (String p : src.split("[,;\\s]+")) {
                try {
                    if (!p.isBlank()) ids.add(Long.parseLong(p.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ids;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", !token().isEmpty());
        m.put("running", running);
        m.put("bot", botName);
        m.put("error", error);
        m.put("allowed", allowed().size());
        m.put("chats", chats.size());
        m.put("fromEnv", System.getenv("TELEGRAM_BOT_TOKEN") != null && !System.getenv("TELEGRAM_BOT_TOKEN").isBlank());
        return m;
    }

    // ------------------------------------------------------------------ polling

    private void loop() {
        String current = null;
        long offset = 0;
        while (true) {
            String token = token();
            if (token.isEmpty()) {
                running = false;
                botName = null;
                error = null;
                current = null;
                sleep(5000);
                continue;
            }
            try {
                if (!token.equals(current)) {
                    JsonNode me = call(token, "getMe", mapper.createObjectNode());
                    botName = me.path("username").asText();
                    current = token;
                    offset = 0;
                }
                running = true;
                ObjectNode q = mapper.createObjectNode().put("timeout", 50).put("offset", offset);
                q.putArray("allowed_updates").add("message");
                JsonNode updates = call(token, "getUpdates", q, Duration.ofSeconds(70));
                error = null;
                for (JsonNode u : updates) {
                    offset = Math.max(offset, u.path("update_id").asLong() + 1);
                    if (u.has("message")) {
                        JsonNode msg = u.path("message");
                        Thread.ofVirtual().start(() -> {
                            try {
                                handle(msg);
                            } catch (Exception e) {
                                error = e.getMessage();
                            }
                        });
                    }
                }
            } catch (Exception e) {
                running = false;
                error = e.getMessage();
                if (error != null && error.contains("401")) current = null; // bad token: re-check after a change
                sleep(10000);
            }
        }
    }

    private void handle(JsonNode msg) throws IOException {
        long chat = msg.path("chat").path("id").asLong();
        long from = msg.path("from").path("id").asLong();
        String text = msg.path("text").asText(msg.path("caption").asText("")).trim();

        if (!allowed().contains(from)) {
            send(chat, "Bạn chưa được phép dùng bot này.\nID Telegram của bạn: " + from
                    + "\nThêm ID này vào Media Studio → Cài đặt → Telegram (hoặc TELEGRAM_ALLOWED_IDS trong server.env) rồi nhắn lại.");
            return;
        }
        if (chats.add(chat)) saveChats();

        String cmd = text.startsWith("/") ? text.split("[\\s@]", 2)[0].toLowerCase(Locale.ROOT) : "";
        switch (cmd) {
            case "/start", "/help" -> {
                send(chat, """
                        Chào bạn! Mình là trợ lý Media Studio. Cứ nhắn việc cần làm, ví dụ:
                        • Làm video lịch sử về trận Bạch Đằng năm 938
                        • Còn bao nhiêu bài trong hàng đợi đăng?
                        • Gửi mình xem video vừa xuất

                        Lệnh: /new cuộc trò chuyện mới · /stop dừng việc đang làm""");
                return;
            }
            case "/new" -> {
                assistant.reset();
                send(chat, "🆕 Đã bắt đầu cuộc trò chuyện mới.");
                return;
            }
            case "/stop" -> {
                assistant.cancel();
                send(chat, "⏹ Đã yêu cầu dừng.");
                return;
            }
            default -> { }
        }
        if (msg.has("photo") || msg.has("document") || msg.has("voice")) {
            if (text.isEmpty()) {
                send(chat, "Hiện bot chỉ đọc được tin nhắn chữ. Gửi ảnh nguồn qua giao diện web nhé.");
                return;
            }
        }
        if (text.isEmpty()) return;
        if (assistant.busy()) {
            send(chat, "⏳ Trợ lý đang làm việc khác. Đợi xong hoặc gửi /stop để dừng.");
            return;
        }
        reply(chat, text);
    }

    private void reply(long chat, String text) throws IOException {
        StringBuilder sent = new StringBuilder();
        String[] lastTool = {""};
        boolean[] done = {false};
        AssistantService.Listener l = new AssistantService.Listener() {
            @Override
            public void text(String t) {
                sent.append(t).append("\n\n");
                send(chat, t);
            }

            @Override
            public void tool(String label) {
                if (label.equals(lastTool[0]) || label.startsWith("Chờ") || label.startsWith("Đọc") || label.startsWith("Xem")) return;
                lastTool[0] = label;
                sendQuiet(chat, "⚙ " + label);
            }

            @Override
            public void done(AssistantService.Msg r) {
                done[0] = true;
                String all = r.text == null ? "" : r.text;
                if ("(đã dừng)".equals(all)) send(chat, "⏹ Đã dừng.");
                else if (r.error) {
                    // The error part was added after the streamed text: send what Telegram has not seen.
                    String seen = sent.toString().trim();
                    String rest = seen.isEmpty() || !all.startsWith(seen) ? all : all.substring(seen.length()).trim();
                    if (!rest.isBlank()) send(chat, rest);
                }
            }
        };
        try {
            assistant.send(text, null, false, "telegram", l);
        } catch (IllegalStateException e) {
            send(chat, "⏳ " + e.getMessage());
            return;
        }
        // "typing…" until the reply is finished (Telegram shows it for ~5 s per call).
        Thread.ofVirtual().start(() -> {
            while (!done[0]) {
                action(chat, "typing");
                sleep(4500);
            }
        });
    }

    // ------------------------------------------------------------------ sending

    /** Sends project files to every chat that talked to the bot. Photos (≤10) go as one album. */
    public Map<String, Object> sendFiles(String projectId, String folder, List<String> names, String caption) throws IOException {
        String token = token();
        if (token.isEmpty()) throw new IllegalStateException("Chưa cấu hình bot Telegram");
        if (chats.isEmpty()) throw new IllegalStateException("Chưa có ai nhắn cho bot Telegram");
        if (!"assets".equals(folder) && !"output".equals(folder)) throw new IllegalArgumentException("folder: assets hoặc output");
        if (names == null || names.isEmpty()) throw new IllegalArgumentException("Thiếu tên file");
        List<Path> files = new ArrayList<>();
        for (String n : names) {
            if (n.contains("/") || n.contains("\\") || n.contains("..")) throw new IllegalArgumentException("Tên file không hợp lệ: " + n);
            Path f = projects.file(projectId, folder, n);
            if (!Files.exists(f)) throw new IllegalArgumentException("Không có file " + n);
            if (Files.size(f) > MAX_UPLOAD) throw new IllegalArgumentException(n + " lớn hơn 50 MB (giới hạn của bot Telegram)");
            files.add(f);
        }
        boolean album = files.size() > 1 && files.stream().allMatch(f -> isPhoto(f) || isVideo(f));
        int sent = 0;
        Set<Long> ok = allowed();
        for (long chat : chats) {
            if (!ok.contains(chat)) continue; // private chat id = user id; removed users get nothing
            if (album) {
                for (int i = 0; i < files.size(); i += 10) {
                    List<Path> part = files.subList(i, Math.min(files.size(), i + 10));
                    Multipart mp = new Multipart().field("chat_id", String.valueOf(chat));
                    ArrayNode media = mapper.createArrayNode();
                    for (int j = 0; j < part.size(); j++) {
                        Path f = part.get(j);
                        String type = isVideo(f) ? "video" : Files.size(f) > MAX_PHOTO ? "document" : "photo";
                        ObjectNode m = media.addObject().put("type", type).put("media", "attach://f" + j);
                        if (i == 0 && j == 0 && caption != null && !caption.isBlank()) m.put("caption", clip(caption, 1000));
                        mp.file("f" + j, f);
                    }
                    mp.field("media", media.toString());
                    upload(token, "sendMediaGroup", mp);
                }
            } else {
                for (int i = 0; i < files.size(); i++) {
                    Path f = files.get(i);
                    String method, field;
                    if (isPhoto(f) && Files.size(f) <= MAX_PHOTO) { method = "sendPhoto"; field = "photo"; }
                    else if (isVideo(f)) { method = "sendVideo"; field = "video"; }
                    else { method = "sendDocument"; field = "document"; }
                    Multipart mp = new Multipart().field("chat_id", String.valueOf(chat)).file(field, f);
                    if ("sendVideo".equals(method)) mp.field("supports_streaming", "true");
                    if (i == 0 && caption != null && !caption.isBlank()) mp.field("caption", clip(caption, 1000));
                    upload(token, method, mp);
                }
            }
            sent++;
        }
        return Map.of("ok", true, "chats", sent, "files", files.size());
    }

    private static boolean isPhoto(Path f) {
        return f.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$");
    }

    private static boolean isVideo(Path f) {
        return f.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mov|m4v)$");
    }

    /** Text message, HTML (bold / code from Claude's markdown); falls back to plain text if Telegram rejects it. */
    void send(long chat, String text) {
        for (String part : chunks(text, 3800)) {
            try {
                ObjectNode b = mapper.createObjectNode().put("chat_id", chat).put("text", html(part)).put("parse_mode", "HTML")
                        .put("disable_web_page_preview", true);
                call(token(), "sendMessage", b);
            } catch (Exception e) {
                try {
                    call(token(), "sendMessage", mapper.createObjectNode().put("chat_id", chat).put("text", part));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void sendQuiet(long chat, String text) {
        try {
            call(token(), "sendMessage", mapper.createObjectNode().put("chat_id", chat).put("text", text)
                    .put("disable_notification", true));
        } catch (Exception ignored) {
        }
    }

    private void action(long chat, String action) {
        try {
            call(token(), "sendChatAction", mapper.createObjectNode().put("chat_id", chat).put("action", action));
        } catch (Exception ignored) {
        }
    }

    static String html(String s) {
        String e = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        e = e.replaceAll("```(?:[a-z]*\\n)?([\\s\\S]*?)```", "<pre>$1</pre>");
        e = e.replaceAll("`([^`\\n]+)`", "<code>$1</code>");
        e = e.replaceAll("\\*\\*([^*\\n]+)\\*\\*", "<b>$1</b>");
        e = e.replaceAll("(?m)^#{1,4} (.+)$", "<b>$1</b>");
        return e;
    }

    static List<String> chunks(String s, int max) {
        List<String> out = new ArrayList<>();
        String rest = s == null ? "" : s.trim();
        while (rest.length() > max) {
            int cut = rest.lastIndexOf('\n', max);
            if (cut < max / 2) cut = max;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------ Bot API

    private JsonNode call(String token, String method, ObjectNode body) throws IOException {
        return call(token, method, body, Duration.ofSeconds(30));
    }

    private JsonNode call(String token, String method, ObjectNode body, Duration timeout) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + token + "/" + method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return result(req, method);
    }

    private JsonNode upload(String token, String method, Multipart mp) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + token + "/" + method))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=" + mp.boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mp.bytes()))
                .build();
        return result(req, method);
    }

    private JsonNode result(HttpRequest req, String method) throws IOException {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode j = mapper.readTree(res.body());
            if (!j.path("ok").asBoolean()) {
                throw new IOException("Telegram " + method + " " + res.statusCode() + ": " + j.path("description").asText());
            }
            return j.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static final class Multipart {
        final String boundary = "ms" + UUID.randomUUID().toString().replace("-", "");
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Multipart field(String name, String value) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
            return this;
        }

        Multipart file(String name, Path f) {
            try {
                String fn = f.getFileName().toString().replace("\"", "");
                write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fn
                        + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
                out.write(Files.readAllBytes(f));
                write("\r\n");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return this;
        }

        byte[] bytes() {
            write("--" + boundary + "--\r\n");
            return out.toByteArray();
        }

        private void write(String s) {
            out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ state

    private Path stateFile() throws IOException {
        Path d = settings.get().workspace().resolve("assistant");
        Files.createDirectories(d);
        return d.resolve("telegram.json");
    }

    private void loadChats() {
        try {
            Path f = stateFile();
            if (Files.exists(f)) for (JsonNode n : mapper.readTree(f.toFile()).path("chats")) chats.add(n.asLong());
        } catch (IOException ignored) {
        }
    }

    private void saveChats() {
        try {
            ObjectNode o = mapper.createObjectNode();
            ArrayNode a = o.putArray("chats");
            chats.forEach(a::add);
            Files.writeString(stateFile(), o.toString());
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
