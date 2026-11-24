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
        return post(projectId, privacy, caption, null, aiLabel, false, null);
    }

    public Job post(String projectId, String privacy, String caption, boolean aiLabel, boolean draft) {
        return post(projectId, privacy, caption, null, aiLabel, draft, null);
    }

    /** What to search in TikTok's sound library when the user named no song: something that fits the niche. */
    static String defaultSound(String niche) {
        return switch (niche == null ? "" : niche) {
            case "history" -> "epic cinematic";
            case "facts" -> "mystery";
            case "drama" -> "sad piano";
            default -> "nhạc nhẹ nhàng";
        };
    }

    /**
     * @param title post title; null = the project's TikTok title
     * @param draft true = click "Save draft" instead of Post: nothing is published, the post waits in Studio's drafts.
     * @param sound song name / keyword to pick in TikTok's sound library. null or blank: a posted photo set gets a
     *              sound fitting the niche (it cannot be changed after posting); a draft keeps none (added later).
     */
    public Job post(String projectId, String privacy, String caption, String title, boolean aiLabel, boolean draft, String sound) {
        Project p = projects.get(projectId);
        String wanted = sound == null ? "" : sound.replace('\n', ' ').trim();
        String soundQuery = !wanted.isEmpty() ? wanted : draft ? "" : defaultSound(p.niche);
        if (!PRIVACY.containsKey(privacy)) throw new IllegalArgumentException("Hãy chọn ai được xem bài đăng");
        if (p.slideFiles == null || p.slideFiles.isEmpty()) throw new IllegalArgumentException("Chưa có ảnh lướt — hãy Xuất danh sách ảnh trước");
        if (AgentService.profileInUse(settings.get().browserProfile())) {
            throw new IllegalStateException("Chrome đang mở với profile Flow/TikTok — hãy đóng Chrome rồi bấm lại");
        }
        String text = caption != null ? caption.trim() : (p.tiktokCaption == null ? "" : p.tiktokCaption.trim());
        if (text.length() > 2200) text = text.substring(0, 2200);
        String finalText = text;
        String t = (title != null ? title : (p.tiktokTitle == null ? "" : p.tiktokTitle)).replace('\n', ' ').trim();
        String finalTitle = t.length() > 90 ? t.substring(0, 90).trim() : t;
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
            ctx.log("▶ Mở TikTok Studio, tải " + paths.size() + " ảnh, chế độ xem: " + PRIVACY.get(privacy) + (draft ? ", lưu nháp" : "")
                    + (soundQuery.isEmpty() ? "" : ", nhạc: " + soundQuery));
            Path out = dir.resolve(RESULT);
            Files.deleteIfExists(out);
            Path downloads = projects.folder(projectId, "downloads");
            Path cfg = mcp.writePlaywright(dir.resolve(".mcp-tiktok.json"), downloads);
            String steps = prompt(paths, finalTitle, finalText, PRIVACY.get(privacy), aiLabel, soundQuery);
            claude.run(draft ? draftPrompt(steps) : steps,
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

    static final String SOUNDS_RESULT = "tiktok-sounds.json";
    static final int MAX_SOUNDS = 5;

    /**
     * Searches TikTok Studio's sound library (photo editor, nothing is posted or saved) and keeps up to 5 results in
     * project.soundOptions, each with a preview clip in output/ when it could be fetched, to listen to before posting.
     * query blank = a sound fitting the niche.
     */
    public Job findSounds(String projectId, String query) {
        Project p = projects.get(projectId);
        if (p.slideFiles == null || p.slideFiles.isEmpty()) throw new IllegalArgumentException("Chưa có ảnh lướt — hãy Xuất danh sách ảnh trước");
        if (AgentService.profileInUse(settings.get().browserProfile())) {
            throw new IllegalStateException("Chrome đang mở với profile Flow/TikTok — hãy đóng Chrome rồi bấm lại");
        }
        String q = query == null || query.isBlank() ? defaultSound(p.niche) : query.replace('\n', ' ').trim();
        return jobs.start("tiktok-sounds", "Tìm nhạc TikTok: " + q, projectId, null, ctx -> {
            Path dir = projects.dir(projectId);
            Path photo = projects.file(projectId, "output", p.slideFiles.get(0));
            if (!Files.exists(photo)) throw new IOException("Không tìm thấy " + p.slideFiles.get(0) + " — hãy xuất lại danh sách ảnh");
            ctx.log("▶ Mở TikTok Studio, tìm nhạc \"" + q + "\" (không đăng bài)");
            Path out = dir.resolve(SOUNDS_RESULT);
            Files.deleteIfExists(out);
            Path cfg = mcp.writePlaywright(dir.resolve(".mcp-tiktok.json"), projects.folder(projectId, "downloads"));
            claude.run(soundsPrompt(photo.toAbsolutePath().toString(), q),
                    "You operate TikTok Studio in a browser for the account owner, who wants to listen to sound choices"
                            + " before posting. Work autonomously; never ask questions. Never post, never save a draft."
                            + " Never solve captchas or security checks.", dir, cfg, List.of("mcp__playwright", "Write"), ctx);
            if (!Files.exists(out)) throw new IOException("Claude không báo kết quả (" + SOUNDS_RESULT + ")");
            JsonNode r = projects.mapper().readTree(out.toFile());
            String status = r.path("status").asText("FAILED");
            if (!"OK".equals(status)) {
                String msg = r.path("message").asText("");
                throw new IOException(switch (status) {
                    case "NEED_LOGIN" -> "Chưa đăng nhập TikTok trong profile trình duyệt — mở Cài đặt → TikTok để đăng nhập";
                    case "BLOCKED" -> "TikTok yêu cầu xác minh (captcha) — hãy tự xử lý trong trình duyệt rồi thử lại. " + msg;
                    default -> "Không tìm được nhạc: " + msg;
                });
            }
            // old previews of an earlier search go away
            try (var old = Files.list(projects.folder(projectId, "output"))) {
                for (Path f : old.filter(f -> f.getFileName().toString().startsWith("nhac-")).toList()) Files.deleteIfExists(f);
            }
            List<Project.SoundOption> found = new ArrayList<>();
            for (JsonNode s : r.path("sounds")) {
                if (found.size() >= MAX_SOUNDS) break;
                Project.SoundOption o = new Project.SoundOption();
                o.title = s.path("title").asText("").trim();
                if (o.title.isEmpty()) continue;
                o.artist = s.path("artist").asText("").trim();
                o.duration = s.path("duration").asText("").trim();
                String page = s.path("pageUrl").asText("").trim();
                o.pageUrl = page.startsWith("https://www.tiktok.com/") ? page : null;
                String audio = s.path("audioUrl").asText("").trim();
                if (audio.startsWith("https://")) {
                    try {
                        o.file = download(audio, projectId, "nhac-" + (found.size() + 1));
                    } catch (Exception e) {
                        ctx.log("  ⚠ Không tải được đoạn nghe thử của \"" + o.title + "\": " + e.getMessage());
                    }
                }
                found.add(o);
            }
            if (found.isEmpty()) throw new IOException("Không tìm thấy bài nhạc nào cho \"" + q + "\"");
            projects.mutate(projectId, pr -> { pr.soundOptions = found; pr.soundQuery = q; });
            for (int i = 0; i < found.size(); i++) {
                Project.SoundOption o = found.get(i);
                ctx.log("  " + (i + 1) + ". " + o.title + (o.artist.isEmpty() ? "" : " – " + o.artist)
                        + (o.duration.isEmpty() ? "" : " (" + o.duration + ")") + (o.file != null ? " · có đoạn nghe thử" : o.pageUrl != null ? " · có link" : ""));
            }
            ctx.log("✔ Tìm được " + found.size() + " bài nhạc");
        });
    }

    /** File extension from the clip's first bytes (TikTok's URLs have none; its clips are usually AAC in M4A). */
    static String audioExt(byte[] b) {
        if (b.length > 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return ".m4a";
        if (b.length > 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return ".ogg";
        if (b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xF6) == 0xF0) return ".aac"; // ADTS
        return ".mp3"; // ID3 tag or MPEG frames
    }

    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(20)).build();

    /** Fetches a preview clip into output/ (TikTok's CDN wants a browser-like request from tiktok.com). Max 15 MB. */
    private String download(String url, String projectId, String base) throws Exception {
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(60))
                .header("Referer", "https://www.tiktok.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .GET().build();
        var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode());
        byte[] b = res.body();
        if (b.length < 2_000) throw new IOException("file quá nhỏ");
        if (b.length > 15L << 20) throw new IOException("file quá lớn");
        String type = res.headers().firstValue("content-type").orElse("");
        if (type.startsWith("text/") || type.contains("json")) throw new IOException("không phải file âm thanh (" + type + ")");
        String name = base + audioExt(b);
        Files.write(projects.file(projectId, "output", name), b);
        return name;
    }

    static String soundsPrompt(String photo, String query) {
        return """
                Find sounds in TikTok Studio's sound library so the account owner can listen to them before posting.
                Use the Playwright browser tools. The page may be in Vietnamese or English.
                NEVER click Post / "Đăng" and NEVER save a draft: this is only a search.

                SEARCH FOR: "%s"
                PHOTO to open the photo editor with: %s

                STEPS
                1. browser_navigate to https://www.tiktok.com/tiktokstudio/upload and take a snapshot.
                   - Login page / "Log in" / "Đăng nhập" instead of the upload area: result NEED_LOGIN, stop.
                   - Captcha / puzzle / "verify" / "Xác minh" / any security check: do NOT solve it; result BLOCKED, stop.
                2. Click the upload button, choose PHOTO ("Ảnh" / "Photo(s)"), and upload the one photo above with
                   browser_file_upload. Wait until the photo editor shows it. If the upload hangs (no progress for about
                   60 s), cancel it, reload the page and try again, at most 3 times.
                3. Open the sound picker: "Add sound" / "Thêm âm thanh" / "Âm thanh" / the music-note control. Type the
                   search text above in its search box and wait for the results.
                4. For each of the first %d results (in order): read its title, artist and duration. Click its play /
                   preview button, wait about 2 s, then find the address of the clip being played: run browser_evaluate
                   with () => [...document.querySelectorAll('audio, video, source')].map(e => e.currentSrc || e.src).filter(Boolean)
                   and/or look at browser_network_requests for a new audio request (.mp3 / .m4a / "audio" / "music" /
                   "tos" URLs on tiktokcdn / ibyteimg / muscdn …). Use the https URL that started with that preview (not a
                   blob: URL). Stop the preview before the next one. If the sound has a link to its page
                   (https://www.tiktok.com/music/…), note it too. Missing URLs are fine: leave them empty.
                5. Close the sound picker and leave the page WITHOUT posting or saving (discard the upload if asked:
                   "Discard" / "Bỏ" / "Hủy bỏ").
                6. Write ./%s (overwrite) as exactly one JSON object:
                   {"status": "OK" | "NEED_LOGIN" | "BLOCKED" | "FAILED", "message": "<one short sentence, in Vietnamese>",
                    "sounds": [{"title": "...", "artist": "...", "duration": "0:30", "audioUrl": "https://...", "pageUrl": "https://www.tiktok.com/music/..."}]}
                   OK only when at least one sound was listed.
                Do not log out, do not change account settings, do not delete anything. Keep your final message short.
                """.formatted(query.replace("\"", "'"), photo, MAX_SOUNDS, SOUNDS_RESULT);
    }

    static String prompt(List<String> files, String title, String caption, String privacyLabel, boolean aiLabel, String sound) {
        String soundRule = sound == null || sound.isBlank()
                ? "Sound: do not change it unless the page refuses to post without one; then pick the first suggested sound."
                : ("Sound / music (a posted photo set cannot change its sound later, so set it now): on the photo editor"
                + " click \"Add sound\" / \"Thêm âm thanh\" / \"Âm thanh\" (or the music-note control). In the sound search box"
                + " type \"" + sound.replace("\"", "'") + "\", wait for the results, and pick the best match (for a song name:"
                + " that song; for a mood: the first result) that is not greyed out / unavailable. Click its use / select"
                + " button (\"Use\" / \"Sử dụng\" / \"+\" / a check mark) so it becomes the post's sound, keep the default volume,"
                + " and close the sound panel. Check that the editor now shows that sound's name. If the editor has no sound"
                + " control or nothing can be picked, keep TikTok's default and go on, and say so in the result message.");
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < files.size(); i++) list.append("  ").append(i + 1).append(". ").append(files.get(i)).append('\n');
        return """
                Publish a PHOTO post (a swipe-through photo carousel, NOT a video) on TikTok Studio with the Playwright
                browser tools. The page may be in Vietnamese or English.

                PHOTOS (%d files, upload ALL of them, in exactly this order; the first one is the cover):
                %s
                TITLE (type it exactly; empty = leave the title empty):
                <<<
                %s
                >>>
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
                   STUCK UPLOAD: TikTok Studio sometimes hangs while uploading. Check with a snapshot about every 20 s. If
                   the upload shows no progress for about 60 s (same percentage / spinner / "Uploading" / "Đang tải lên",
                   no new photo appearing), or all photos are still not shown after 3 minutes, it is stuck: click the
                   upload's cancel control ("Cancel" / "Hủy" / "Huỷ" / the X on the upload), confirm a "Discard" /
                   "Bỏ" / "Hủy bỏ" question if one appears, then reload the page (browser_navigate to
                   https://www.tiktok.com/tiktokstudio/upload again, like pressing F5) and start over from step 2 with all
                   the photos. Do this at most 3 times; if it is still stuck on the 3rd try, write FAILED with message
                   "Tải ảnh lên TikTok Studio bị treo 3 lần" and stop.
                4. Title: photo posts have a separate one-line title field ("Add a title" / "Thêm tiêu đề" / "Tiêu đề",
                   above the description). If the TITLE is not empty, click it, clear it and type the title. If the page
                   has no title field, skip this and go on.
                   Description: click the description / caption editor, select everything (ControlOrMeta+A) and delete it,
                   then type the caption. Type it line by line, pressing Enter between lines (two Enters for an empty line).
                   Plain words: when a mention ("@") suggestion list pops up, press Escape and go on.
                   HASHTAGS must become real tags, or TikTok treats them as plain text: a typed "#tag" stays plain until it
                   is picked from the suggestion list (a real tag is shown bold / as its own element). For EACH hashtag:
                   type it ("#" + the word, no space after it), wait about 1 s for the suggestion list, then click the
                   suggestion whose text is exactly that hashtag (same spelling, not a longer one like #hocsuaxe for
                   #hocsu). If no suggestion matches exactly, press Escape and keep it as typed. After picking, type ONE
                   space before the next hashtag only if the editor did not add one itself (never two spaces).
                   Check the editor afterwards (snapshot): the text matches the caption, and each hashtag that had an
                   exact suggestion is its own bold tag element.
                5. Settings (open "Show more" / "Hiển thị thêm" if needed):
                   - "Who can view this post" / "Ai có thể xem": choose %s.
                   - %s
                   - Leave everything else as it is (no branded content / promotion, no schedule: post now).
                   - %s
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
                """.formatted(files.size(), list, title, caption, privacyLabel, files.size(), privacyLabel,
                aiLabel ? "\"AI-generated content\" / \"Nội dung do AI tạo\": turn it ON (the pictures are AI-generated)."
                        : "\"AI-generated content\" / \"Nội dung do AI tạo\": leave it as it is.",
                soundRule, privacyLabel, RESULT);
    }
}
