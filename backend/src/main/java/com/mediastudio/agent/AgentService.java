package com.mediastudio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobContext;
import com.mediastudio.job.JobService;
import com.mediastudio.project.CharacterRef;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.project.Scene;
import com.mediastudio.render.FfmpegService;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Service
public class AgentService {
    private static final Set<String> MEDIA_EXT = Set.of(".png", ".jpg", ".jpeg", ".webp", ".mp4", ".mov", ".webm");

    private final ClaudeCli claude;
    private final McpConfigWriter mcp;
    private final ProjectService projects;
    private final JobService jobs;
    private final SettingsService settings;

    private final FfmpegService ffmpeg;
    private final RefPhotoService refPhotos;

    public AgentService(ClaudeCli claude, McpConfigWriter mcp, ProjectService projects,
                        JobService jobs, SettingsService settings, FfmpegService ffmpeg, RefPhotoService refPhotos) {
        this.ffmpeg = ffmpeg;
        this.refPhotos = refPhotos;
        this.claude = claude;
        this.mcp = mcp;
        this.projects = projects;
        this.jobs = jobs;
        this.settings = settings;
    }

    /** Topic → storyboard.json (narration + image prompt per scene) → replaces the project's scenes. */
    public Job storyboard(String projectId, String topic, int count, String language, String style, boolean append) {
        return storyboard(projectId, topic, count, language, style, append, null);
    }

    /** @param niche "story" / "facts" / "history" / "drama"; null = keep the project's niche. */
    public Job storyboard(String projectId, String topic, int count, String language, String style, boolean append,
                          String niche) {
        if (niche != null && Project.NICHES.contains(niche)) projects.mutate(projectId, pr -> pr.niche = niche);
        String n = projects.get(projectId).niche;
        int max = Project.maxScenes(n);
        int shots = Math.max(1, Math.min(count, max));
        // A drama topic is a whole pasted episode: name the job by its first line only.
        String first = topic.strip().lines().findFirst().orElse("").strip();
        String title = "drama".equals(n) ? "Chia shot: " + (first.length() > 60 ? first.substring(0, 60) + "…" : first)
                : "Kịch bản: " + topic;
        return jobs.start("storyboard", title, projectId, null,
                ctx -> runStoryboard(projectId, topic, topic, shots, language, style, append, ctx));
    }

    /**
     * Source images → Claude reads them (text, characters, style) → reference images are cropped →
     * optionally the storyboard is written right away.
     */
    public Job analyze(String projectId, String note, boolean thenStoryboard) {
        return analyze(projectId, note, thenStoryboard, "claude", null);
    }

    /** @param ai "claude" or "agy" for this run; @param model model of that tool (blank = its Settings model). */
    public Job analyze(String projectId, String note, boolean thenStoryboard, String ai, String model) {
        Project p0 = projects.get(projectId);
        boolean hasText = note != null && !note.isBlank();
        if (p0.sources.isEmpty() && !hasText) throw new IllegalArgumentException("Hãy nhập nội dung hoặc dán ít nhất một hình");
        String jobTitle = p0.sources.isEmpty() ? "Phân tích nội dung" : "Phân tích " + p0.sources.size() + " hình";
        return jobs.start("analyze", jobTitle, projectId, null, ctx -> {
            Project p = projects.get(projectId);
            Path dir = projects.dir(projectId);
            Path assets = projects.folder(projectId, "assets");
            Map<String, int[]> images = new LinkedHashMap<>();
            for (String f : p.sources) images.put(f, ffmpeg.probeSize(assets.resolve(f)));
            Path out = dir.resolve(AgentPrompts.ANALYSIS_FILE);
            Files.deleteIfExists(out);

            boolean useAgy = "agy".equalsIgnoreCase(ai);
            ctx.log("▶ " + (useAgy ? "agy" : "Claude") + " đọc "
                    + (images.isEmpty() ? "nội dung đã nhập…" : images.size() + " hình" + (hasText ? " và nội dung đã nhập…" : "…")));
            if (useAgy && !images.isEmpty()) ctx.log("  ⚠ agy mở được file ảnh nhưng không nhìn được nội dung; nếu kết quả sai hãy chọn Claude");
            // The user picks the tool/model per run; needsVision=false so the choice is honoured.
            claude.runWriting(AgentPrompts.analyze(images, p.characters, note), AgentPrompts.system(p), dir,
                    List.of("Read", "Write"), false, useAgy ? "agy" : "claude", model, ctx);
            if (!Files.exists(out)) throw new IOException("AI không tạo analysis.json");
            JsonNode a = projects.mapper().readTree(out.toFile());

            String title = a.path("title").asText("").trim();
            String language = a.path("language").asText("Vietnamese").trim();
            String sourceText = a.path("sourceText").asText("").trim();
            boolean verbatim = "verbatim".equals(a.path("narrationMode").asText()) && !sourceText.isEmpty();
            String brief = a.path("brief").asText("").trim();
            String style = a.path("style").asText("").trim();
            int count = Math.max(1, Math.min(a.path("scenes").asInt(6), AgentPrompts.MAX_SCENES));
            // History videos always use the full 34-scene structure, unless the user listed / asked for scenes.
            if ("history".equals(p.niche)) {
                boolean userCount = hasText && note.toLowerCase().contains("cảnh");
                count = userCount ? Math.min(count, AgentPrompts.HISTORY_SCENES) : AgentPrompts.HISTORY_SCENES;
            }
            ctx.log("  " + a.path("contentType").asText("?") + " · " + (verbatim ? "đọc nguyên văn" : "lời kể tự do")
                    + " · " + count + " cảnh · " + language);

            // Characters: crop a reference image from the source where the character is drawn.
            Set<String> known = new HashSet<>();
            for (CharacterRef c : p.characters) known.add(c.name.trim().toLowerCase());
            List<CharacterRef> added = new ArrayList<>();
            for (JsonNode n : a.path("characters")) {
                String name = n.path("name").asText("").trim();
                if (name.isEmpty() || !known.add(name.toLowerCase())) continue;
                CharacterRef c = new CharacterRef();
                c.name = name;
                c.description = n.path("description").asText("").trim();
                String img = n.path("image").asText("");
                JsonNode box = n.path("box");
                if (images.containsKey(img) && box.isArray() && box.size() == 4) {
                    try {
                        c.file = "ref-" + c.id + ".png";
                        cropWithPadding(assets.resolve(img), box, images.get(img), assets.resolve(c.file), ctx);
                    } catch (Exception e) {
                        ctx.log("  ⚠ không cắt được ảnh " + name + ": " + e.getMessage());
                        c.file = null;
                    }
                }
                added.add(c);
                ctx.log("  + nhân vật " + name + (c.file == null ? " (không có ảnh, chỉ mô tả)" : " (đã cắt ảnh tham chiếu)"));
            }
            projects.mutate(projectId, pr -> {
                pr.characters.addAll(added);
                pr.sourceText = sourceText;
                if (!title.isEmpty() && (pr.name == null || pr.name.isBlank() || "Dự án mới".equals(pr.name))) pr.name = title;
            });

            if (thenStoryboard) {
                ctx.log("▶ Viết kịch bản…");
                runStoryboard(projectId, AgentPrompts.storyboardTopicFromAnalysis(brief, sourceText, verbatim, note),
                        brief, count, language, style, false, useAgy ? "agy" : "claude", model, ctx);
            } else {
                projects.mutate(projectId, pr -> pr.topic = brief);
                ctx.log("✔ Phân tích xong");
            }
        });
    }

