package com.mediastudio.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediastudio.agent.ClaudeCli;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobService;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.project.Scene;
import com.mediastudio.render.FfmpegService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api")
public class TiktokController {
    private static final Set<String> PRIVACY = Set.of("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY");

    private final TiktokClient tiktok;
    private final ProjectService projects;
    private final JobService jobs;
    private final ClaudeCli claude;
    private final FfmpegService ffmpeg;

    private final StudioPoster studio;

    public TiktokController(TiktokClient tiktok, ProjectService projects, JobService jobs, ClaudeCli claude, FfmpegService ffmpeg,
                            StudioPoster studio) {
        this.studio = studio;
        this.tiktok = tiktok;
        this.projects = projects;
        this.jobs = jobs;
        this.claude = claude;
        this.ffmpeg = ffmpeg;
    }

    // ---------------------------------------------------------------- account

    @GetMapping("/tiktok/status")
    public Map<String, Object> status(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>(tiktok.status());
        m.put("redirectUri", "http://127.0.0.1:" + req.getLocalPort() + TiktokClient.CALLBACK_PATH);
        m.put("redirectUriToRegister", "http://127.0.0.1:*" + TiktokClient.CALLBACK_PATH);
        m.put("requestedScopes", tiktok.scopes());
        return m;
    }

    /** Returns the TikTok authorize URL; the UI opens it in the system browser. */
    @PostMapping("/tiktok/login")
    public Map<String, Object> login(HttpServletRequest req) throws Exception {
        return Map.of("url", tiktok.authorizeUrl(req.getLocalPort()));
    }

