package com.mediastudio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Scene pictures from the user's local image service (it drives Google Flow itself):
 * {@code POST {base}/api/v1/generate {"prompts": [...]}} → {@code {"images": [{prompt, image_url, base64, error}], ...}}.
 * Prompts go in small batches (Flow needs 15–40 s per picture); each picture's own error is reported per scene.
 * The prompts carry the shared style + character block, since this API takes no reference images.
 */
@Service
public class FlowApiService {
    private final ProjectService projects;
    private final JobService jobs;
    private final SettingsService settings;
    private final ObjectMapper json = new ObjectMapper();
    // HTTP/1.1 only: the default client asks to upgrade plain http to HTTP/2 (h2c), which uvicorn/FastAPI rejects on
    // a POST with a body ("400 Invalid HTTP request received").
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public FlowApiService(ProjectService projects, JobService jobs, SettingsService settings) {
        this.projects = projects;
        this.jobs = jobs;
        this.settings = settings;
    }

    // ---------- client for the image service ----------

    /** One picture from the service: the bytes, or the reason it failed. */
    public record Result(String prompt, byte[] image, String error) { }

    private String base() {
        String u = settings.get().flowApiUrl;
        return (u == null || u.isBlank() ? "http://localhost:8080" : u.trim()).replaceAll("/+$", "");
    }

    /**
     * HTTP timeout for one generate call with n prompts. The service works synchronously (prompts one after another,
     * ~30 s each) and answers only when every picture is fully rendered, so the client just waits:
     * n × 60 s as the service recommends plus a 120 s margin, never less than the minimum set in Settings.
     */
    Duration timeout(int n) {
        long min = Math.max(120, settings.get().flowApiTimeoutSec);
        // + 120 s margin: one picture Flow is slow on (the service waits up to 120 s for it) must not throw away the others
        return Duration.ofSeconds(Math.max(min, 60L * Math.max(1, n) + 120));
    }