    /** Crops box (x, y, w, h) with ~8% padding, clamped to the image. */
    private void cropWithPadding(Path src, JsonNode box, int[] size, Path dest, JobContext ctx) throws Exception {
        double x = box.get(0).asDouble(), y = box.get(1).asDouble(), w = box.get(2).asDouble(), h = box.get(3).asDouble();
        if (w <= 4 || h <= 4) throw new IOException("box quá nhỏ");
        double padX = w * 0.08, padY = h * 0.08;
        int iw = size == null ? Integer.MAX_VALUE : size[0], ih = size == null ? Integer.MAX_VALUE : size[1];
        int x0 = (int) Math.max(0, x - padX), y0 = (int) Math.max(0, y - padY);
        int x1 = (int) Math.min(iw, x + w + padX), y1 = (int) Math.min(ih, y + h + padY);
        if (x1 - x0 < 16 || y1 - y0 < 16) throw new IOException("box nằm ngoài ảnh");
        // Small book scans give tiny crops; upscale so image tools can read the character's features.
        String vf = "crop=" + (x1 - x0) + ":" + (y1 - y0) + ":" + x0 + ":" + y0
                + ((y1 - y0) < 512 ? ",scale=-2:512:flags=lanczos" : "");
        ffmpeg.run(List.of("-i", src.toString(), "-vf", vf, "-frames:v", "1", "-y", dest.toString()), dest.getParent(), ctx);
    }

    private void runStoryboard(String projectId, String topic, String topicToStore, int count, String language,
                               String style, boolean append, JobContext ctx) throws Exception {
        runStoryboard(projectId, topic, topicToStore, count, language, style, append, null, null, ctx);
    }

