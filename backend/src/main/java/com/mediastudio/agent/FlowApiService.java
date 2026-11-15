package com.mediastudio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobContext;
import com.mediastudio.job.JobService;
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
        ObjectNode body = json.createObjectNode();
        var arr = body.putArray("prompts");
        prompts.forEach(arr::add);
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
            List<Integer> nums = new ArrayList<>();
            for (String sid : part) {
                Scene sc = p.scene(sid);
                prompts.add(AgentPrompts.textOnlyImagePrompt(p, sc));
                nums.add(p.scenes.indexOf(sc) + 1);
            }
            ctx.log("… Cảnh " + nums + ": đang tạo (" + (i + part.size()) + "/" + ids.size() + ")");
            long a = System.currentTimeMillis();
            List<Result> res;
            try {
                res = generate(prompts, p.aspect);
            } catch (IOException e) {
                ctx.log("  ✖ Cảnh " + nums + ": " + e.getMessage());
                failed.addAll(nums);
                if (e.getMessage().startsWith("không kết nối")) break; // service went away: stop
                continue;
            }
            for (int k = 0; k < part.size(); k++) {
                Result r = res.get(k);
                int n = nums.get(k);
                String sid = part.get(k);
                if (r.error() != null || r.image() == null || r.image().length < 1000) {
                    failed.add(n);
                    ctx.log("  ✖ Cảnh " + n + ": " + (r.error() != null ? r.error() : "ảnh trống"));
                    continue;
                }
                String file = "scene-" + sid + "-flow-" + System.currentTimeMillis() + ext(r.image());
                Files.write(dir.resolve("assets").resolve(file), r.image());
                projects.mutate(projectId, pr -> {
                    Scene x = pr.scene(sid);
                    if (x != null) { x.mediaFile = file; x.mediaSource = "flow"; }
                });
                ok++;
                ctx.log("  ✔ Cảnh " + n + " xong (" + r.image().length / 1024 + " KB)");
            }
            ctx.log("  · lượt này mất " + (System.currentTimeMillis() - a) / 1000 + "s");
        }
        ctx.log("✔ Xong " + ok + "/" + ids.size() + " cảnh trong " + (System.currentTimeMillis() - t0) / 1000 + "s"
                + (failed.isEmpty() ? "" : "; lỗi ở cảnh " + failed));
        if (ok == 0) throw new IOException("Không tạo được cảnh nào qua service tạo ảnh");
    }

    static String ext(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return ".jpg";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E') return ".webp";
        return ".png";
    }
}
