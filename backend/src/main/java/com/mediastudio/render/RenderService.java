package com.mediastudio.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediastudio.agent.ClaudeCli;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobContext;
import com.mediastudio.job.JobService;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.project.Scene;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds one segment per scene (visual + voice) and concatenates them into the final MP4 + SRT. */
@Service
public class RenderService {
    private static final Set<String> VIDEO_EXT = Set.of(".mp4", ".mov", ".webm", ".mkv", ".m4v");
    private static final int FPS = 30;

    private final ProjectService projects;
    private final FfmpegService ffmpeg;
    private final JobService jobs;
    private final ClaudeCli claude;

    public RenderService(ProjectService projects, FfmpegService ffmpeg, JobService jobs, ClaudeCli claude) {
        this.projects = projects;
        this.ffmpeg = ffmpeg;
        this.jobs = jobs;
        this.claude = claude;
    }

    public Job render(String projectId) {
        return jobs.start("render", "Render video", projectId, null, ctx -> doRender(projectId, ctx));
    }

    /**
     * "List ảnh" mode: one still per scene (video clips give their first frame), sized like the video, with the
     * scene text in its own band under the picture when withText (see SlideCard). Saved as JPG for swiping through (e.g. a TikTok photo post).
     */
    public Job slides(String projectId, boolean withText) {
        return slides(projectId, withText, null, null);
    }

    /** @param coverTitle / coverTeaser null = keep the saved text, blank = let Claude write it again */
    public Job slides(String projectId, boolean withText, String coverTitle, String coverTeaser) {
        return slides(projectId, withText, coverTitle, coverTeaser, null);
    }

    /** @param ratio "video" (same size as the video), "3:4" or "1:1"; null = the project's last choice. */
    public Job slides(String projectId, boolean withText, String coverTitle, String coverTeaser, String ratio) {
        if (ratio != null && java.util.Set.of("video", "3:4", "1:1").contains(ratio)) projects.mutate(projectId, pr -> pr.slideRatio = ratio);
        saveCoverText(projectId, coverTitle, coverTeaser);
        return jobs.start("slides", "Xuất ảnh lướt", projectId, null, ctx -> doSlides(projectId, withText, ctx));
    }

    private void saveCoverText(String projectId, String coverTitle, String coverTeaser) {
        if (coverTitle == null && coverTeaser == null) return;
        projects.mutate(projectId, pr -> {
            if (coverTitle != null) pr.coverTitle = coverTitle.trim();
            if (coverTeaser != null) pr.coverTeaser = coverTeaser.trim();
            if (coverTitle != null && coverTitle.isBlank()) pr.coverScene = null;
        });
    }

    private static final java.util.regex.Pattern SLIDE_NAME = java.util.regex.Pattern.compile("slides-(\\d{8}-\\d{6})-\\d+\\.jpg");

    /**
     * Makes only the cover slide again (text null = keep, blank = Claude rewrites it) and puts it first in the
     * exported set, replacing the old cover; the scene photos are left as they are.
     */
    public Job cover(String projectId, String coverTitle, String coverTeaser) {
        Project p0 = projects.get(projectId);
        if (p0.slideFiles == null || p0.slideFiles.isEmpty()) throw new IllegalArgumentException("Hãy xuất danh sách ảnh trước");
        saveCoverText(projectId, coverTitle, coverTeaser);
        return jobs.start("slides", "Tạo lại ảnh bìa", projectId, null, ctx -> {
            Project p = projects.get(projectId);
            java.util.regex.Matcher m = SLIDE_NAME.matcher(p.slideFiles.get(0));
            String stamp = m.matches() ? m.group(1) : LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path assets = projects.folder(projectId, "assets");
            Path out = projects.folder(projectId, "output");
            Path tmp = out.resolve("tmp-cover");
            Files.createDirectories(tmp);
            int[] wh = slideSize(p);
            String name = String.format("slides-%s-00.jpg", stamp);
            if (!renderCover(p, assets, tmp, wh[0], wh[1], out.resolve(name), ctx))
                throw new IllegalStateException("Chưa cảnh nào có hình để làm ảnh bìa");
            List<String> files = new ArrayList<>();
            files.add(name);
            for (String f : p.slideFiles) if (!f.equals(name)) files.add(f);
            String zipName = "slides-" + stamp + ".zip";
            String music = p.slidesMusic != null && Files.exists(out.resolve(p.slidesMusic)) ? p.slidesMusic : null;
            writeZip(out, zipName, files, music);
            projects.mutate(projectId, pr -> {
                pr.slideFiles = files;
                pr.slidesZip = zipName;
            });
            cleanup(tmp);
            ctx.log("✔ Đã tạo lại ảnh bìa: " + files.size() + " ảnh trong bộ");
        });
    }