    private void runStoryboard(String projectId, String topic, String topicToStore, int count, String language,
                               String style, boolean append, String ai, String model, JobContext ctx) throws Exception {
        {
            Path dir = projects.dir(projectId);
            // A drama series keeps its people from episode to episode (same faces, same portraits).
            if (!append && !"drama".equals(projects.get(projectId).niche)) {
                // A new script brings its own main figures: drop the ones the last script made (and their portraits).
                for (CharacterRef c : projects.get(projectId).characters) {
                    if (c.auto && c.file != null) Files.deleteIfExists(dir.resolve("assets").resolve(c.file));
                }
                projects.mutate(projectId, pr -> pr.characters.removeIf(c -> c.auto));
            }
            Project p = projects.get(projectId);
            Path out = dir.resolve("storyboard.json");
            Files.deleteIfExists(out);
            boolean history = "history".equals(p.niche);
            List<String> tools = history ? List.of("Write", "Read", "WebSearch", "WebFetch") : List.of("Write", "Read");
            if (history) ctx.log("  Claude tra cứu sự kiện, trang phục, hình tượng nhân vật trên mạng trước khi viết");

            if (!p.characters.isEmpty()) ctx.log("  dùng " + p.characters.size() + " nhân vật tham chiếu");
            claude.runWriting(AgentPrompts.storyboard(topic, count, language, style, p.aspect, p.characters, append, p.niche),
                    AgentPrompts.system(p), dir, tools,
                    p.characters.stream().anyMatch(c -> c.file != null && !c.file.isBlank()), ai, model, ctx);

            if (!Files.exists(out)) throw new IOException("AI không tạo storyboard.json");
            JsonNode root = projects.mapper().readTree(out.toFile());
            JsonNode arr = root.isArray() ? root : root.path("scenes");
            String bible = root.path("styleBible").asText("").trim();
            if (!arr.isArray() || arr.isEmpty()) throw new IOException("storyboard.json không hợp lệ");
            List<Scene> scenes = new ArrayList<>();
            boolean drama = "drama".equals(p.niche);
            // Modern history only: real photos the storyboard found, downloaded below as image references.
            boolean modern = history && root.path("modern").asBoolean(false);
            Map<String, RefPhotoService.Want> photoWants = new LinkedHashMap<>();
            for (JsonNode n : arr) {
                Scene s = new Scene();
                s.narration = n.path("narration").asText("");
                s.imagePrompt = n.path("imagePrompt").asText("");
                if (modern) {
                    var w = new RefPhotoService.Want(n.path("photoUrl").asText(""), n.path("photoPage").asText(""));
                    if (!w.isEmpty()) photoWants.put(s.id, w);
                }
                if (drama) {
                    String who = n.path("speaker").asText("").trim();
                    s.speaker = who.isEmpty() ? null : who.equalsIgnoreCase(Scene.SCREEN_TEXT) ? Scene.SCREEN_TEXT : who;
                    double sec = n.path("seconds").asDouble(0);
                    // Silent shots and on-screen text have no voice: their length comes from here.
                    if (sec > 0) s.duration = Math.max(1.0, Math.min(sec, 10.0));
                    else if (s.isScreenText()) s.duration = Scene.scriptSeconds(s.narration, 3.0);
                    else if (s.narration.isBlank()) s.duration = 2.5;
                }
                scenes.add(s);
            }
            if (!append) {
                // The old scenes go away: so do their downloaded reference photos.
                for (Scene old : projects.get(projectId).scenes) {
                    if (old.refPhoto != null && !old.refPhoto.isBlank()) {
                        Files.deleteIfExists(dir.resolve("assets").resolve(old.refPhoto));
                    }
                }
            }
            projects.mutate(projectId, pr -> {
                pr.topic = topicToStore;
                if (!append) {
                    pr.scenes.clear();
                    // New story, new look: drop the old style anchor.
                    pr.styleRefMediaId = null;
                    pr.styleRefSceneId = null;
                }
                // When appending, keep the existing bible so new scenes match the old ones.
                if (!bible.isEmpty() && (!append || pr.styleBible == null || pr.styleBible.isBlank())) {
                    pr.styleBible = bible;
                }
                pr.scenes.removeIf(s -> isEmpty(s));
                pr.scenes.addAll(scenes);
                for (JsonNode c : root.path("characters")) {
                    String name = c.path("name").asText("").trim();
                    String look = c.path("look").asText("").trim();
                    if (name.isEmpty() || pr.characters.stream().anyMatch(x -> x.name != null && x.name.trim().equalsIgnoreCase(name))) continue;
                    CharacterRef r = new CharacterRef();
                    r.name = name;
                    r.description = look;
                    r.auto = true;
                    pr.characters.add(r);
                }
                // Drama cast: keep the voices already chosen (a series keeps its voices), add new speakers.
                for (JsonNode c : root.path("cast")) {
                    String name = c.path("name").asText("").trim();
                    if (name.isEmpty() || name.equalsIgnoreCase(Scene.SCREEN_TEXT)) continue;
                    Project.CastMember m = pr.castMember(name);
                    if (m == null) {
                        m = new Project.CastMember();
                        m.name = name;
                        pr.cast.add(m);
                    }
                    String g = c.path("gender").asText("").trim().toLowerCase();
                    if (g.equals("male") || g.equals("female")) m.gender = g;
                    String note = c.path("note").asText("").trim();
                    if (!note.isEmpty()) m.note = note;
                }
                // A speaker the cast list forgot still gets a row, so a voice can be picked for it.
                for (Scene s : scenes) {
                    if (s.speaker == null || s.isScreenText() || pr.castMember(s.speaker) != null) continue;
                    Project.CastMember m = new Project.CastMember();
                    m.name = s.speaker;
                    pr.cast.add(m);
                }
            });
            if (drama) {
                long lines = scenes.stream().filter(s -> s.speaker != null && !s.isScreenText() && !s.narration.isBlank()).count();
                long texts = scenes.stream().filter(Scene::isScreenText).count();
                ctx.log("  " + lines + " câu thoại · " + texts + " chữ trên màn hình · "
                        + (scenes.size() - lines - texts) + " shot không lời · " + projects.get(projectId).cast.size() + " vai có giọng");
            }
            if (history) {
                ctx.log(!modern ? "  Lịch sử xa xưa (chưa có ảnh chụp): bỏ qua bước tìm ảnh thật"
                        : photoWants.isEmpty() ? "  Lịch sử hiện đại: không tìm được ảnh thật chắc chắn, uy tín nào — mọi cảnh tạo từ mô tả"
                        : "  Lịch sử hiện đại: Claude tìm được ảnh thật cho " + photoWants.size() + " cảnh, đang kiểm tra nguồn");
            }
            if (!photoWants.isEmpty()) {
                Map<String, Integer> nums = new HashMap<>();
                List<Scene> all = projects.get(projectId).scenes;
                for (int i = 0; i < all.size(); i++) nums.put(all.get(i).id, i + 1);
                Map<String, RefPhotoService.Saved> got = refPhotos.fetch(projectId, photoWants, nums, ctx);
                projects.mutate(projectId, pr -> {
                    for (var e : got.entrySet()) {
                        Scene x = pr.scenes.stream().filter(sc -> sc.id.equals(e.getKey())).findFirst().orElse(null);
                        if (x == null) continue;
                        x.refPhoto = e.getValue().file();
                        x.refPhotoSource = e.getValue().source();
                    }
                });
                ctx.log("  ✔ " + got.size() + " cảnh dùng ảnh thật đã kiểm tra nguồn; các cảnh khác tạo ảnh chỉ từ mô tả");
            }
            long made = projects.get(projectId).characters.stream().filter(c -> c.auto && c.file == null).count();
            if (made > 0) ctx.log("  + " + made + " nhân vật chính (bấm \"Tạo chân dung\" ở mục Nhân vật để vẽ ảnh tham chiếu)");
            ctx.log("✔ Đã tạo " + scenes.size() + " cảnh" + (bible.isEmpty() ? "" : " + bộ quy tắc hình ảnh"));
        }
    }

