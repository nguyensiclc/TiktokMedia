package com.mediastudio.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediastudio.job.JobContext;
import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * VieNeu-TTS 3.x through its OpenAI-compatible server (apps/openai_speech.py):
 * POST /v1/audio/speech, GET /v1/voices, GET /health.
 * Starts the server from the configured venv when it isn't running.
 */
@Service
public class VieneuClient {
    private static final int SAMPLE_RATE = 48000;

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private volatile Process server;

    public VieneuClient(SettingsService settings) {
        this.settings = settings;
    }

    private String base() {
        String u = settings.get().vieneuUrl;
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** true if /health answers. The first /health call loads the model, so allow a long wait. */
    public boolean healthy(Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/health")).timeout(timeout).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Is the TCP port answering at all (server process up, model maybe still loading)? */
    public boolean reachable() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/docs")).timeout(Duration.ofSeconds(2)).GET().build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void ensureRunning(Consumer<String> log) throws IOException, InterruptedException {
        if (reachable()) {
            if (!healthy(Duration.ofMinutes(10))) throw new IOException("VieNeu server đang chạy nhưng /health lỗi");
            return;
        }
        AppSettings s = settings.get();
        if (!s.vieneuAutoStart) throw new IOException("VieNeu server chưa chạy tại " + base());
        if (s.vieneuPython == null || s.vieneuPython.isBlank() || !Files.exists(Path.of(s.vieneuPython))) {
            throw new IOException("Chưa cấu hình python.exe của venv VieNeu (Cài đặt → Giọng đọc)");
        }
        URI u = URI.create(base());
        int port = u.getPort() > 0 ? u.getPort() : 8100;
        Path logFile = s.workspace().resolve("vieneu-server.log");
        Files.createDirectories(logFile.getParent());

        ProcessBuilder pb = new ProcessBuilder(s.vieneuPython, "-m", "apps.openai_speech")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Map<String, String> env = pb.environment();
        env.put("HOST", "127.0.0.1");
        env.put("PORT", String.valueOf(port));
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        if (s.vieneuDevice != null && !s.vieneuDevice.isBlank()) env.put("VIENEU_DEVICE", s.vieneuDevice);
        // Allow a few queued scene requests instead of failing fast with 429.
        env.putIfAbsent("VIENEU_QUEUE_TIMEOUT", "600");
        log.accept("▶ Khởi động VieNeu-TTS server (cổng " + port + "), log: " + logFile);
        server = pb.start();

        long deadline = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
        while (!reachable()) {
            if (!server.isAlive()) throw new IOException("VieNeu server thoát (mã " + server.exitValue() + "), xem " + logFile);
            if (System.currentTimeMillis() > deadline) throw new IOException("VieNeu server không lên kịp, xem " + logFile);
            Thread.sleep(1000);
        }
        log.accept("  đang nạp model (lần đầu có thể tải model từ HuggingFace)…");
        if (!healthy(Duration.ofMinutes(15))) throw new IOException("VieNeu không nạp được model, xem " + logFile);
        log.accept("  ✔ VieNeu sẵn sàng");
    }

    /** Preset + enrolled voices, normalised to [{id, name, language, gender, description}]. */
    public JsonNode voices() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/v1/voices"))
                .timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 300) throw new IOException("VieNeu /v1/voices trả về " + res.statusCode());
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode v : mapper.readTree(res.body()).path("data")) {
            ObjectNode o = out.addObject();
            o.put("id", v.path("id").asText());
            o.put("name", v.path("name").asText());
            o.put("language", "vi");
            o.put("gender", v.path("gender").asText(""));
            o.put("description", v.path("description").asText(""));
        }
        return out;
    }

    /** Synthesises text and returns a complete 48 kHz mono 16-bit WAV. */
    public byte[] speak(String text, String voice, JobContext ctx) throws IOException, InterruptedException {
        ensureRunning(ctx::log);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", text);
        if (voice != null && !voice.isBlank()) body.put("voice", voice);
        // pcm, not wav: the server's streamed WAV header has an unknown (0xFFFFFFFF) length.
        body.put("response_format", "pcm");
        body.put("sample_rate", SAMPLE_RATE);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/v1/audio/speech"))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() >= 300) {
            String msg = new String(res.body(), StandardCharsets.UTF_8);
            try {
                msg = mapper.readTree(msg).path("error").path("message").asText(msg);
            } catch (Exception ignored) {
            }
            throw new IOException("VieNeu trả về " + res.statusCode() + ": " + msg);
        }
        int rate = res.headers().firstValue("X-Sample-Rate").map(Integer::parseInt).orElse(SAMPLE_RATE);
        return wav(res.body(), rate);
    }

    private static byte[] wav(byte[] pcm, int rate) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.writeBytes(h.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    /** Stops the server this app started (not one the user started separately). */
    @PreDestroy
    public void stop() {
        Process p = server;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }
}