    /** TikTok redirects the browser here after the user approves (or declines). */
    @GetMapping(value = {"/tiktok/callback", "/tiktok/callback/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error,
                           @RequestParam(name = "error_description", required = false) String errorDescription) {
        try {
            if (error != null) throw new IllegalStateException(error + (errorDescription == null ? "" : ": " + errorDescription));
            if (code == null) throw new IllegalStateException("Thiếu mã code");
            JsonNode tok = tiktok.handleCallback(code, state);
            return page("✔ Đã kết nối TikTok", "Quyền: " + HtmlUtils.htmlEscape(tok.path("scope").asText())
                    + "<br>Bạn có thể đóng tab này và quay lại Media Studio.");
        } catch (Exception e) {
            return page("✖ Kết nối TikTok thất bại", HtmlUtils.htmlEscape(String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/tiktok/logout")
    public Map<String, Object> logout() throws IOException {
        tiktok.logout();
        return Map.of("ok", true);
    }

    /** Creator nickname, allowed privacy levels, disabled interactions, max duration (video.publish). */
    @GetMapping("/tiktok/creator-info")
    public JsonNode creatorInfo() throws Exception {
        return tiktok.creatorInfo();
    }

    // ---------------------------------------------------------------- caption

    /** Claude writes a TikTok caption + hashtags from the project's script. Body: {tone?, extra?} */
    @PostMapping("/projects/{id}/tiktok/caption")
    public Job caption(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        Project p0 = projects.get(id);
        if (p0.scenes.stream().allMatch(s -> s.narration == null || s.narration.isBlank()) && blank(p0.topic)) {
            throw new IllegalArgumentException("Dự án chưa có chủ đề hay lời thoại để viết mô tả");
        }
        String tone = body == null ? "" : body.getOrDefault("tone", "");
        String extra = body == null ? "" : body.getOrDefault("extra", "");
        return jobs.start("caption", "Mô tả TikTok", id, null, ctx -> {
            Project p = projects.get(id);
            Path dir = projects.dir(id);
            Path out = dir.resolve("tiktok-caption.json");
            Files.deleteIfExists(out);
            StringBuilder script = new StringBuilder();
            for (int i = 0; i < p.scenes.size(); i++) {
                Scene s = p.scenes.get(i);
                if (s.narration != null && !s.narration.isBlank()) script.append(i + 1).append(". ").append(s.narration.trim()).append('\n');
            }
            String prompt = """
                    Write the TITLE and the CAPTION of a TikTok post for this short video / photo carousel.
                    Project name: %s
                    Topic: %s
                    Narration:
                    %s
                    Tone: %s
                    Extra instructions from the user: %s
%s
                    Rules:
                    - Same language as the narration (Vietnamese if the narration is Vietnamese).
                    - TITLE: max 60 characters, no hashtags, at most 1 emoji. Punchy and specific: the most striking
                      name / number / twist of the story, the kind of line that makes people stop scrolling
                      (e.g. "17 tuổi cưỡi voi đánh giặc: Bà Triệu"). Not a copy of the project name.
                    - CAPTION: EXACTLY 2-3 short sentences, about 200 characters in total, NOT more. Sentence 1 is a
                      hook (a surprising true fact or a sharp question); sentence 2 builds curiosity without spoiling
                      the ending; the optional sentence 3 is a short call to action (follow / comment). 1-2 emoji max.
                      No bullet points, no lists, no long summaries.
                    - HASHTAGS: 4-6 relevant tags: mix broad (e.g. #xuhuong #fyp) and specific ones for this topic;
                      no spaces inside hashtags, no misleading or unrelated tags.
                    - Do not invent facts that are not in the video.
                    Write ./tiktok-caption.json (overwrite) exactly as:
                      {"title": "<title>", "caption": "<the 2-3 sentences>", "hashtags": ["#tag1", "#tag2"]}
                    Write only that file.
                    """.formatted(p.name, nz(p.topic), script, blank(tone) ? "friendly, engaging" : tone, nz(extra),
                    "facts".equals(p.niche) ? "Niche: 'Did you know?' science / space facts. Hook with the most surprising fact as a question;"
                            + " ask viewers which fact surprised them most; hashtags like #bancobiet #khoahoc #vutru #suthatthuvi when they fit.\n"
                    : "history".equals(p.niche) ? "Niche: true Vietnamese / world history (wars). Only real, documented facts; never invent."
                            + " Hook with the most dramatic true fact; invite viewers to follow the history series; hashtags like"
                            + " #lichsu #lichsuvietnam #suviet #hocsu when they fit.\n" : "");
            // Always Claude: agy (Gemini) often finished without writing the file here.
            claude.runWriting(prompt, "You write social media captions. Never ask questions.", dir, List.of("Write"), false, "claude", null, ctx);
            if (!Files.exists(out)) throw new IOException("AI không tạo tiktok-caption.json");
            JsonNode j = projects.mapper().readTree(out.toFile());
            StringBuilder caption = new StringBuilder(j.path("caption").asText("").trim());
            List<String> tags = new ArrayList<>();
            for (JsonNode t : j.path("hashtags")) {
                String tag = t.asText("").trim().replace(" ", "");
                if (tag.isEmpty()) continue;
                tags.add(tag.startsWith("#") ? tag : "#" + tag);
            }
            if (!tags.isEmpty()) caption.append("\n\n").append(String.join(" ", tags));
            String text = caption.length() > 2200 ? caption.substring(0, 2200) : caption.toString();
            String t = j.path("title").asText("").replace('\n', ' ').trim();
            String title = t.length() > 90 ? t.substring(0, 90).trim() : t;
            projects.mutate(id, pr -> {
                pr.tiktokCaption = text;
                pr.tiktokTitle = title;
            });
            ctx.log("✔ Tiêu đề: " + title + "\n✔ Mô tả (" + text.length() + " ký tự):\n" + text);
        });
    }

    // ---------------------------------------------------------------- TikTok Studio (browser, no API)

    /** Posts the exported swipe photos through TikTok Studio in the browser. Body: {privacy, caption?, aiLabel?} */
    @PostMapping("/projects/{id}/tiktok/studio-post")
    public Job studioPost(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return studio.post(id, str(body.get("privacy")), str(body.get("caption")), str(body.get("title")),
                !Boolean.FALSE.equals(body.get("aiLabel")), false);
    }

    // ---------------------------------------------------------------- publish

    /**
     * Body: {mode: "draft"|"direct", file?, title, privacy_level, disable_comment, disable_duet, disable_stitch,
     *        brand_content_toggle, brand_organic_toggle, is_aigc, video_cover_timestamp_ms, consent}
     */
    @PostMapping("/projects/{id}/tiktok/publish")
    public Job publish(@PathVariable String id, @RequestBody Map<String, Object> body) throws Exception {
        Project p = projects.get(id);
        String mode = "direct".equals(body.get("mode")) ? "direct" : "draft";
        String file = str(body.get("file"));
        if (blank(file)) file = p.lastRender;
        if (blank(file)) throw new IllegalArgumentException("Chưa có video — hãy Render trước");
        Path video = projects.file(id, "output", file);
        if (!Files.exists(video)) throw new IllegalArgumentException("Không tìm thấy " + file);
        if (!Boolean.TRUE.equals(body.get("consent"))) {
            throw new IllegalArgumentException("Bạn cần xác nhận đồng ý trước khi đăng");
        }

        Map<String, Object> postInfo = new LinkedHashMap<>();
        String privacy = null;
        if ("direct".equals(mode)) {
            privacy = str(body.get("privacy_level"));
            if (!PRIVACY.contains(privacy)) throw new IllegalArgumentException("Hãy chọn quyền riêng tư");
            // TikTok UX rule: verify against the creator's current options and limits right before posting.
            JsonNode ci = tiktok.creatorInfo();
            boolean allowed = false;
            for (JsonNode o : ci.path("privacy_level_options")) if (privacy.equals(o.asText())) allowed = true;
            if (!allowed) throw new IllegalArgumentException("Tài khoản không cho phép quyền riêng tư " + privacy);
            int maxSec = ci.path("max_video_post_duration_sec").asInt(0);
            Double dur = ffmpeg.probeDuration(video);
            if (maxSec > 0 && dur != null && dur > maxSec) {
                throw new IllegalArgumentException(String.format("Video dài %.0fs, tài khoản chỉ cho đăng tối đa %ds", dur, maxSec));
            }
            boolean brandContent = Boolean.TRUE.equals(body.get("brand_content_toggle"));
            if (brandContent && "SELF_ONLY".equals(privacy)) {
                throw new IllegalArgumentException("Nội dung hợp tác thương mại (Branded content) không thể để Chỉ mình tôi");
            }
            String title = str(body.get("title"));
            if (title != null && title.length() > 2200) title = title.substring(0, 2200);
            postInfo.put("title", title == null ? "" : title);
            postInfo.put("privacy_level", privacy);
            postInfo.put("disable_comment", ci.path("comment_disabled").asBoolean() || !Boolean.TRUE.equals(body.get("allow_comment")));
            postInfo.put("disable_duet", ci.path("duet_disabled").asBoolean() || !Boolean.TRUE.equals(body.get("allow_duet")));
            postInfo.put("disable_stitch", ci.path("stitch_disabled").asBoolean() || !Boolean.TRUE.equals(body.get("allow_stitch")));
            postInfo.put("brand_content_toggle", brandContent);
            postInfo.put("brand_organic_toggle", Boolean.TRUE.equals(body.get("brand_organic_toggle")));
            postInfo.put("is_aigc", Boolean.TRUE.equals(body.get("is_aigc")));
            Object cover = body.get("video_cover_timestamp_ms");
            if (cover instanceof Number n) postInfo.put("video_cover_timestamp_ms", n.intValue());
        }

        String fileName = file;
        String privacyFinal = privacy;
        return jobs.start("tiktok", "TikTok: " + ("direct".equals(mode) ? "đăng" : "gửi nháp") + " " + fileName, id, null, ctx -> {
            String publishId = tiktok.upload(video, mode, postInfo, ctx);
            ctx.log("  publish_id: " + publishId);
            Project.TiktokPost post = new Project.TiktokPost();
            post.publishId = publishId;
            post.mode = mode;
            post.file = fileName;
            post.privacy = privacyFinal;
            post.status = "PROCESSING_UPLOAD";
            projects.mutate(id, pr -> pr.tiktokPosts.add(0, post));

            // Poll until TikTok has the video (inbox) or the post is published / failed. Moderation can take longer.
            String last = "";
            for (int i = 0; i < 120; i++) {
                if (ctx.cancelled()) {
                    ctx.log("ℹ Video ĐÃ được tải lên TikTok; chỉ dừng theo dõi trạng thái. Bấm ↻ trong Lịch sử đăng để cập nhật.");
                    return;
                }
                try {
                    Thread.sleep(i < 10 ? 3000 : 10000);
                } catch (InterruptedException e) {
                    ctx.log("ℹ Video ĐÃ được tải lên TikTok; chỉ dừng theo dõi trạng thái. Bấm ↻ trong Lịch sử đăng để cập nhật.");
                    return;
                }
                JsonNode st = tiktok.publishStatus(publishId);
                String status = st.path("status").asText();
                if (!status.equals(last)) ctx.log("  trạng thái: " + status);
                last = status;
                List<String> ids = new ArrayList<>();
                for (JsonNode n : st.path("publicaly_available_post_id")) ids.add(n.asText());
                String fail = st.path("fail_reason").asText("");
                projects.mutate(id, pr -> pr.tiktokPosts.stream().filter(x -> publishId.equals(x.publishId)).findFirst()
                        .ifPresent(x -> { x.status = status; x.failReason = fail; if (!ids.isEmpty()) x.postIds = ids; }));
                if ("FAILED".equals(status)) throw new IOException("TikTok báo lỗi: " + fail);
                if ("SEND_TO_USER_INBOX".equals(status)) {
                    ctx.log("✔ Đã gửi vào TikTok. Mở app TikTok → Hộp thư (thông báo) để chỉnh sửa và đăng.");
                    return;
                }
                if ("PUBLISH_COMPLETE".equals(status)) {
                    ctx.log("✔ Đã đăng lên TikTok." + ("SELF_ONLY".equals(privacyFinal) ? " (Chế độ: chỉ mình tôi)"
                            : " Video có thể mất vài phút kiểm duyệt trước khi hiện công khai."));
                    return;
                }
            }
            ctx.log("⚠ TikTok vẫn đang xử lý — kiểm tra lại sau trong app TikTok.");
        });
    }

    /** Re-reads TikTok's status for every post in the project (e.g. after a cancelled poll). */
    @PostMapping("/projects/{id}/tiktok/refresh")
    public Project refresh(@PathVariable String id) throws Exception {
        Project p = projects.get(id);
        Map<String, JsonNode> fresh = new HashMap<>();
        for (Project.TiktokPost post : p.tiktokPosts) {
            if (post.publishId == null) continue;
            try {
                fresh.put(post.publishId, tiktok.publishStatus(post.publishId));
            } catch (Exception e) {
                // keep the old status for this one (e.g. expired publish id)
            }
        }
        return projects.mutate(id, pr -> pr.tiktokPosts.forEach(x -> {
            JsonNode st = fresh.get(x.publishId);
            if (st == null) return;
            x.status = st.path("status").asText(x.status);
            x.failReason = st.path("fail_reason").asText("");
            List<String> ids = new ArrayList<>();
            for (JsonNode n : st.path("publicaly_available_post_id")) ids.add(n.asText());
            if (!ids.isEmpty()) x.postIds = ids;
        }));
    }

    // ---------------------------------------------------------------- utils

    private static String page(String title, String msg) {
        return """
                <!doctype html><html lang="vi"><head><meta charset="utf-8"><title>Media Studio · TikTok</title>
                <style>body{font-family:'Segoe UI',sans-serif;background:#0f1115;color:#e6e8ee;display:flex;align-items:center;
                justify-content:center;height:100vh;margin:0}div{background:#171a21;border:1px solid #2a2f3a;border-radius:12px;
                padding:28px 36px;max-width:520px}h2{margin-top:0}</style></head><body><div><h2>%s</h2><p>%s</p></div></body></html>
                """.formatted(HtmlUtils.htmlEscape(title), msg);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