    /** Generates a scene visual with Google Flow (browser automation via Playwright MCP). */
    public Job flow(String projectId, String sceneId, String mediaType) {
        if (!settings.get().enableFlow) throw new IllegalStateException("Google Flow đang tắt trong Cài đặt");
        if (profileInUse(settings.get().browserProfile())) {
            throw new IllegalStateException("Chrome vẫn đang mở với profile Flow — hãy đóng cửa sổ Chrome đó rồi thử lại");
        }
        String ext = "video".equals(mediaType) ? ".mp4" : ".png";
        return mediaJob("flow", "Google Flow", projectId, sceneId, ext, FLOW_TOOLS,
                (p, s, target) -> AgentPrompts.flow(p, s, settings.get().flowUrl, mediaType, target,
                        projects.dir(projectId)));
    }

    private static final List<String> FLOW_TOOLS = List.of("mcp__playwright", "Bash(mv:*)", "Bash(cp:*)", "Bash(ls:*)", "Write");

    /**
     * Generates every scene's visual with Google Flow, one after another (the browser profile can only be open
     * once). A failed scene is logged and skipped, so one bad scene does not stop the rest.
     */
    public Job flowAll(String projectId, String mediaType, boolean onlyMissing) {
        if (!settings.get().enableFlow) throw new IllegalStateException("Google Flow đang tắt trong Cài đặt");
        if (profileInUse(settings.get().browserProfile())) {
            throw new IllegalStateException("Chrome vẫn đang mở với profile Flow — hãy đóng cửa sổ Chrome đó rồi thử lại");
        }
        Project p0 = projects.get(projectId);
        List<String> ids = new ArrayList<>();
        for (Scene s : p0.scenes) {
            if (s.imagePrompt != null && !s.imagePrompt.isBlank() && !(onlyMissing && s.mediaFile != null)) ids.add(s.id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Không có cảnh nào cần tạo hình");
        String ext = "video".equals(mediaType) ? ".mp4" : ".png";
        if (!"video".equals(mediaType)) {
            return jobs.start("flow", "Google Flow: " + ids.size() + " cảnh", projectId, null,
                    ctx -> flowImages(projectId, ids, ctx));
        }
        return jobs.start("flow", "Google Flow: " + ids.size() + " cảnh", projectId, null, ctx -> {
            int ok = 0;
            List<Integer> failed = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                if (ctx.cancelled()) return;
                ctx.log("━━ Cảnh " + (i + 1) + "/" + ids.size() + " ━━");
                String sid = ids.get(i);
                try {
                    mediaScene("flow", "Google Flow", projectId, sid, ext, FLOW_TOOLS,
                            (p, s, target) -> AgentPrompts.flow(p, s, settings.get().flowUrl, mediaType, target,
                                    projects.dir(projectId)), ctx);
                    ok++;
                } catch (IOException e) {
                    if (ctx.cancelled()) return;
                    ctx.log("✖ Cảnh " + (i + 1) + " lỗi: " + e.getMessage());
                    // Not signed in affects every scene: stop instead of repeating it.
                    if (e.getMessage() != null && e.getMessage().contains("đăng nhập")) throw e;
                    failed.add(i + 1);
                }
            }
            ctx.log("✔ Xong " + ok + "/" + ids.size() + " cảnh" + (failed.isEmpty() ? "" : "; lỗi ở cảnh " + failed));
            if (ok == 0) throw new IOException("Không tạo được cảnh nào bằng Flow");
        });
    }

    /** Scenes per Claude run. One browser session per batch; longer runs would overflow Claude's context. */
    private static final int FLOW_BATCH = 6;

    /**
     * Images for many scenes: each batch of up to {@value #FLOW_BATCH} scenes is ONE Claude run in ONE browser session,
     * references are uploaded once per batch. Pictures are assigned to their scenes as soon as the files appear.
     */
    private void flowImages(String projectId, List<String> ids, JobContext ctx) throws Exception {
        int ok = 0;
        List<Integer> failed = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += FLOW_BATCH) {
            if (ctx.cancelled()) return;
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + FLOW_BATCH));
            ctx.log("━━ Cảnh " + (from + 1) + "–" + (from + chunk.size()) + " / " + ids.size()
                    + " (một phiên trình duyệt, ảnh tham chiếu tải lên một lần) ━━");
            Project p = projects.get(projectId);
            Path dir = projects.dir(projectId);
            Path downloads = projects.folder(projectId, "downloads");
            projects.folder(projectId, "assets");
            List<Scene> scenes = new ArrayList<>();
            Map<String, String> targets = new java.util.LinkedHashMap<>();
            for (String sid : chunk) {
                scenes.add(p.scene(sid));
                String target = "assets/scene-" + sid + "-flow.png";
                Files.deleteIfExists(dir.resolve(target));
                targets.put(sid, target);
            }
            Path flowResult = dir.resolve(AgentPrompts.FLOW_RESULT);
            Files.deleteIfExists(flowResult);
            Path cfg = mcp.write(dir.resolve(".mcp-run.json"), downloads);

