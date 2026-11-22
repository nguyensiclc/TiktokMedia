package com.mediastudio.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediastudio.agent.AgentService;
import com.mediastudio.agent.ClaudeCli;
import com.mediastudio.agent.McpConfigWriter;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobService;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Posts the exported swipe photos to TikTok Studio (tiktok.com/tiktokstudio/upload) without TikTok's API:
 * Claude drives the page through Playwright MCP, in the same persistent Chrome profile as Google Flow, where the user
 * is already signed in to TikTok. Captchas and security checks are never solved: the job stops and says so.
 */
@Component
public class StudioPoster {
    /** TikTok's photo-post limit. */
    static final int MAX_PHOTOS = 35;
    static final String RESULT = "tiktok-studio-result.json";
    public static final java.util.Set<String> PRIVACY_KEYS = java.util.Set.of("SELF_ONLY", "FRIENDS", "PUBLIC");
    static final Map<String, String> PRIVACY = Map.of(
            "SELF_ONLY", "Only me (Chỉ mình tôi)",
            "FRIENDS", "Friends (Bạn bè)",
            "PUBLIC", "Everyone (Mọi người)");

    private final ClaudeCli claude;
    private final McpConfigWriter mcp;
    private final ProjectService projects;
    private final JobService jobs;
    private final SettingsService settings;

    public StudioPoster(ClaudeCli claude, McpConfigWriter mcp, ProjectService projects, JobService jobs, SettingsService settings) {
        this.claude = claude;
        this.mcp = mcp;
        this.projects = projects;
        this.jobs = jobs;
        this.settings = settings;
    }

    /**
     * @param privacy SELF_ONLY / FRIENDS / PUBLIC
     * @param caption text + hashtags; null = the project's TikTok caption
     * @param aiLabel turn on TikTok's "AI-generated content" label (the pictures are AI-made)
     */
    public Job post(String projectId, String privacy, String caption, boolean aiLabel) {
        return post(projectId, privacy, caption, aiLabel, false);
    }

