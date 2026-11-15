package com.mediastudio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobContext;
import com.mediastudio.job.JobService;
import com.mediastudio.project.CharacterRef;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.project.Scene;
import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scene pictures straight from the Gemini API (Nano Banana image models): no browser, no Claude run, several scenes
 * at once. The same prompt as Flow/Canva (style bible + scene + quality rules) plus reference images: the style
 * anchor (⭐ scene or an existing scene picture) and the characters the scene names.
 */
@Service
public class GeminiImageService {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta";
    /** Revision the Interactions API examples use. */
    private static final String API_REVISION = "2026-05-20";
    private static final int MAX_TRIES = 3;

    private final ProjectService projects;
    private final JobService jobs;
    private final SettingsService settings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public GeminiImageService(ProjectService projects, JobService jobs, SettingsService settings) {
        this.projects = projects;
        this.jobs = jobs;
        this.settings = settings;
    }

    private AppSettings cfg() {
        AppSettings s = settings.get();
        if (s.geminiApiKey == null || s.geminiApiKey.isBlank()) {
            throw new IllegalStateException("Chưa có Gemini API key — nhập ở Cài đặt (lấy tại aistudio.google.com → Get API key)");
        }
        return s;
    }

    /** One scene (always regenerates it). */
    public Job scene(String projectId, String sceneId) {
        cfg();
        Project p = projects.get(projectId);
        Scene s = p.scene(sceneId);
        int n = p.scenes.indexOf(s) + 1;
        return jobs.start("gemini", "Gemini: cảnh " + n, projectId, sceneId, ctx -> run(projectId, List.of(sceneId), ctx));
    }