            // Watcher: hand each picture to its scene as soon as the file is complete (same size on two checks).
            java.util.Set<String> assigned = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Map<String, Long> lastSize = new ConcurrentHashMap<>();
            Runnable scan = () -> {
                for (var e : targets.entrySet()) {
                    String sid = e.getKey();
                    if (assigned.contains(sid)) continue;
                    Path f = dir.resolve(e.getValue());
                    try {
                        if (!Files.exists(f)) continue;
                        long size = Files.size(f);
                        if (size > 0 && lastSize.getOrDefault(sid, -1L) == size && isImage(f)) {
                            String name = f.getFileName().toString();
                            projects.mutate(projectId, pr -> {
                                Scene x = pr.scene(sid);
                                x.mediaFile = name;
                                x.mediaSource = "flow";
                            });
                            assigned.add(sid);
                            ctx.log("  ✔ Cảnh " + (ids.indexOf(sid) + 1) + " đã có hình (" + name + ")");
                        }
                        lastSize.put(sid, size);
                    } catch (Exception ignored) {
                    }
                }
            };
            Thread watcher = Thread.ofVirtual().start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(4000);
                        scan.run();
                    }
                } catch (InterruptedException ignored) {
                }
            });
            ClaudeCli.Result r = null;
            try {
                r = claude.run(AgentPrompts.flowBatch(p, scenes, targets, settings.get().flowUrl, dir),
                        AgentPrompts.system(p), dir, cfg, FLOW_TOOLS, ctx);
            } finally {
                watcher.interrupt();
                // Whatever was finished stays assigned, even if the run was cancelled or died half-way.
                scan.run();
                scan.run();
            }
            if (r.text() != null && r.text().contains("NEED_LOGIN")) {
                throw new IOException("Google Flow cần đăng nhập. Dùng nút đăng nhập trong Cài đặt rồi thử lại.");
            }
            rememberFlowProject(projectId, flowResult, ctx);
            if (r.text() != null && (r.text().contains("UNUSUAL_ACTIVITY") || r.text().toLowerCase().contains("unusual activity"))) {
                throw new IOException("Google Flow chặn vì phát hiện \"hoạt động bất thường\" (chống tự động của Google). "
                        + "Đã dừng, không thử lại. Hãy đợi khoảng 30–60 phút, kiểm tra tạo thử một ảnh bằng tay trong Chrome thường; "
                        + "khi Flow hoạt động bình thường thì chạy lại. Có " + ok + " cảnh đã xong trước khi bị chặn.");
            }
            for (String sid : chunk) {
                if (assigned.contains(sid)) ok++;
                else failed.add(ids.indexOf(sid) + 1);
            }
        }
        ctx.log("✔ Xong " + ok + "/" + ids.size() + " cảnh" + (failed.isEmpty() ? "" : "; chưa có hình ở cảnh " + failed));
        if (ok == 0) throw new IOException("Không tạo được cảnh nào bằng Flow");
    }

    /** Generates one scene's image with Canva, inside the project's Canva folder, anchored to the style reference. */
    public Job canva(String projectId, String sceneId) {
        if (!settings.get().enableCanva) throw new IllegalStateException("Canva đang tắt trong Cài đặt");
        requirePrompt(projects.get(projectId).scene(sceneId));
        return jobs.start("canva", "Canva: cảnh " + sceneId, projectId, sceneId,
                ctx -> canvaScene(projectId, sceneId, ctx));
    }

    /**
     * Generates every scene with Canva one after another. The first image becomes the style reference
     * (unless one is already set), so all later scenes reuse the same characters and look.
     *
     * @param onlyMissing skip scenes that already have a visual
     */
    public Job canvaAll(String projectId, boolean onlyMissing) {
        if (!settings.get().enableCanva) throw new IllegalStateException("Canva đang tắt trong Cài đặt");
        Project p0 = projects.get(projectId);
        List<String> ids = new ArrayList<>();
        // Generate the reference scene first if it is in the list, so others can anchor to it.
        for (Scene s : p0.scenes) {
            boolean has = s.mediaFile != null;
            if (s.imagePrompt != null && !s.imagePrompt.isBlank() && !(onlyMissing && has)) ids.add(s.id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Không có cảnh nào cần tạo hình");
        return jobs.start("canva", "Canva: " + ids.size() + " cảnh", projectId, null, ctx -> {
            for (int i = 0; i < ids.size(); i++) {
                if (ctx.cancelled()) return;
                ctx.log("━━ Cảnh " + (i + 1) + "/" + ids.size() + " ━━");
                canvaScene(projectId, ids.get(i), ctx);
            }
            ctx.log("✔ Xong " + ids.size() + " cảnh, tất cả nằm trong thư mục Canva của dự án");
        });
    }

    /** One Canva job per project at a time: they share the folder id and the style reference. */
    private final Map<String, ReentrantLock> canvaLocks = new ConcurrentHashMap<>();

    private void canvaScene(String projectId, String sceneId, JobContext ctx) throws Exception {
        ReentrantLock lock = canvaLocks.computeIfAbsent(projectId, k -> new ReentrantLock());
        if (lock.isLocked()) ctx.log("  ⏳ chờ tác vụ Canva khác của dự án này xong…");
        lock.lockInterruptibly();
        try {
            Project p = projects.get(projectId);
            Scene s = p.scene(sceneId);
            requirePrompt(s);
            Path dir = projects.dir(projectId);
            Path downloads = projects.folder(projectId, "downloads");
            Path assets = projects.folder(projectId, "assets");
            String target = "assets/scene-" + sceneId + "-canva.png";
            try (Stream<Path> old = Files.list(assets)) {
                for (Path f : old.filter(f -> f.getFileName().toString().startsWith("scene-" + sceneId + "-canva")).toList()) {
                    Files.deleteIfExists(f);
                }
            }
            Path result = dir.resolve(AgentPrompts.CANVA_RESULT);
            Files.deleteIfExists(result);
            FileTime started = FileTime.fromMillis(System.currentTimeMillis());

            if (!p.characters.isEmpty()) {
                List<CharacterRef> inScene = AgentPrompts.sceneCharacters(p, s);
                ctx.log("  nhân vật trong cảnh: " + (inScene.isEmpty() ? "(không có ảnh tham chiếu nào)"
                        : String.join(", ", inScene.stream().map(c -> c.name
                        + (c.canvaMediaId == null ? " (sẽ tải lên Canva)" : "")).toList())));
            }
            ctx.log(p.styleRefMediaId != null
                    ? "  tham chiếu phong cách: ảnh cảnh " + p.styleRefSceneId
                    : "  chưa có ảnh cảnh tham chiếu, ảnh này sẽ làm tham chiếu cho các cảnh sau");
            Path cfg = mcp.write(dir.resolve(".mcp-run.json"), downloads);
            ClaudeCli.Result r = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                r = claude.run(AgentPrompts.canva(p, s, target), AgentPrompts.system(p), dir, cfg,
                        List.of("mcp__canva", "Bash(curl:*)", "Bash(ls:*)", "Bash(mv:*)", "Bash(sleep:*)", "Write", "Read"), ctx);
                // The remote Canva server sometimes connects but lists no tools; that is not a login problem.
                if (r.hasServerTools("canva")) break;
                if (attempt == 3) throw new IOException("Canva MCP không trả về công cụ nào sau 3 lần thử — thử lại sau ít phút");
                ctx.log("  ⚠ Canva MCP chưa sẵn sàng (không có tool), thử lại lần " + (attempt + 1) + "…");
                Thread.sleep(5000L * attempt);
            }
            if (r.text() != null && r.text().contains("NEED_LOGIN")) {
                throw new IOException("Canva cần đăng nhập. Dùng nút đăng nhập trong Cài đặt rồi thử lại.");
            }

            // The image must come from export-design (full size); MCP previews are ~200px thumbnails.
            Path out = dir.resolve(target);
            if (!isImage(out)) throw new IOException("Claude không export được ảnh từ Canva (" + target + ")");
            int[] size = ffmpeg.probeSize(out);
            if (size != null) {
                ctx.log("  ảnh " + size[0] + "x" + size[1]);
                if (size[0] < p.width() / 2) {
                    throw new IOException("Ảnh Canva quá nhỏ (" + size[0] + "x" + size[1] + "), có thể là ảnh xem trước");
                }
            }
            String name = out.getFileName().toString();

            JsonNode ids = Files.exists(result) ? projects.mapper().readTree(result.toFile()) : projects.mapper().createObjectNode();
            String folderId = text(ids, "folderId");
            String mediaId = text(ids, "mediaId");
            String designId = text(ids, "designId");
            String pageId = text(ids, "pageId");
            if (mediaId == null) ctx.log("  ⚠ không đọc được media_id, cảnh này không dùng làm tham chiếu được");

            JsonNode charMedia = ids.path("characterMedia");
            Project after = projects.mutate(projectId, pr -> {
                charMedia.fields().forEachRemaining(e -> {
                    String mid = e.getValue().asText("").trim();
                    pr.characters.stream().filter(c -> c.id.equals(e.getKey())).findFirst()
                            .ifPresent(c -> { if (!mid.isEmpty()) c.canvaMediaId = mid; });
                });
                Scene x = pr.scene(sceneId);
                x.mediaFile = name;
                x.mediaSource = "canva";
                x.canvaMediaId = mediaId;
                x.canvaDesignId = designId;
                if (pageId != null) x.canvaPageId = pageId;
                if (folderId != null && (pr.canvaFolderId == null || pr.canvaFolderId.isBlank())) pr.canvaFolderId = folderId;
                if (designId != null && (pr.canvaDesignId == null || pr.canvaDesignId.isBlank())) pr.canvaDesignId = designId;
                if (pr.styleRefMediaId == null && mediaId != null) {
                    pr.styleRefMediaId = mediaId;
                    pr.styleRefSceneId = sceneId;
                }
            });
            ctx.log("✔ Đã gán " + name + " cho cảnh " + sceneId
                    + (sceneId.equals(after.styleRefSceneId) ? " (ảnh tham chiếu phong cách)" : ""));
        } finally {
            lock.unlock();
        }
    }

    /** PNG / JPEG / WebP signature check, so a stray text file is never taken for an image. */
    static boolean isImage(Path f) {
        if (!Files.isRegularFile(f)) return false;
        try (var in = Files.newInputStream(f)) {
            byte[] h = in.readNBytes(12);
            if (h.length < 12) return false;
            boolean png = (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
            boolean jpg = (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8;
            boolean webp = h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E';
            return png || jpg || webp;
        } catch (IOException e) {
            return false;
        }
    }

    private static void requirePrompt(Scene s) {
        if (s.imagePrompt == null || s.imagePrompt.isBlank()) {
            throw new IllegalArgumentException("Cảnh chưa có prompt hình ảnh");
        }
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText("").trim();
        return v.isEmpty() || "null".equals(v) ? null : v;
    }

    /** Chrome keeps <profile>/lockfile open while running; a stale one can be deleted. */
    public static boolean profileInUse(Path profile) {
        Path lock = profile.resolve("lockfile");
        if (!Files.exists(lock)) return false;
        try {
            Files.delete(lock);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** Opens Google Flow through Playwright MCP and reports LOGGED_IN / NEED_LOGIN. */
    public Job checkFlow() {
        if (!settings.get().enableFlow) throw new IllegalStateException("Google Flow đang tắt trong Cài đặt");
        return jobs.start("flow-check", "Kiểm tra Google Flow", null, null, ctx -> {
            Path ws = settings.get().workspace();
            Files.createDirectories(ws);
            Path cfg = mcp.write(ws.resolve(".mcp-check.json"), ws.resolve("downloads"));
            ClaudeCli.Result r = claude.run(AgentPrompts.flowCheck(settings.get().flowUrl),
                    "You are a non-interactive checker. Never ask questions.", ws, cfg,
                    List.of("mcp__playwright"), ctx);
            String t = r.text() == null ? "" : r.text();
            if (t.contains("NEED_LOGIN")) throw new IOException("Google Flow chưa đăng nhập trong profile này");
            if (!t.contains("LOGGED_IN")) throw new IOException("Không xác định được trạng thái: " + t);
            ctx.log("✔ Google Flow sẵn sàng");
        });
    }

    /** Free-form instruction with every enabled MCP server available. */
    public Job custom(String projectId, String prompt) {
        return jobs.start("agent", "Claude: " + abbrev(prompt), projectId, null, ctx -> {
            Project p = projects.get(projectId);
            Path dir = projects.dir(projectId);
            Path cfg = mcp.write(dir.resolve(".mcp-run.json"), dir.resolve("downloads"));
            List<String> tools = new ArrayList<>(List.of("Read", "Write", "Edit", "Bash(curl:*)", "Bash(ls:*)",
                    "Bash(mv:*)", "Bash(cp:*)"));
            for (String s : mcp.enabledServers()) tools.add("mcp__" + s);
            claude.run(prompt, AgentPrompts.system(p), dir, cfg, tools, ctx);
        });
    }

    private interface PromptFn {
        String build(Project p, Scene s, String target);
    }

    private Job mediaJob(String type, String label, String projectId, String sceneId, String ext,
                         List<String> tools, PromptFn prompt) {
        Project p0 = projects.get(projectId);
        Scene s0 = p0.scene(sceneId);
        if (s0.imagePrompt == null || s0.imagePrompt.isBlank()) {
            throw new IllegalArgumentException("Cảnh chưa có prompt hình ảnh");
        }
        return jobs.start(type, label + ": cảnh " + sceneId, projectId, sceneId,
                ctx -> mediaScene(type, label, projectId, sceneId, ext, tools, prompt, ctx));
    }

    /** Keeps the address of the Flow project the task worked in, so later scenes reuse the same one. */
    private void rememberFlowProject(String projectId, Path resultFile, JobContext ctx) {
        try {
            if (!Files.exists(resultFile)) return;
            String url = projects.mapper().readTree(resultFile.toFile()).path("projectUrl").asText("").trim();
            Files.deleteIfExists(resultFile);
            // Flow lives at labs.google/fx/tools/flow or flow.google.com, depending on the account; only a project page counts.
            boolean google = url.startsWith("https://labs.google/") || url.startsWith("https://flow.google.com/");
            if (!google || !url.contains("/project/")) return;
            Project cur = projects.get(projectId);
            if (url.equals(cur.flowProjectUrl)) return;
            projects.mutate(projectId, pr -> pr.flowProjectUrl = url);
            ctx.log("  🔗 Project Flow của dự án: " + url);
        } catch (Exception e) {
            ctx.log("  ⚠ không lưu được địa chỉ project Flow: " + e.getMessage());
        }
    }

    private void mediaScene(String type, String label, String projectId, String sceneId, String ext,
                            List<String> tools, PromptFn prompt, JobContext ctx) throws Exception {
        Project p = projects.get(projectId);
        Scene s = p.scene(sceneId);
        Path dir = projects.dir(projectId);
        Path downloads = projects.folder(projectId, "downloads");
        projects.folder(projectId, "assets");
        String target = "assets/scene-" + sceneId + "-" + type + ext;
        Files.deleteIfExists(dir.resolve(target));
        Path flowResult = dir.resolve(AgentPrompts.FLOW_RESULT);
        Files.deleteIfExists(flowResult);
        FileTime started = FileTime.fromMillis(System.currentTimeMillis());

        Path cfg = mcp.write(dir.resolve(".mcp-run.json"), downloads);
        ClaudeCli.Result r = claude.run(prompt.build(p, s, target), AgentPrompts.system(p), dir, cfg, tools, ctx);
        if (r.text() != null && r.text().contains("NEED_LOGIN")) {
            throw new IOException(label + " cần đăng nhập. Dùng nút đăng nhập trong Cài đặt rồi thử lại.");
        }
        if ("flow".equals(type)) rememberFlowProject(projectId, flowResult, ctx);

        Path saved = locate(dir, target, downloads, started, ctx);
        String name = saved.getFileName().toString();
        projects.mutate(projectId, pr -> {
            Scene x = pr.scene(sceneId);
            x.mediaFile = name;
            x.mediaSource = type;
        });
        ctx.log("✔ Đã gán " + name + " cho cảnh " + sceneId);
    }

    /** Finds the produced file: the requested target, any scene-<id>-<type>.* or the newest download. */
    private Path locate(Path dir, String target, Path downloads, FileTime since, JobContext ctx) throws IOException {
        Path exact = dir.resolve(target);
        if (Files.exists(exact)) return exact;

        Path assets = exact.getParent();
        String prefix = exact.getFileName().toString().replaceAll("\\.[^.]+$", "");
        try (Stream<Path> s = Files.list(assets)) {
            Optional<Path> alt = s.filter(f -> f.getFileName().toString().startsWith(prefix)).findFirst();
            if (alt.isPresent()) return alt.get();
        }

        try (Stream<Path> s = Files.walk(downloads)) {
            Optional<Path> newest = s.filter(Files::isRegularFile)
                    .filter(f -> MEDIA_EXT.contains(ext(f)))
                    .filter(f -> modified(f).compareTo(since) >= 0)
                    .max(Comparator.comparing(AgentService::modified));
            if (newest.isPresent()) {
                Path dest = assets.resolve(prefix + ext(newest.get()));
                Files.copy(newest.get(), dest, StandardCopyOption.REPLACE_EXISTING);
                ctx.log("  (lấy file mới nhất trong downloads/: " + newest.get().getFileName() + ")");
                return dest;
            }
        }
        throw new IOException("Không tìm thấy file kết quả (" + target + ")");
    }

    private Path emptyMcp(Path dir) throws IOException {
        Path cfg = dir.resolve(".mcp-empty.json");
        Files.writeString(cfg, "{\"mcpServers\":{}}");
        return cfg;
    }

    private static boolean isEmpty(Scene s) {
        return (s.narration == null || s.narration.isBlank()) && (s.imagePrompt == null || s.imagePrompt.isBlank())
                && s.mediaFile == null && s.audioFile == null;
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i);
    }

    private static String abbrev(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