    /** @param draft true = click "Save draft" instead of Post: nothing is published, the post waits in Studio's drafts. */
    public Job post(String projectId, String privacy, String caption, boolean aiLabel, boolean draft) {
        Project p = projects.get(projectId);
        if (!PRIVACY.containsKey(privacy)) throw new IllegalArgumentException("Hãy chọn ai được xem bài đăng");
        if (p.slideFiles == null || p.slideFiles.isEmpty()) throw new IllegalArgumentException("Chưa có ảnh lướt — hãy Xuất danh sách ảnh trước");
        if (AgentService.profileInUse(settings.get().browserProfile())) {
            throw new IllegalStateException("Chrome đang mở với profile Flow/TikTok — hãy đóng Chrome rồi bấm lại");
        }
        String text = caption != null ? caption.trim() : (p.tiktokCaption == null ? "" : p.tiktokCaption.trim());
        if (text.length() > 2200) text = text.substring(0, 2200);
        String finalText = text;
        return jobs.start("tiktok-studio", "TikTok Studio: " + (draft ? "lưu nháp " : "đăng ") + Math.min(p.slideFiles.size(), MAX_PHOTOS) + " ảnh",
                projectId, null, ctx -> {
            Path dir = projects.dir(projectId);
            List<String> paths = new ArrayList<>();
            for (String f : p.slideFiles) {
                Path file = projects.file(projectId, "output", f);
                if (!Files.exists(file)) throw new IOException("Không tìm thấy " + f + " — hãy xuất lại danh sách ảnh");
                paths.add(file.toAbsolutePath().toString());
            }
            if (paths.size() > MAX_PHOTOS) {
                ctx.log("⚠ TikTok cho tối đa " + MAX_PHOTOS + " ảnh/bài: chỉ đăng " + MAX_PHOTOS + " ảnh đầu (bỏ "
                        + (paths.size() - MAX_PHOTOS) + " ảnh cuối)");
                paths = paths.subList(0, MAX_PHOTOS);
            }
            ctx.log("▶ Mở TikTok Studio, tải " + paths.size() + " ảnh, chế độ xem: " + PRIVACY.get(privacy) + (draft ? ", lưu nháp" : ""));
            Path out = dir.resolve(RESULT);
            Files.deleteIfExists(out);
            Path downloads = projects.folder(projectId, "downloads");
            Path cfg = mcp.writePlaywright(dir.resolve(".mcp-tiktok.json"), downloads);
            claude.run(draft ? draftPrompt(prompt(paths, finalText, PRIVACY.get(privacy), aiLabel)) : prompt(paths, finalText, PRIVACY.get(privacy), aiLabel),
                    "You operate TikTok Studio in a browser for the account owner, who asked for this post and approved"
                            + " every step described. Work autonomously; never ask questions. Never solve captchas or"
                            + " security checks.", dir, cfg, List.of("mcp__playwright", "Write"), ctx);
            if (!Files.exists(out)) throw new IOException("Claude không báo kết quả (" + RESULT + ")");
            JsonNode r = projects.mapper().readTree(out.toFile());
            String status = r.path("status").asText("FAILED");
            String msg = r.path("message").asText("");
            switch (status) {
                case "POSTED" -> {
                    Project.TiktokPost post = new Project.TiktokPost();
                    post.mode = "studio";
                    post.file = p.slidesZip != null ? p.slidesZip : paths.size() + " ảnh";
                    post.privacy = privacy;
                    post.status = "POSTED_VIA_STUDIO";
                    projects.mutate(projectId, pr -> pr.tiktokPosts.add(0, post));
                    ctx.log("✔ Đã đăng " + paths.size() + " ảnh lên TikTok Studio. " + msg);
                }
                case "DRAFTED" -> {
                    Project.TiktokPost post = new Project.TiktokPost();
                    post.mode = "studio-draft";
                    post.file = p.slidesZip != null ? p.slidesZip : paths.size() + " ảnh";
                    post.privacy = privacy;
                    post.status = "DRAFT_VIA_STUDIO";
                    projects.mutate(projectId, pr -> pr.tiktokPosts.add(0, post));
                    ctx.log("✔ Đã lưu " + paths.size() + " ảnh vào bản nháp TikTok Studio (chưa đăng). " + msg);
                }
                case "NEED_LOGIN" -> throw new IOException("TikTok chưa đăng nhập trong profile Chrome này: bấm \"Mở Chrome để đăng nhập TikTok\", đăng nhập rồi đóng Chrome");
                case "BLOCKED" -> throw new IOException("TikTok yêu cầu xác minh (captcha/bảo mật): hãy mở Chrome, tự xác minh rồi thử lại. " + msg);
                default -> throw new IOException("Không đăng được: " + msg);
            }
        });
    }

    /** Turns the post instructions into "save as draft" ones: steps 1-5 stay, the Post click and its check are replaced. */
    static String draftPrompt(String post) {
        int a = post.indexOf("6. Click the red Post button");
        int b = post.indexOf("8. Write ./");
        if (a < 0 || b < 0) throw new IllegalStateException("draft prompt: post steps not found");
        String steps = """
                6. DO NOT click Post / "Đăng". Click the "Save draft" / "Lưu bản nháp" button (usually next to Post,
                   grey / outlined). If a dialog asks to confirm saving the draft, confirm it. If there is no draft
                   button at all, do not post: write FAILED with message "không thấy nút Lưu bản nháp" and stop.
                7. Wait (up to 2 minutes) for the success state: a "draft saved" message ("Đã lưu bản nháp", "Draft
                   saved"), or the page moving to the content / drafts list. Then CHECK it: open
                   https://www.tiktok.com/tiktokstudio/content, open the Drafts tab ("Bản nháp" / "Drafts") if there is
                   one, and look for a draft created just now with the caption's first words. If you saw the success
                   message or that draft is there, the status is DRAFTED; otherwise FAILED with what you saw.
                   Take a final screenshot (browser_take_screenshot, filename "tiktok-studio-done.png").
                """;
        return post.substring(0, a) + steps + post.substring(b)
                .replace("\"POSTED\" | \"NEED_LOGIN\"", "\"DRAFTED\" | \"NEED_LOGIN\"")
                .replace("Never click Post more than once.", "Never click Post at all: this is a draft only. Never save the draft more than once.")
                .replaceFirst("Publish a PHOTO post", "Prepare a PHOTO post and SAVE IT AS A DRAFT (do NOT publish)");
    }

