package com.mediastudio.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobService;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.project.Scene;
import com.mediastudio.render.FfmpegService;
import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import com.mediastudio.job.JobContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class VoiceController {
    private final VoiceboxClient voicebox;
    private final VieneuClient vieneu;
    private final ProjectService projects;
    private final JobService jobs;
    private final FfmpegService ffmpeg;
    private final SettingsService settings;

    public VoiceController(VoiceboxClient voicebox, VieneuClient vieneu, ProjectService projects, JobService jobs,
                           FfmpegService ffmpeg, SettingsService settings) {
        this.voicebox = voicebox;
        this.vieneu = vieneu;
        this.projects = projects;
        this.jobs = jobs;
        this.ffmpeg = ffmpeg;
        this.settings = settings;
    }

    private boolean useVieneu() {
        return !"voicebox".equals(settings.get().voiceProvider);
    }

    /** Voices of the active provider (or ?provider=vieneu|voicebox). */
    @GetMapping("/voice/profiles")
    public JsonNode profiles(@RequestParam(required = false) String provider) throws Exception {
        boolean v = provider == null ? useVieneu() : "vieneu".equals(provider);
        if (v) {
            if (!vieneu.reachable()) throw new IllegalStateException("VieNeu server chưa chạy — bấm \"Khởi động VieNeu\"");
            return vieneu.voices();
        }
        return voicebox.profiles();
    }

    /** Starts the VieNeu server (and loads the model) as a job, so the UI can show progress. */
    @PostMapping("/voice/vieneu/start")
    public Job startVieneu() {
        return jobs.start("vieneu-start", "Khởi động VieNeu-TTS", null, null,
                ctx -> vieneu.ensureRunning(ctx::log));
    }

    @GetMapping("/voice/vieneu/status")
    public Map<String, Object> vieneuStatus() {
        return Map.of("running", vieneu.reachable());
    }

    private static final String DEFAULT_PREVIEW_VI =
            "Ngày xửa ngày xưa, ở một làng quê yên bình, có một cậu bé rất ham chơi. Xin chào, đây là giọng đọc thử.";
    private static final String DEFAULT_PREVIEW_EN =
            "Once upon a time, in a quiet little village, there lived a curious boy. Hello, this is a voice preview.";

    /**
     * Speaks a short sample with the given voice of the active provider and returns WAV.
     * Results are cached in <workspace>/voice-previews so replaying is instant.
     */
    // No `produces`: it would stop error responses from being rendered as JSON.
    @GetMapping("/voice/preview")
    public ResponseEntity<byte[]> preview(@RequestParam(required = false) String voice,
                                          @RequestParam(required = false) String text,
                                          @RequestParam(required = false) Double speed) throws Exception {
        AppSettings s = settings.get();
        boolean v = useVieneu();
        String lang = v ? "vi" : (blank(s.voiceLanguage) ? "en" : s.voiceLanguage);
        String sample = !blank(text) ? text.trim()
                : v || "vi".equals(lang) ? DEFAULT_PREVIEW_VI : DEFAULT_PREVIEW_EN;
        if (sample.length() > 400) sample = sample.substring(0, 400);
        if (!v && blank(voice)) throw new IllegalArgumentException("Chọn một voice profile của Voicebox");

        String key = (v ? "vieneu|" : "voicebox|") + (voice == null ? "" : voice) + "|" + sample
                + (v ? "" : "|" + lang + "|" + s.voiceModelSize);
        Path dir = s.workspace().resolve("voice-previews");
        Files.createDirectories(dir);
        Path f = dir.resolve(sha1(key) + ".wav");
        JobContext quiet = new JobContext() {
            private final Job job = new Job();
            public Job job() { return job; }
            public void log(String line) { }
            public void attach(Process process) { }
            public void onCancel(Runnable action) { }
            public boolean cancelled() { return false; }
        };
        if (!Files.exists(f)) {
            byte[] wav = v ? vieneu.speak(sample, voice, quiet) : voicebox.speak(sample, voice, lang, quiet);
            Path tmp = dir.resolve(f.getFileName() + ".tmp");
            Files.write(tmp, wav);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
        // The raw take is cached once; each tempo is derived from it (cheap, no re-synthesis).
        double sp = clampSpeed(speed == null ? s.voiceSpeed : speed);
        if (sp != 1.0) {
            Path fs = dir.resolve(f.getFileName().toString().replace(".wav", "") + "-x" + sp + ".wav");
            if (!Files.exists(fs)) changeTempo(f, fs, sp, dir, quiet);
            f = fs;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .header("Cache-Control", "no-cache")
                .body(Files.readAllBytes(f));
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d).substring(0, 20);
    }

    /**
     * Generates narration audio. Body: {sceneId?, voice?, language?}.
     * Without sceneId, every scene with narration is voiced.
     */
    @PostMapping("/projects/{id}/voice")
    public Job generate(@PathVariable String id, @RequestBody Map<String, String> body) {
        AppSettings s = settings.get();
        boolean v = useVieneu();
        Project p = projects.get(id);
        // Priority: request body → project's own choice → Settings default.
        String voice = !blank(body.get("voice")) ? body.get("voice")
                : !blank(body.get("profileId")) ? body.get("profileId")
                : p.voice != null ? p.voice
                : v ? s.vieneuVoice : s.voiceProfileId;
        String lang = blank(body.get("language")) ? s.voiceLanguage : body.get("language");
        String sceneId = body.get("sceneId");
        if (!v && blank(voice)) throw new IllegalArgumentException("Chưa chọn voice profile của Voicebox (Cài đặt)");

        List<String> targets = new ArrayList<>();
        for (Scene sc : p.scenes) {
            if ((sceneId == null || sceneId.equals(sc.id)) && !blank(sc.narration)) targets.add(sc.id);
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("Không có cảnh nào có lời thoại");

        String label = v ? "VieNeu" : "Voicebox";
        double speed = clampSpeed(p.voiceSpeed != null ? p.voiceSpeed : s.voiceSpeed);
        return jobs.start("voice", label + ": " + targets.size() + " cảnh", id, sceneId, ctx -> {
            if (v) ctx.log("  giọng: " + (blank(voice) ? "mặc định" : voice));
            if (speed != 1.0) ctx.log("  tốc độ đọc: ×" + speed);
            for (int i = 0; i < targets.size(); i++) {
                if (ctx.cancelled()) return;
                String sid = targets.get(i);
                Scene sc = projects.get(id).scene(sid);
                ctx.log("▶ (" + (i + 1) + "/" + targets.size() + ") Tạo giọng cho cảnh " + sid);
                long t0 = System.currentTimeMillis();
                byte[] wav = v ? vieneu.speak(sc.narration, voice, ctx)
                        : voicebox.speak(sc.narration, voice, lang, ctx);
                String name = "scene-" + sid + "-voice.wav";
                Path f = projects.file(id, "assets", name);
                if (speed != 1.0) {
                    Path raw = projects.file(id, "assets", "scene-" + sid + "-voice-raw.wav");
                    Files.write(raw, wav);
                    changeTempo(raw, f, speed, raw.getParent(), ctx);
                    Files.deleteIfExists(raw);
                } else {
                    Files.write(f, wav);
                }
                Double dur = ffmpeg.probeDuration(f);
                projects.mutate(id, pr -> {
                    Scene x = pr.scene(sid);
                    x.audioFile = name;
                    x.audioDuration = dur;
                });
                ctx.log(String.format("  ✔ %s (%s, mất %.1fs)", name,
                        dur == null ? "?" : String.format("%.1fs audio", dur),
                        (System.currentTimeMillis() - t0) / 1000.0));
            }
        });
    }

    /** Rounds to 0.05 steps within ffmpeg atempo's single-pass range. */
    static double clampSpeed(double sp) {
        if (Double.isNaN(sp) || sp <= 0) return 1.0;
        sp = Math.max(0.5, Math.min(2.0, sp));
        return Math.round(sp * 20) / 20.0;
    }

    /** Re-times speech without changing pitch (ffmpeg atempo). */
    private void changeTempo(Path in, Path out, double speed, Path workDir, JobContext ctx) throws Exception {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp.wav");
        ffmpeg.run(List.of("-y", "-i", in.toString(), "-filter:a", "atempo=" + speed, tmp.toString()), workDir, ctx);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