    /** Every scene that has an image prompt (only those without a picture when onlyMissing). */
    public Job all(String projectId, boolean onlyMissing) {
        cfg();
        Project p = projects.get(projectId);
        List<String> ids = new ArrayList<>();
        for (Scene s : p.scenes) {
            if (s.imagePrompt != null && !s.imagePrompt.isBlank() && !(onlyMissing && s.mediaFile != null)) ids.add(s.id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Không có cảnh nào cần tạo hình");
        return jobs.start("gemini", "Gemini: " + ids.size() + " cảnh", projectId, null, ctx -> run(projectId, ids, ctx));
    }

    private void run(String projectId, List<String> ids, JobContext ctx) throws Exception {
        AppSettings s = cfg();
        Project p = projects.get(projectId);
        Path dir = projects.dir(projectId);
        int parallel = Math.max(1, Math.min(s.geminiParallel, 6));
        ctx.log("Model " + s.geminiImageModel + " · " + s.geminiImageSize + " · " + p.aspect + " · " + parallel + " cảnh cùng lúc");

        // Style anchor: the ⭐ scene, else the first existing scene picture that is not being regenerated now.
        Set<String> batch = new HashSet<>(ids);
        Path anchor = null;
        if (p.styleRefSceneId != null && !batch.contains(p.styleRefSceneId)) {
            anchor = p.scenes.stream().filter(x -> x.id.equals(p.styleRefSceneId)).findFirst()
                    .map(x -> AgentPrompts.stillImage(dir, x)).orElse(null);
        }
        if (anchor == null) {
            for (Scene x : p.scenes) {
                if (batch.contains(x.id)) continue;
                anchor = AgentPrompts.stillImage(dir, x);
                if (anchor != null) break;
            }
        }
        ctx.log(anchor != null ? "Ảnh mẫu phong cách: " + anchor.getFileName() : "Chưa có ảnh mẫu phong cách (dùng bộ quy tắc hình ảnh)");
        final Path styleAnchor = anchor;

        long t0 = System.currentTimeMillis();
        AtomicInteger ok = new AtomicInteger();
        List<Integer> failed = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String sid : ids) {
                futures.add(pool.submit(() -> {
                    if (ctx.cancelled()) return;
                    Project cur = projects.get(projectId);
                    Scene sc = cur.scene(sid);
                    int n = cur.scenes.indexOf(sc) + 1;
                    long a = System.currentTimeMillis();
                    try {
                        String file = generate(cur, sc, dir, styleAnchor, s, ctx, n);
                        projects.mutate(projectId, pr -> {
                            Scene x = pr.scene(sid);
                            x.mediaFile = file;
                            x.mediaSource = "gemini";
                        });
                        ok.incrementAndGet();
                        ctx.log("  ✔ Cảnh " + n + " xong (" + (System.currentTimeMillis() - a) / 1000 + "s)");
                    } catch (Exception e) {
                        failed.add(n);
                        ctx.log("  ✖ Cảnh " + n + ": " + e.getMessage());
                    }
                }));
            }
            for (Future<?> f : futures) {
                while (true) {
                    try {
                        f.get(1, TimeUnit.SECONDS);
                        break;
                    } catch (TimeoutException te) {
                        if (ctx.cancelled()) { pool.shutdownNow(); return; }
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Collections.sort(failed);
        ctx.log("✔ Xong " + ok.get() + "/" + ids.size() + " cảnh trong " + (System.currentTimeMillis() - t0) / 1000 + "s"
                + (failed.isEmpty() ? "" : "; lỗi ở cảnh " + failed));
        if (ok.get() == 0) throw new IOException("Không tạo được cảnh nào bằng Gemini");
    }

    /** Calls the API (retrying rate limits / server errors) and saves the picture; returns the file name in assets/. */
    private String generate(Project p, Scene s, Path dir, Path anchor, AppSettings cfg, JobContext ctx, int n) throws Exception {
        List<Path> refs = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        if (anchor != null) {
            refs.add(anchor);
            roles.add("STYLE ANCHOR (another scene of this video): copy its art style exactly — medium, colour palette, line work,"
                    + " lighting — but NOT its composition or content");
        }
        for (CharacterRef c : AgentPrompts.sceneCharacters(p, s)) {
            Path f = dir.resolve("assets").resolve(c.file);
            if (!Files.exists(f)) continue;
            refs.add(f);
            roles.add("CHARACTER " + c.name + ": keep this character's face, hair, body and outfit exactly as shown");
        }
        StringBuilder text = new StringBuilder(AgentPrompts.imagePrompt(p, s));
        if (!refs.isEmpty()) {
            text.append("\n\nREFERENCE IMAGES (attached in this order):\n");
            for (int i = 0; i < roles.size(); i++) text.append(i + 1).append(". ").append(roles.get(i)).append('\n');
            text.append("Compose a completely new picture for THIS SCENE as described above.");
        }

        byte[] img = null;
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_TRIES && img == null; attempt++) {
            if (ctx.cancelled()) throw new IOException("đã huỷ");
            try {
                img = call(cfg, text.toString(), refs, p.aspect);
            } catch (RetryableException e) {
                last = e;
                long wait = 8000L * attempt;
                ctx.log("  … cảnh " + n + ": " + e.getMessage() + " — thử lại sau " + wait / 1000 + "s");
                Thread.sleep(wait);
            }
        }
        if (img == null) throw last != null ? last : new IOException("không nhận được ảnh");

        String size = "";
        try (var in = new java.io.ByteArrayInputStream(img)) {
            var bi = javax.imageio.ImageIO.read(in);
            if (bi != null) size = bi.getWidth() + "x" + bi.getHeight();
        } catch (Exception ignored) {
        }
        if (lastNote.get() != null) ctx.log("  · cảnh " + n + ": " + lastNote.get());
        ctx.log("  · cảnh " + n + ": " + lastApi.get() + " " + size + ", " + img.length / 1024 + " KB");
        lastNote.remove();
        String ext = img.length > 3 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8 ? ".jpg" : ".png";
        String name = "scene-" + s.id + "-gemini-" + System.currentTimeMillis() + ext;
        Files.write(dir.resolve("assets").resolve(name), img);
        return name;
    }

    static class RetryableException extends IOException {
        RetryableException(String m) { super(m); }
    }

    /** Per-thread notes for the job log: which endpoint answered, and why the first one was skipped. */
    private final ThreadLocal<String> lastApi = new ThreadLocal<>();
    private final ThreadLocal<String> lastNote = new ThreadLocal<>();

    private byte[] call(AppSettings cfg, String prompt, List<Path> refs, String aspect) throws IOException, InterruptedException {
        // Interactions API (current docs); older keys/endpoints fall back to generateContent.
        ObjectNode body = json.createObjectNode();
        body.put("model", cfg.geminiImageModel);
        ArrayNode input = body.putArray("input");
        input.addObject().put("type", "text").put("text", prompt);
        for (Path r : refs) {
            input.addObject().put("type", "image").put("mime_type", mime(r))
                    .put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(r)));
        }
        ObjectNode rf = body.putObject("response_format");
        rf.put("type", "image").put("aspect_ratio", aspect).put("image_size", cfg.geminiImageSize).put("mime_type", "image/jpeg");

        HttpResponse<String> res = post(BASE + "/interactions", cfg, body, true);
        String api = "interactions";
        if (res.statusCode() == 404 || (res.statusCode() == 400 && res.body().matches("(?s).*(Unknown name|Invalid JSON payload|not supported|unrecognized).*"))) {
            lastNote.set("interactions HTTP " + res.statusCode() + ": " + errorText(res.body()));
            res = post(BASE + "/models/" + cfg.geminiImageModel + ":generateContent", cfg, legacyBody(prompt, refs, aspect, cfg), false);
            api = "generateContent";
        }
        int code = res.statusCode();
        if (code == 429 || code >= 500) throw new RetryableException("HTTP " + code + " " + errorText(res.body()));
        if (code / 100 != 2) throw new IOException("HTTP " + code + " " + errorText(res.body()));

        JsonNode root = json.readTree(res.body());
        String b64 = findImage(root);
        if (b64 == null) {
            String said = findText(root);
            throw new IOException("Gemini không trả về ảnh" + (said == null ? "" : ": " + abbrev(said, 300)));
        }
        byte[] out = Base64.getDecoder().decode(b64);
        lastApi.set(api);
        return out;
    }

    private ObjectNode legacyBody(String prompt, List<Path> refs, String aspect, AppSettings cfg) throws IOException {
        ObjectNode body = json.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
        parts.addObject().put("text", prompt);
        for (Path r : refs) {
            parts.addObject().putObject("inline_data").put("mime_type", mime(r))
                    .put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(r)));
        }
        ObjectNode gc = body.putObject("generationConfig");
        gc.putArray("responseModalities").add("IMAGE");
        gc.putObject("imageConfig").put("aspectRatio", aspect).put("imageSize", cfg.geminiImageSize);
        return body;
    }

    private HttpResponse<String> post(String url, AppSettings cfg, ObjectNode body, boolean revision)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("x-goog-api-key", cfg.geminiApiKey.trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (revision) b.header("Api-Revision", API_REVISION);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new RetryableException("quá thời gian chờ");
        }
    }

    /**
     * The final image in the response (base64): output_image.data, else the LAST image block in steps[].content[] or
     * candidates[].content.parts[]. Gemini 3 image models also return low-resolution draft ("thought") images first;
     * those and the echoed request input are skipped.
     */
    static String findImage(JsonNode root) {
        if (root == null) return null;
        JsonNode oi = root.get("output_image");
        if (oi != null && oi.path("data").isTextual()) return oi.get("data").asText();
        List<String> found = new ArrayList<>();
        collect(root, found);
        return found.isEmpty() ? null : found.get(found.size() - 1);
    }

    private static void collect(JsonNode n, List<String> out) {
        if (n == null) return;
        if (n.isArray()) {
            for (JsonNode x : n) collect(x, out);
            return;
        }
        if (!n.isObject()) return;
        if (n.path("thought").asBoolean(false) || "thought".equals(n.path("type").asText())) return;
        for (String k : List.of("inlineData", "inline_data")) {
            JsonNode d = n.get(k);
            if (d != null && d.path("data").isTextual()) {
                String mt = d.has("mimeType") ? d.path("mimeType").asText() : d.path("mime_type").asText("image");
                if (mt.startsWith("image")) { out.add(d.get("data").asText()); return; }
            }
        }
        if ("image".equals(n.path("type").asText()) && n.path("data").isTextual() && n.get("data").asText().length() > 100) {
            out.add(n.get("data").asText());
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equals("input") || e.getKey().equals("contents")) continue;
            collect(e.getValue(), out);
        }
    }

    private static String findText(JsonNode n) {
        if (n == null) return null;
        if (n.isObject()) {
            if (n.path("text").isTextual()) return n.get("text").asText();
            if (n.path("finishReason").isTextual()) return "finishReason " + n.get("finishReason").asText();
            if (n.path("blockReason").isTextual()) return "blockReason " + n.get("blockReason").asText();
        }
        for (JsonNode x : n) {
            String r = findText(x);
            if (r != null) return r;
        }
        return null;
    }

    private String errorText(String body) {
        try {
            JsonNode e = json.readTree(body).path("error");
            if (e.path("message").isTextual()) return e.get("message").asText();
        } catch (Exception ignored) {
        }
        return abbrev(body, 300);
    }

    private static String mime(Path f) {
        String n = f.getFileName().toString().toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
