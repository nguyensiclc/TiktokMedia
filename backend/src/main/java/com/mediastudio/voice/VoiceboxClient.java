package com.mediastudio.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediastudio.job.JobContext;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Talks to the local Voicebox (jamiepine/voicebox) REST API.
 * Default address http://127.0.0.1:17493; docs at /docs.
 */
@Service
public class VoiceboxClient {
    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();
    // HTTP/1.1: uvicorn rejects the h2c upgrade headers Java sends by default ("Invalid HTTP request").
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public VoiceboxClient(SettingsService settings) {
        this.settings = settings;
    }

    private String base() {
        String u = settings.get().voiceboxUrl;
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** GET /profiles → [{id, name, language, voice_type, ...}] */
    public JsonNode profiles() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/profiles"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 300) throw new IOException("Voicebox /profiles trả về " + res.statusCode());
        return mapper.readTree(res.body());
    }

    /**
     * POST /generate (queues generation, downloads the model on first use)
     * → follow GET /generate/{id}/status (SSE) until completed → GET /audio/{id}.
     */
    public byte[] speak(String text, String profileId, String language, JobContext ctx)
            throws IOException, InterruptedException {
        Consumer<String> log = ctx::log;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("profile_id", profileId);
        body.put("language", language == null || language.isBlank() ? "en" : language);
        String engine = engineFor(profileId);
        if (engine != null) body.put("engine", engine);
        String size = settings.get().voiceModelSize;
        if (size != null && !size.isBlank()) body.put("model_size", size);

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/generate"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 300) throw new IOException("Voicebox trả về " + res.statusCode() + ": " + res.body());
        JsonNode gen = mapper.readTree(res.body());
        String id = gen.path("id").asText();
        String status = gen.path("status").asText();
        if (engine != null) log.accept("  engine: " + engine);

        if (!"completed".equals(status)) status = waitFor(id, log, ctx);
        if (ctx.cancelled()) throw new IOException("Đã huỷ");
        if (!"completed".equals(status)) throw new IOException("Voicebox: tạo giọng thất bại (" + status + ")");

        HttpRequest audio = HttpRequest.newBuilder(URI.create(base() + "/audio/" + id))
                .timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<byte[]> a = http.send(audio, HttpResponse.BodyHandlers.ofByteArray());
        if (a.statusCode() >= 300) throw new IOException("Voicebox /audio trả về " + a.statusCode());
        return a.body();
    }

    /** Reads the SSE status stream; returns the final status (completed / failed / error message). */
    private String waitFor(String id, Consumer<String> log, JobContext ctx) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/generate/" + id + "/status"))
                .timeout(Duration.ofMinutes(30)).header("Accept", "text/event-stream").GET().build();
        HttpResponse<Stream<String>> res = http.send(req, HttpResponse.BodyHandlers.ofLines());
        if (res.statusCode() >= 300) throw new IOException("Voicebox status trả về " + res.statusCode());
        String last = "unknown";
        try (Stream<String> lines = res.body()) {
            ctx.onCancel(lines::close); // aborts the blocking SSE read
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("data:")) continue;
                JsonNode ev;
                try {
                    ev = mapper.readTree(line.substring(5).trim());
                } catch (Exception e) {
                    continue;
                }
                String st = ev.path("status").asText(last);
                if (!st.equals(last)) log.accept("  trạng thái: " + st);
                last = st;
                if ("completed".equals(st)) return st;
                if ("failed".equals(st) || "error".equals(st)) {
                    return st + (ev.hasNonNull("error") ? ": " + ev.path("error").asText() : "");
                }
            }
        }
        return last;
    }

    /** Preset profiles only accept their own engine (e.g. qwen_custom_voice); cloned ones use the default. */
    private String engineFor(String profileId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/profiles/" + profileId))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 300) return null;
            JsonNode p = mapper.readTree(res.body());
            for (String f : new String[]{"preset_engine", "default_engine"}) {
                String v = p.path(f).asText("");
                if (!v.isBlank() && !"null".equals(v)) return v;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