    /** GET /api/v1/health. Returns null when healthy, else a short reason. */
    public String health() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/health"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                return "không thấy service tạo ảnh ở " + base() + " (HTTP 404) — kiểm tra địa chỉ/cổng trong Cài đặt";
            }
            if (r.statusCode() / 100 != 2) return "health trả HTTP " + r.statusCode();
            JsonNode h;
            try { h = json.readTree(r.body()); } catch (Exception notJson) { return null; } // 2xx without JSON: fine
            if (h == null) return null;
            if (h.path("is_enabled").isBoolean() && !h.path("is_enabled").asBoolean()) return "service tạo ảnh đang tắt (is_enabled=false)";
            if (h.path("service_ready").isBoolean() && !h.path("service_ready").asBoolean()) return "service tạo ảnh chưa sẵn sàng (service_ready=false)";
            return null;
        } catch (ConnectException e) {
            return "không kết nối được " + base() + " (service tạo ảnh chưa chạy?)";
        } catch (Exception e) {
            return "health lỗi: " + e.getMessage();
        }
    }

    /** POST /api/v1/generate for these prompts; one Result per prompt, in the same order. */
    public List<Result> generate(List<String> prompts) throws IOException, InterruptedException {
        return generate(prompts, null);
    }

    /** Same, with the frame for every picture ("16:9", "4:3", "1:1", "3:4", "9:16"); null = the service's current setting. */
    public List<Result> generate(List<String> prompts, String aspect) throws IOException, InterruptedException {
        return generate(prompts, aspect, null);
    }

    /** @param app the service's project key: one Flow project per Media Studio project ("ms-<id>"); null = "default". */
    public List<Result> generate(List<String> prompts, String aspect, String app) throws IOException, InterruptedException {
        return generate(prompts, null, aspect, app);
    }

    /**
     * @param refs per prompt, absolute paths of reference images (character portraits) the service uploads to Flow
     *             with that prompt; null or all empty = plain prompts.
     */
    public List<Result> generate(List<String> prompts, List<List<String>> refs, String aspect, String app) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        if (refs != null && refs.stream().anyMatch(r -> r != null && !r.isEmpty())) {
            var items = body.putArray("items");
            for (int i = 0; i < prompts.size(); i++) {
                ObjectNode it = items.addObject();
                it.put("prompt", prompts.get(i));
                List<String> r = i < refs.size() ? refs.get(i) : null;
                if (r != null && !r.isEmpty()) r.forEach(it.putArray("reference_images")::add);
            }
        } else {
            var arr = body.putArray("prompts");
            prompts.forEach(arr::add);
        }
        if (app != null) body.put("app_name", app);
        if (aspect != null && List.of("16:9", "4:3", "1:1", "3:4", "9:16").contains(aspect)) body.put("aspect_ratio", aspect);
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/generate"))
                    .timeout(timeout(prompts.size())).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new IOException("không kết nối được " + base() + " (service tạo ảnh chưa chạy?)");
        } catch (HttpTimeoutException e) {
            throw new IOException("service không trả lời sau " + timeout(prompts.size()).toSeconds() + "s");
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("service trả HTTP " + r.statusCode() + ": " + r.body().substring(0, Math.min(300, r.body().length())));
        }
        JsonNode images = json.readTree(r.body()).path("images");
        List<Result> out = new ArrayList<>();
        List<JsonNode> left = new ArrayList<>();
        images.forEach(left::add);
        for (int i = 0; i < prompts.size(); i++) {
            String want = prompts.get(i);
            // match by prompt text, else the next unused result (the service keeps the order)
            JsonNode n = left.stream().filter(x -> want.equals(x.path("prompt").asText(null))).findFirst()
                    .orElse(left.isEmpty() ? null : left.get(0));
            if (n != null) left.remove(n);
            out.add(toResult(want, n));
        }
        return out;
    }

    private Result toResult(String prompt, JsonNode n) {
        if (n == null) return new Result(prompt, null, "service không trả ảnh cho prompt này");
        String err = n.path("error").isNull() || n.path("error").isMissingNode() ? null : n.path("error").asText();
        if (err != null && !err.isBlank()) return new Result(prompt, null, err);
        String b64 = n.path("base64").asText("");
        try {
            if (!b64.isBlank()) {
                int comma = b64.startsWith("data:") ? b64.indexOf(',') : -1;
                return new Result(prompt, Base64.getMimeDecoder().decode(b64.substring(comma + 1)), null);
            }
            String url = n.path("image_url").asText("");
            if (!url.isBlank()) {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() / 100 == 2) return new Result(prompt, r.body(), null);
                return new Result(prompt, null, "tải image_url lỗi HTTP " + r.statusCode());
            }
        } catch (Exception e) {
            return new Result(prompt, null, "không đọc được ảnh: " + e.getMessage());
        }
        return new Result(prompt, null, "kết quả không có base64 lẫn image_url");
    }

    // ---------- jobs ----------

    public Job scenes(String projectId, List<String> sceneIds, boolean onlyMissing) {
        Project p = projects.get(projectId);
        Set<String> wanted = sceneIds == null ? null : new HashSet<>(sceneIds);
        List<String> ids = new ArrayList<>();
        for (Scene s : p.scenes) {
            if (s.imagePrompt == null || s.imagePrompt.isBlank()) continue;
            if (wanted != null ? !wanted.contains(s.id) : (onlyMissing && s.mediaFile != null)) continue;
            ids.add(s.id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Không có cảnh nào cần tạo hình");
        String title = ids.size() == 1 ? "Flow API: cảnh " + (p.scenes.indexOf(p.scene(ids.get(0))) + 1) : "Flow API: " + ids.size() + " cảnh";
        return jobs.start("flow-api", title, projectId, ids.size() == 1 ? ids.get(0) : null, ctx -> run(projectId, ids, ctx));
    }

    private void run(String projectId, List<String> ids, JobContext ctx) throws Exception {
        AppSettings cfg = settings.get();
        int batch = Math.max(1, Math.min(10, cfg.flowApiBatch));
        ctx.log("Service tạo ảnh: " + base() + " · " + batch + " ảnh mỗi lần gọi (tạo tuần tự, ~30s/ảnh) · chờ tối đa " + timeout(batch).toSeconds() + "s mỗi lần");
        String bad = health();
        if (bad != null) {
            // wrong address, service switched off or not ready: stop now instead of failing every scene
            if (bad.startsWith("không")) throw new IOException(bad);
            if (bad.startsWith("service tạo ảnh")) throw new IOException(bad + " — bật service rồi thử lại");
            ctx.log("⚠ " + bad + " — vẫn thử tạo ảnh");
        }
        Path dir = projects.dir(projectId);
        long t0 = System.currentTimeMillis();
        int ok = 0;
        List<Integer> failed = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += batch) {
            if (ctx.cancelled()) { ctx.log("Đã huỷ"); return; }
            List<String> part = ids.subList(i, Math.min(ids.size(), i + batch));
            Project p = projects.get(projectId);
            List<String> prompts = new ArrayList<>();
            List<List<String>> refs = new ArrayList<>();
            List<Integer> nums = new ArrayList<>();
            for (String sid : part) {
                Scene sc = p.scene(sid);
                List<String> r = new ArrayList<>();
                Path photo = AgentPrompts.scenePhoto(dir, sc);
                if (photo != null) r.add(photo.toAbsolutePath().toString());
                for (CharacterRef c : AgentPrompts.sceneCharacters(p, sc)) {
                    Path f = dir.resolve("assets").resolve(c.file);
                    if (Files.exists(f)) r.add(f.toAbsolutePath().toString());
                }
                String prompt = AgentPrompts.textOnlyImagePrompt(p, sc);
                if (photo != null) prompt += "\nThe FIRST attached image is the PHOTO REFERENCE. " + AgentPrompts.PHOTO_RULE;
                if (r.size() > (photo != null ? 1 : 0)) prompt += "\nThe " + (photo != null ? "other " : "")
                        + "attached reference image(s) show how these characters look: keep the same"
                        + " face, hair, costume, armour and colours, but draw a completely new picture of THIS scene"
                        + " (new pose, action, setting and framing; do not copy the character images' background).";
                prompts.add(prompt);
                refs.add(r);
                nums.add(p.scenes.indexOf(sc) + 1);
            }
            int withRefs = (int) refs.stream().filter(r -> !r.isEmpty()).count();
            if (withRefs > 0) ctx.log("  · " + withRefs + " cảnh kèm ảnh chân dung nhân vật làm tham chiếu");
            ctx.log("… Cảnh " + nums + ": đang tạo (" + (i + part.size()) + "/" + ids.size() + ")");
            long a = System.currentTimeMillis();
            List<Result> res;
            try {
                res = generate(prompts, refs, p.aspect, appName(projectId));
            } catch (IOException e) {
                ctx.log("  ✖ Cảnh " + nums + ": " + e.getMessage());
                failed.addAll(nums);
                if (e.getMessage().startsWith("không kết nối")) break; // service went away: stop
                continue;
            }
            boolean refused = false;
            for (int k = 0; k < part.size(); k++) {
                Result r = res.get(k);
                int n = nums.get(k);
                String sid = part.get(k);
                if (r.error() != null && r.error().contains("FLOW_REFUSED")) {
                    // Google refused ("unusual activity"): keep the pictures other tabs already finished, then stop.
                    ctx.log("  ✖ Cảnh " + n + ": Google Flow từ chối (hoạt động bất thường)");
                    failed.add(n);
                    refused = true;
                    continue;
                }
                if (r.error() != null || r.image() == null || r.image().length < 1000) {
                    failed.add(n);
                    ctx.log("  ✖ Cảnh " + n + ": " + (r.error() != null ? r.error() : "ảnh trống"));
                    continue;
                }
                String file = "scene-" + sid + "-flow-" + System.currentTimeMillis() + ext(r.image());
                Files.write(dir.resolve("assets").resolve(file), GeminiWatermark.clean(r.image())); // drop the ✦ mark
                projects.mutate(projectId, pr -> {
                    Scene x = pr.scene(sid);
                    if (x != null) { x.mediaFile = file; x.mediaSource = "flow"; }
                });
                ok++;
                ctx.log("  ✔ Cảnh " + n + " xong (" + r.image().length / 1024 + " KB)");
            }
            ctx.log("  · lượt này mất " + (System.currentTimeMillis() - a) / 1000 + "s");
            if (refused) {
                ctx.log("  · đã lưu " + ok + " cảnh xong trước khi bị từ chối");
                throw new IOException(FLOW_REFUSED_MSG);
            }
        }
        ctx.log("✔ Xong " + ok + "/" + ids.size() + " cảnh trong " + (System.currentTimeMillis() - t0) / 1000 + "s"
                + (failed.isEmpty() ? "" : "; lỗi ở cảnh " + failed));
        if (ok == 0) throw new IOException("Không tạo được cảnh nào qua service tạo ảnh");
        cleanUpIfComplete(projectId, ctx);
    }

    /**
     * Reference portraits of characters with Flow (one picture each, from the style bible + the character's look).
     * ids null = every character that has no picture yet; otherwise exactly these (an existing picture is replaced).
     */
    public Job portraits(String projectId, List<String> ids) {
        Project p = projects.get(projectId);
        List<String> todo = new ArrayList<>();
        for (CharacterRef c : p.characters) {
            if (c.name == null || c.name.isBlank()) continue;
            if (ids != null ? ids.contains(c.id) : c.file == null) todo.add(c.id);
        }
        if (todo.isEmpty()) throw new IllegalArgumentException("Không có nhân vật nào cần vẽ chân dung");
        return jobs.start("flow-api", "Chân dung nhân vật (" + todo.size() + ")", projectId, null, ctx -> {
            String bad = health();
            if (bad != null && (bad.startsWith("không") || bad.startsWith("service tạo ảnh"))) throw new IOException(bad);
            Path assets = projects.folder(projectId, "assets");
            Project pr0 = projects.get(projectId);
            List<String> prompts = new ArrayList<>();
            for (String cid : todo) prompts.add(AgentPrompts.portraitPrompt(pr0, pr0.character(cid)));
            ctx.log("… Đang vẽ chân dung: " + todo.stream().map(cid -> pr0.character(cid).name).toList());
            List<Result> res = generate(prompts, null, "3:4", appName(projectId));
            int ok = 0;
            boolean refused = false;
            for (int k = 0; k < todo.size(); k++) {
                Result r = res.get(k);
                String cid = todo.get(k);
                String name = pr0.character(cid).name;
                if (r.error() != null && r.error().contains("FLOW_REFUSED")) { refused = true; ctx.log("  ✖ " + name + ": Google Flow từ chối"); continue; }
                if (r.error() != null || r.image() == null || r.image().length < 1000) {
                    ctx.log("  ✖ " + name + ": " + (r.error() != null ? r.error() : "ảnh trống"));
                    continue;
                }
                String file = "ref-" + cid + "-" + System.currentTimeMillis() + ext(r.image());
                Files.write(assets.resolve(file), GeminiWatermark.clean(r.image()));
                String[] old = new String[1];
                projects.mutate(projectId, pr -> {
                    CharacterRef c = pr.character(cid);
                    old[0] = c.file;
                    c.file = file;
                    c.canvaMediaId = null;
                });
                if (old[0] != null && !old[0].equals(file)) Files.deleteIfExists(assets.resolve(old[0]));
                ok++;
                ctx.log("  ✔ " + name);
            }
            if (refused) throw new IOException(FLOW_REFUSED_MSG);
            if (ok == 0) throw new IOException("Không vẽ được chân dung nào");
            ctx.log("✔ Xong " + ok + "/" + todo.size() + " chân dung. Các cảnh có nhân vật này sẽ dùng ảnh làm tham chiếu.");
        });
    }

    /** The service's project key for a Media Studio project. */
    static String appName(String projectId) {
        return "ms-" + projectId;
    }

    /**
     * Every scene has its picture on disk → delete this project's Flow project (Settings → flowApiDeleteWhenDone),
     * so Flow does not pile up hundreds of pictures. Failing to delete never fails the job.
     */
    public void cleanUpIfComplete(String projectId, JobContext ctx) {
        if (!settings.get().flowApiDeleteWhenDone) return;
        Project p = projects.get(projectId);
        boolean complete = p.scenes.stream()
                .filter(s -> s.imagePrompt != null && !s.imagePrompt.isBlank())
                .allMatch(s -> s.mediaFile != null && Files.exists(projects.dir(projectId).resolve("assets").resolve(s.mediaFile)));
        if (!complete) return;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/projects/"
                            + appName(projectId) + "?remote=true")).timeout(Duration.ofMinutes(3)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode n = r.statusCode() / 100 == 2 ? json.readTree(r.body()) : null;
            if (n != null && n.path("remote_deleted").asBoolean(false)) ctx.log("🗑 Đã xoá project trên Flow (đã lấy đủ hình về máy)");
            else if (n != null && !n.path("had_project").asBoolean(true)) { /* nothing on Flow for this project */ }
            else ctx.log("⚠ Chưa xoá được project trên Flow: " + (n == null ? "HTTP " + r.statusCode() + " " + r.body() : n.path("message").asText()));
        } catch (Exception e) {
            ctx.log("⚠ Chưa xoá được project trên Flow: " + e.getMessage());
        }
    }

    /** Also checked by the publish queue, which pauses itself on it. */
    public static final String FLOW_REFUSED_MSG = "Google Flow báo \"hoạt động bất thường\" và từ chối tạo ảnh. Đã dừng: "
            + "hãy đợi vài giờ (hoặc tới hôm sau) rồi bấm Thử lại, và tạo ít ảnh hơn mỗi lần.";

    static String ext(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return ".jpg";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E') return ".webp";
        return ".png";
    }
}