    private static int[] slideSize(Project p) {
        if ("3:4".equals(p.slideRatio)) return new int[]{1080, 1440};
        if ("1:1".equals(p.slideRatio)) return new int[]{1080, 1080};
        return new int[]{p.width(), p.height()};
    }

    /** Cover slide: story title + a curiosity line over the most fitting picture. False when no scene has a picture. */
    private boolean renderCover(Project p, Path assets, Path tmp, int w, int h, Path dest, JobContext ctx) throws Exception {
        p = ensureCoverText(p, projects.dir(p.id), ctx);
        Path coverMedia = coverMedia(p, assets);
        if (coverMedia == null) return false;
        Path src = tmp.resolve("cover-src.png");
        ffmpeg.run(List.of("-i", coverMedia.toString(), "-frames:v", "1", "-vf", "scale=" + w + ":" + h
                + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h + ",setsar=1", "-update", "1", "-y", src.toString()), tmp, ctx);
        boolean facts = "facts".equals(p.niche);
        BufferedImage cover = CoverCard.render(ImageIO.read(src.toFile()), p.coverTitle, p.coverTeaser,
                facts ? "BẠN CÓ BIẾT?" : "history".equals(p.niche) ? "CHUYỆN LỊCH SỬ" : "TRUYỆN CỔ TÍCH", facts, w, h);
        writeJpg(cover, dest);
        ctx.log("▶ Trang bìa: " + p.coverTitle + " — " + p.coverTeaser);
        return true;
    }