    static String prompt(List<String> files, String caption, String privacyLabel, boolean aiLabel) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < files.size(); i++) list.append("  ").append(i + 1).append(". ").append(files.get(i)).append('\n');
        return """
                Publish a PHOTO post (a swipe-through photo carousel, NOT a video) on TikTok Studio with the Playwright
                browser tools. The page may be in Vietnamese or English.

                PHOTOS (%d files, upload ALL of them, in exactly this order; the first one is the cover):
                %s
                CAPTION (type it exactly, keep the line breaks; empty = leave the description empty):
                <<<
                %s
                >>>
                WHO CAN VIEW: %s

                STEPS
                1. browser_navigate to https://www.tiktok.com/tiktokstudio/upload and take a snapshot.
                   - Login page or "Log in" / "Đăng nhập" button instead of the upload area: write the result NEED_LOGIN and stop.
                   - Captcha, puzzle slider, "verify", "Xác minh", or any security check: do NOT try to solve it. Write
                     BLOCKED (say what you saw) and stop.
                2. Click the upload button. It offers Video or Photo ("Ảnh" / "Photo(s)"): choose PHOTO. When the file chooser
                   opens, call browser_file_upload with ALL the paths above in one call, in that order. If only some were
                   accepted, use the page's add-more ("+" / "Thêm") control to add the missing ones, keeping the order.
                3. Wait (browser_wait_for, up to 3 minutes in total) until all %d photos are shown in the editor. Count them.
                   If the count is wrong or the first photo is not the cover, fix it (remove and re-add) before going on.
                4. Description: click the description / caption editor, select everything (ControlOrMeta+A) and delete it,
                   then type the caption. Type it line by line, pressing Enter between lines (two Enters for an empty line).
                   When a hashtag or mention suggestion list pops up, do NOT pick a suggestion: press Escape and go on, so
                   the text stays exactly as given. Check the editor text afterwards.
                5. Settings (open "Show more" / "Hiển thị thêm" if needed):
                   - "Who can view this post" / "Ai có thể xem": choose %s.
                   - %s
                   - Leave everything else as it is (no branded content / promotion, no schedule: post now).
                   - Sound: do not change it unless the page refuses to post without one; then pick the first suggested sound.
                6. Click the red Post button ("Đăng" / "Post"). If a dialog asks to confirm or warns about something,
                   read it: confirm a normal "post now?" question; if it is a content / copyright / policy warning, do not
                   bypass it, write FAILED with the warning text and stop.
                7. Wait (up to 3 minutes) for the success state: a "posted / uploaded" message ("Đã đăng", "Bài đăng của bạn
                   đã được tải lên", "Your post has been uploaded") or the page moving to the content / posts list.
                   Then CHECK it: open the posts list (https://www.tiktok.com/tiktokstudio/content), wait up to 2 minutes
                   (reload every ~20 s) for a new row created just now with the caption's first words, privacy "%s"
                   (it may say "processing" / "đang xử lý" first). Only if that row is there is the status POSTED; if the
                   post button was clicked but no row appears, use status FAILED with message "đã bấm Đăng nhưng chưa thấy
                   bài trong danh sách".
                   Take a final screenshot (browser_take_screenshot, filename "tiktok-studio-done.png").
                8. Write ./%s (overwrite) as exactly one JSON object:
                   {"status": "POSTED" | "NEED_LOGIN" | "BLOCKED" | "FAILED", "message": "<one short sentence, in Vietnamese>"}
                Never click Post more than once. Do not log out, do not change account settings, do not delete anything.
                Keep your final message short.
                """.formatted(files.size(), list, caption, privacyLabel, files.size(), privacyLabel,
                aiLabel ? "\"AI-generated content\" / \"Nội dung do AI tạo\": turn it ON (the pictures are AI-generated)."
                        : "\"AI-generated content\" / \"Nội dung do AI tạo\": leave it as it is.",
                privacyLabel, RESULT);
    }
}