    private static void writeZip(Path out, String zipName, List<String> files, String musicName) throws IOException {
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(out.resolve(zipName)))) {
            int k = 0;
            for (String f : files) {
                zos.putNextEntry(new java.util.zip.ZipEntry(String.format("slide-%02d.jpg", ++k)));
                Files.copy(out.resolve(f), zos);
                zos.closeEntry();
            }
            if (musicName != null) {
                zos.putNextEntry(new java.util.zip.ZipEntry("nhac-nen.mp3"));
                Files.copy(out.resolve(musicName), zos);
                zos.closeEntry();
            }
        }
    }

    private void doSlides(String projectId, boolean withText, JobContext ctx) throws Exception {
        Project p = projects.get(projectId);
        if (p.scenes.isEmpty()) throw new IllegalStateException("Dự án chưa có cảnh nào");
        Path assets = projects.folder(projectId, "assets");
        Path out = projects.folder(projectId, "output");
        Path tmp = out.resolve("tmp-slides");
        Files.createDirectories(tmp);
        int w = slideSize(p)[0], h = slideSize(p)[1];
        ctx.log("  khung ảnh: " + w + "x" + h);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        List<String> files = new ArrayList<>();
        int n = 0;

        // Cover first: story title + a curiosity line over the most fitting picture.
        try {
            String name = String.format("slides-%s-00.jpg", stamp);
            if (renderCover(p, assets, tmp, w, h, out.resolve(name), ctx)) files.add(name);
        } catch (Exception e) {
            ctx.log("⚠ Không tạo được trang bìa (" + e.getMessage() + "), xuất tiếp các ảnh cảnh");
        }
        for (int i = 0; i < p.scenes.size(); i++) {
            if (ctx.cancelled()) return;
            Scene s = p.scenes.get(i);
            Path media = s.mediaFile == null ? null : assets.resolve(s.mediaFile);
            if (media == null || !Files.exists(media)) {
                ctx.log("⚠ Cảnh " + (i + 1) + " chưa có hình, bỏ qua");
                continue;
            }
            n++;
            String text = continued(s.narration, i == p.scenes.size() - 1);
            BufferedImage rgb;
            if (withText && !text.isEmpty()) {
                // Picture kept whole (no padding) above its own text band, so the text never covers it.
                Path src = tmp.resolve(String.format("src-%03d.png", i));
                ffmpeg.run(List.of("-i", media.toString(), "-frames:v", "1", "-vf",
                        "scale=" + w + ":" + h + ":force_original_aspect_ratio=increase,setsar=1", "-update", "1", "-y", src.toString()), tmp, ctx);
                rgb = SlideCard.render(ImageIO.read(src.toFile()), text, w, h);
            } else {
                Path base = tmp.resolve(String.format("base-%03d.png", i));
                ffmpeg.run(List.of("-i", media.toString(), "-frames:v", "1", "-vf",
                        "scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease,pad=" + w + ":" + h
                                + ":(ow-iw)/2:(oh-ih)/2:color=black,setsar=1", "-update", "1", "-y", base.toString()), tmp, ctx);
                rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                var g = rgb.createGraphics();
                g.drawImage(ImageIO.read(base.toFile()), 0, 0, null);
                g.dispose();
            }
            String name = String.format("slides-%s-%02d.jpg", stamp, n);
            writeJpg(rgb, out.resolve(name));
            files.add(name);
            ctx.log("▶ Ảnh " + n + "/" + p.scenes.size() + " (cảnh " + (i + 1) + ")");
        }
        if (files.isEmpty()) throw new IllegalStateException("Chưa cảnh nào có hình để xuất");

        // The project's music, already cut at its start point, as its own MP3 next to the pictures.
        String musicName = null;
        Path music = p.musicFile == null ? null : assets.resolve(p.musicFile);
        if (music != null && Files.exists(music)) {
            double start = p.musicStart == null ? 0 : p.musicStart;
            Double len = ffmpeg.probeDuration(music);
            if (len != null && start >= len - 1) {
                ctx.log(String.format(Locale.ROOT, "  ⚠ điểm bắt đầu nhạc (%.1fs) vượt độ dài file (%.1fs), dùng từ đầu", start, len));
                start = 0;
            }
            musicName = "slides-" + stamp + "-nhac.mp3";
            List<String> args = new ArrayList<>();
            if (start > 0.05) args.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", start)));
            args.addAll(List.of("-i", music.toString(), "-vn", "-c:a", "libmp3lame", "-b:a", "192k", "-y", out.resolve(musicName).toString()));
            ctx.log(String.format(Locale.ROOT, "▶ Cắt nhạc nền từ giây %.1f → %s", start, musicName));
            ffmpeg.run(args, tmp, ctx);
        }

        String zipName = "slides-" + stamp + ".zip";
        writeZip(out, zipName, files, musicName);
        String musicFinal = musicName;
        projects.mutate(projectId, pr -> {
            pr.slideFiles = files;
            pr.slidesZip = zipName;
            pr.slidesMusic = musicFinal;
        });
        cleanup(tmp);
        ctx.log("✔ Hoàn tất: " + files.size() + " ảnh " + w + "x" + h + (musicName != null ? " + nhạc đã cắt" : "") + " + " + zipName);
    }

    /** Asks Claude for the cover title + teaser (+ which scene to show) when they are not set yet. */
    private Project ensureCoverText(Project p, Path dir, JobContext ctx) throws Exception {
        if (!blank(p.coverTitle) && !blank(p.coverTeaser)) return p;
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < p.scenes.size(); i++) {
            Scene s = p.scenes.get(i);
            if (!blank(s.narration)) script.append(i + 1).append(". ").append(s.narration.trim()).append('\n');
        }
        Path out = dir.resolve("cover.json");
        Files.deleteIfExists(out);
        boolean facts = "facts".equals(p.niche);
        String keepTitle = blank(p.coverTitle) ? "" : p.coverTitle.trim();
        String prompt = """
                Write the cover page text for a TikTok swipe-through photo post (%s).
                Project name (may lack diacritics): %s
                Topic: %s
                Scenes:
                %s
                %s
                Rules:
                - Same language as the scenes, with correct spelling and diacritics.
                - "title": the title, 2-7 words, as people know it (e.g. "Sự tích trầu cau"). %s
                - "teaser": ONE short line (max ~12 words) that makes viewers curious enough to swipe: hint at a
                  mystery, a dilemma or a surprising twist WITHOUT revealing the ending. No hashtags, no emoji, no quotes.
                - "scene": the number of the scene whose picture would make the most striking cover
                  (usually the main character in a dramatic or beautiful moment; not the ending).
                Use only the text above: do not search, do not run commands.
                Write ./cover.json (overwrite) exactly as: {"title": "...", "teaser": "...", "scene": 1}
                Write only that file.
                """.formatted(facts ? "a 'Did you know?' science / space facts video"
                        : "history".equals(p.niche) ? "a true history video about a war (only real facts)" : "a folk tale / story",
                p.name, p.topic == null ? "" : p.topic, script,
                keepTitle.isEmpty() ? "" : "The title is fixed, keep it exactly: " + keepTitle,
                facts ? "For facts, a punchy title like '5 sự thật về Mặt Trời'."
                        : "history".equals(p.niche) ? "For history, the name people know, e.g. 'Trận Bạch Đằng 938'. The teaser must stay historically true." : "");
        ctx.log("▶ Claude viết tên truyện + câu gợi tò mò cho trang bìa…");
        claude.runWriting(prompt, "You write short, catchy social media cover text. Never ask questions.", dir,
                List.of("Write"), false, "claude", null, ctx);
        if (!Files.exists(out)) throw new IOException("AI không tạo cover.json");
        JsonNode j = projects.mapper().readTree(out.toFile());
        String title = keepTitle.isEmpty() ? j.path("title").asText("").trim() : keepTitle;
        if (title.isEmpty()) title = p.name;
        String teaser = blank(p.coverTeaser) ? j.path("teaser").asText("").trim() : p.coverTeaser.trim();
        int scene = j.path("scene").asInt(1);
        String t = title, te = teaser;
        return projects.mutate(p.id, pr -> {
            pr.coverTitle = t;
            pr.coverTeaser = te;
            if (pr.coverScene == null) pr.coverScene = scene;
        });
    }

    /** The picture for the cover: the chosen scene, else the first scene that has one. */
    private static Path coverMedia(Project p, Path assets) {
        List<Integer> order = new ArrayList<>();
        if (p.coverScene != null) order.add(p.coverScene - 1);
        for (int i = 0; i < p.scenes.size(); i++) order.add(i);
        for (int i : order) {
            if (i < 0 || i >= p.scenes.size()) continue;
            Scene s = p.scenes.get(i);
            if (s.mediaFile == null) continue;
            Path m = assets.resolve(s.mediaFile);
            if (Files.exists(m)) return m;
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * On-screen script text: every part except the last ends with "..." so the reader knows more follows.
     * Trailing , ; : . are replaced by the dots; ? and ! are kept in front of them.
     */
    static String continued(String narration, boolean last) {
        String t = narration == null ? "" : narration.trim();
        if (t.isEmpty() || last) return t;
        t = t.replaceAll("[\\s,;:.…]+$", "");
        return t.isEmpty() ? "" : t + "...";
    }

    private static void writeJpg(BufferedImage img, Path dest) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.93f);
        try (var ios = ImageIO.createImageOutputStream(Files.newOutputStream(dest))) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private void doRender(String projectId, JobContext ctx) throws Exception {
        Project p = projects.get(projectId);
        if (p.scenes.isEmpty()) throw new IllegalStateException("Dự án chưa có cảnh nào");
        Path assets = projects.folder(projectId, "assets");
        Path out = projects.folder(projectId, "output");
        Path tmp = out.resolve("tmp");
        Files.createDirectories(tmp);

        int w = p.width(), h = p.height();
        Path cache = out.resolve("cache");
        Files.createDirectories(cache);
        List<String> concat = new ArrayList<>();
        List<double[]> timeline = new ArrayList<>();
        Set<String> used = new java.util.HashSet<>();
        int reused = 0, built = 0;
        double t = 0;

        for (int i = 0; i < p.scenes.size(); i++) {
            if (ctx.cancelled()) return;
            Scene s = p.scenes.get(i);
            Path audio = p.scriptMode || s.audioFile == null ? null : assets.resolve(s.audioFile);
            if (audio != null && !Files.exists(audio)) audio = null;
            Path media = s.mediaFile == null ? null : assets.resolve(s.mediaFile);
            if (media != null && !Files.exists(media)) media = null;

            double dur = p.sceneDuration(s, audio != null);

            // Incremental render: a scene is only rebuilt when something that shows up in it has changed.
            String text = p.scriptMode ? continued(s.narration, i == p.scenes.size() - 1) : "";
            String key = sha("seg|" + SEG_VERSION + "|" + ScriptOverlay.VERSION + "|" + w + "x" + h + "|kb=" + p.kenBurns
                    + "|script=" + p.scriptMode + "|dur=" + String.format(Locale.ROOT, "%.3f", dur)
                    + "|media=" + fp(media) + "|audio=" + fp(audio) + "|text=" + text);
            Path cached = cache.resolve("seg-" + key + ".mp4");
            used.add(cached.getFileName().toString());
            concat.add("file '" + cached.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\\''") + "'");
            timeline.add(new double[]{t, t + dur});
            t += dur;
            if (Files.exists(cached) && Files.size(cached) > 0) {
                reused++;
                ctx.log(String.format(Locale.ROOT, "↻ Cảnh %d/%d (%.1fs): không đổi, dùng lại bản đã render", i + 1, p.scenes.size(), dur));
                continue;
            }
            built++;
            String seg = String.format("seg-%03d.mp4", i);
            ctx.log(String.format(Locale.ROOT, "▶ Cảnh %d/%d (%.1fs)%s%s", i + 1, p.scenes.size(), dur,
                    media == null ? " — không có hình, dùng nền đen" : "",
                    p.scriptMode ? " — chữ script trên hình" : audio == null ? " — không có giọng" : ""));

            // Script mode: draw the text where this picture is calmest (cached, shared with the editor preview).
            Path overlay = null;
            if (p.scriptMode && !text.isEmpty()) {
                try {
                    overlay = overlay(p, i);
                    if (overlay != null) used.add(overlay.getFileName().toString());
                } catch (Exception e) {
                    ctx.log("  ⚠ không vẽ được chữ script: " + e.getMessage());
                    overlay = null;
                }
            }

            List<String> a = new ArrayList<>();
            boolean isVideo = media != null && VIDEO_EXT.contains(ext(media));
            boolean zoom = media != null && !isVideo && p.kenBurns;
            String scaleFit = "scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease,"
                    + "pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2:color=black";

            String vf;
            if (media == null) {
                a.addAll(List.of("-f", "lavfi", "-i", "color=c=black:s=" + w + "x" + h + ":r=" + FPS));
                vf = "setsar=1,format=yuv420p";
            } else if (isVideo) {
                a.addAll(List.of("-stream_loop", "-1", "-i", media.toString()));
                vf = scaleFit + ",setsar=1,fps=" + FPS + ",format=yuv420p";
            } else if (zoom) {
                int frames = (int) Math.ceil(dur * FPS) + 1;
                a.addAll(List.of("-i", media.toString()));
                vf = "scale=" + (w * 2) + ":" + (h * 2) + ":force_original_aspect_ratio=increase,"
                        + "crop=" + (w * 2) + ":" + (h * 2) + ","
                        + "zoompan=z='min(zoom+0.0006,1.15)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
                        + ":d=" + frames + ":s=" + w + "x" + h + ":fps=" + FPS
                        + ",setsar=1,format=yuv420p";
            } else {
                a.addAll(List.of("-loop", "1", "-framerate", String.valueOf(FPS), "-i", media.toString()));
                vf = scaleFit + ",setsar=1,format=yuv420p";
            }

            if (audio != null) a.addAll(List.of("-i", audio.toString()));
            else a.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo"));

            if (overlay != null) {
                a.addAll(List.of("-loop", "1", "-framerate", String.valueOf(FPS), "-i", overlay.toString()));
                double f = Math.min(0.4, dur / 4);
                a.addAll(List.of("-filter_complex",
                        "[0:v]" + vf + "[bg];[2:v]format=rgba,fade=t=in:st=0:d=" + f + ":alpha=1,fade=t=out:st="
                                + String.format(Locale.ROOT, "%.3f", dur - f) + ":d=" + f + ":alpha=1[ov];"
                                + "[bg][ov]overlay=0:0:format=auto,format=yuv420p[v]",
                        "-map", "[v]", "-map", "1:a:0"));
            } else {
                a.addAll(List.of("-map", "0:v:0", "-map", "1:a:0", "-vf", vf));
            }
            a.addAll(List.of(
                    "-af", "apad",
                    "-t", String.format(Locale.ROOT, "%.3f", dur),
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-r", String.valueOf(FPS),
                    "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "2",
                    "-y", seg));
            ffmpeg.run(a, tmp, ctx);
            Files.move(tmp.resolve(seg), cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String finalName = "video-" + stamp + ".mp4";
        Files.write(tmp.resolve("list.txt"), concat, StandardCharsets.UTF_8);
        ctx.log(String.format("♻ Dùng lại %d/%d cảnh, render mới %d cảnh", reused, concat.size(), built));
        ctx.log("▶ Ghép " + concat.size() + " cảnh → " + finalName);
        Path music = p.musicFile == null ? null : assets.resolve(p.musicFile);
        if (music != null && !Files.exists(music)) {
            ctx.log("  ⚠ không thấy file nhạc nền " + p.musicFile + ", bỏ qua");
            music = null;
        }
        if (music == null) {
            ffmpeg.run(List.of("-f", "concat", "-safe", "0", "-i", "list.txt",
                    "-c", "copy", "-movflags", "+faststart", "-y", out.resolve(finalName).toString()), tmp, ctx);
        } else {
            ffmpeg.run(List.of("-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy", "-y", "joined.mp4"), tmp, ctx);
            // Cut the part before the chosen start first, so looping repeats only the kept part.
            double start = p.musicStart == null ? 0 : p.musicStart;
            if (start > 0.05) {
                Double len = ffmpeg.probeDuration(music);
                if (len != null && start >= len - 1) {
                    ctx.log(String.format(Locale.ROOT, "  ⚠ điểm bắt đầu nhạc (%.1fs) vượt độ dài file (%.1fs), dùng từ đầu", start, len));
                } else {
                    ctx.log(String.format(Locale.ROOT, "▶ Cắt nhạc nền từ giây %.1f", start));
                    ffmpeg.run(List.of("-ss", String.format(Locale.ROOT, "%.3f", start), "-i", music.toString(),
                            "-vn", "-c:a", "aac", "-b:a", "192k", "-y", "music-cut.m4a"), tmp, ctx);
                    music = tmp.resolve("music-cut.m4a");
                }
            }
            double vol = p.musicVolume == null ? 0.25 : Math.max(0, Math.min(1, p.musicVolume));
            double fadeOut = Math.min(2.5, t / 3);
            double fadeIn = Math.min(1.5, t / 4);
            boolean voice = !p.scriptMode && p.scenes.stream().anyMatch(s -> s.audioFile != null);
            String m = String.format(Locale.ROOT,
                    "[1:a]aresample=44100,volume=%.3f,afade=t=in:st=0:d=%.2f,afade=t=out:st=%.2f:d=%.2f[m];",
                    vol, fadeIn, t - fadeOut, fadeOut);
            // With a voice track the music dips while someone is speaking.
            String mix = voice
                    ? "[0:a]aresample=44100,asplit=2[v1][v2];[m][v1]sidechaincompress=threshold=0.02:ratio=6:attack=30:release=600[md];"
                      + "[v2][md]amix=inputs=2:duration=first:normalize=0:dropout_transition=0[a]"
                    : "[0:a]aresample=44100[v];[v][m]amix=inputs=2:duration=first:normalize=0:dropout_transition=0[a]";
            ctx.log(String.format(Locale.ROOT, "▶ Thêm nhạc nền %s (âm lượng %.0f%%%s)", p.musicFile, vol * 100,
                    voice ? ", tự nhỏ lại khi có giọng" : ""));
            ffmpeg.run(List.of("-i", "joined.mp4", "-stream_loop", "-1", "-i", music.toString(),
                    "-filter_complex", m + mix, "-map", "0:v:0", "-map", "[a]",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-t", String.format(Locale.ROOT, "%.3f", t),
                    "-movflags", "+faststart", "-y", out.resolve(finalName).toString()), tmp, ctx);
        }

        Files.writeString(out.resolve("video-" + stamp + ".srt"), srt(p.scenes, timeline), StandardCharsets.UTF_8);
        projects.mutate(projectId, pr -> pr.lastRender = finalName);
        cleanup(tmp);
        pruneCache(cache, used);
        ctx.log(String.format(Locale.ROOT, "✔ Hoàn tất: %s (%.1fs) + phụ đề .srt", finalName, t));
    }

    private static final int SEG_VERSION = 1;

    /** Fingerprint of a file (name, size, modified time) for cache keys; "none" for a missing file. */
    private static String fp(Path f) {
        if (f == null) return "none";
        try {
            return f.getFileName() + ":" + Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return "none";
        }
    }

    private static String sha(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Drops cached segments the render just done no longer uses, and text overlays unused for 3 days. */
    private static void pruneCache(Path cache, Set<String> used) {
        long old = System.currentTimeMillis() - 3L * 86_400_000;
        try (var s = Files.list(cache)) {
            s.filter(f -> {
                String n = f.getFileName().toString();
                if (n.startsWith("seg-")) return !used.contains(n);
                if (n.startsWith("ov-") || n.startsWith("th-")) return f.toFile().lastModified() < old;
                return false;
            }).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    /**
     * The transparent PNG with the scene's script text (script mode), cached by what it depends on.
     * Used by the render and by the editor's preview, so both show exactly the same thing. Null when the
     * scene has no text.
     */
    public Path overlay(Project p, int index) throws Exception {
        Scene s = p.scenes.get(index);
        String text = continued(s.narration, index == p.scenes.size() - 1);
        if (text.isEmpty()) return null;
        Path assets = projects.folder(p.id, "assets");
        Path cache = projects.folder(p.id, "output").resolve("cache");
        Files.createDirectories(cache);
        Path media = s.mediaFile == null ? null : assets.resolve(s.mediaFile);
        if (media != null && !Files.exists(media)) media = null;
        int w = p.width(), h = p.height();
        String key = sha("ov|" + ScriptOverlay.VERSION + "|" + w + "x" + h + "|" + text + "|" + fp(media));
        Path png = cache.resolve("ov-" + key + ".png");
        if (Files.exists(png)) return png;
        BufferedImage thumb = null;
        Path small = cache.resolve("th-" + key + ".png");
        if (media != null) {
            int tw = Math.max(32, w / 10), th = Math.max(32, h / 10);
            ffmpeg.runQuiet(List.of("-i", media.toString(), "-frames:v", "1", "-vf",
                    "scale=" + tw + ":" + th + ":force_original_aspect_ratio=decrease,"
                            + "pad=" + tw + ":" + th + ":(ow-iw)/2:(oh-ih)/2:color=black",
                    "-update", "1", "-y", small.toString()), cache);
            thumb = ImageIO.read(small.toFile());
        }
        ScriptOverlay.render(text, w, h, thumb, png);
        Files.deleteIfExists(small);
        return png;
    }

    /** Overlay for the editor preview, by scene id; null when there is no text. */
    public Path previewOverlay(String projectId, String sceneId) throws Exception {
        Project p = projects.get(projectId);
        for (int i = 0; i < p.scenes.size(); i++) {
            if (p.scenes.get(i).id.equals(sceneId)) return overlay(p, i);
        }
        throw new IllegalArgumentException("Scene not found: " + sceneId);
    }

    private static String srt(List<Scene> scenes, List<double[]> timeline) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (int i = 0; i < scenes.size(); i++) {
            String text = scenes.get(i).narration == null ? "" : scenes.get(i).narration.trim();
            if (text.isEmpty()) continue;
            sb.append(n++).append('\n')
                    .append(ts(timeline.get(i)[0])).append(" --> ").append(ts(timeline.get(i)[1])).append('\n')
                    .append(text).append("\n\n");
        }
        return sb.toString();
    }

    private static String ts(double s) {
        long ms = Math.round(s * 1000);
        return String.format("%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i);
    }

    private static void cleanup(Path dir) {
        try (var s = Files.list(dir)) {
            s.forEach(f -> f.toFile().delete());
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }
}
