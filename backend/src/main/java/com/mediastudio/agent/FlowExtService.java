package com.mediastudio.agent;

import com.mediastudio.job.Job;
import com.mediastudio.job.JobService;
import com.mediastudio.project.CharacterRef;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.project.Scene;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Work queue for the "Media Studio → Flow" Chrome extension. The app queues scenes; the extension (running in the
 * user's own Chrome, already signed in to Google Flow) takes each prompt plus its reference images, generates the
 * picture in Flow, downloads it and sends it back here, where it is assigned to the scene. One queue at a time.
 * A job mirrors the queue so progress shows in the project's task list, and cancelling the job stops the extension.
 */
@Service
public class FlowExtService {
    public static class Ref {
        public String name;
        public String role;
        public String url;
        /** Absolute path on this computer: the extension puts it straight into Flow's file chooser. */
        public String path;
    }

    public static class Task {
        public String sceneId;
        public int n;
        public String prompt;
        public List<Ref> refs = new ArrayList<>();
        /** pending, working, done, failed */
        public String state = "pending";
        public String message;
    }

    public static class Session {
        public String projectId;
        public String projectName;
        public String flowProjectUrl;
        public String aspect;
        public String jobId;
        public boolean stopped;
        public String stopReason;
        /** Seconds the extension waits between scenes. */
        public int pauseSeconds = 5;
        /** Attach reference images in Flow (slow) or send the prompt alone (fast). */
        public boolean useRefs;
        public List<Task> tasks = new ArrayList<>();
        /** Fixed style anchor (⭐ scene or an existing picture), else the first picture made in this queue. */
        transient String anchorFile;
        transient String firstDone;
    }

    private final ProjectService projects;
    private final JobService jobs;
    private final SettingsService settings;
    private volatile Session session;
    private final Object lock = new Object();

    public FlowExtService(ProjectService projects, JobService jobs, SettingsService settings) {
        this.settings = settings;
        this.projects = projects;
        this.jobs = jobs;
    }

    public Job queue(String projectId, boolean onlyMissing, List<String> sceneIds) {
        synchronized (lock) {
            Session cur = session;
            if (cur != null && !cur.stopped && cur.tasks.stream().anyMatch(t -> isOpen(t.state))) {
                throw new IllegalStateException("Extension đang làm hàng đợi của dự án \"" + cur.projectName
                        + "\" — chờ xong hoặc huỷ tác vụ đó trước");
            }
            Project p = projects.get(projectId);
            Path dir = projects.dir(projectId);
            Session s = new Session();
            s.projectId = projectId;
            s.projectName = p.name;
            s.flowProjectUrl = p.flowProjectUrl;
            s.aspect = p.aspect;
            s.useRefs = settings.get().flowExtUseRefs;
            s.pauseSeconds = Math.max(0, Math.min(120, settings.get().flowExtPauseSeconds));
            Set<String> wanted = sceneIds == null ? null : new HashSet<>(sceneIds);
            for (int i = 0; i < p.scenes.size(); i++) {
                Scene sc = p.scenes.get(i);
                if (sc.imagePrompt == null || sc.imagePrompt.isBlank()) continue;
                if (wanted != null ? !wanted.contains(sc.id) : (onlyMissing && sc.mediaFile != null)) continue;
                Task t = new Task();
                t.sceneId = sc.id;
                t.n = i + 1;
                s.tasks.add(t);
            }
            if (s.tasks.isEmpty()) throw new IllegalArgumentException("Không có cảnh nào cần tạo hình");
            Set<String> batch = new HashSet<>();
            s.tasks.forEach(t -> batch.add(t.sceneId));
            s.anchorFile = anchor(p, dir, batch);
            session = s;

            final Session mine = s;
            Job job = jobs.start("flow", "Flow (extension): " + s.tasks.size() + " cảnh", projectId, null, ctx -> {
                ctx.log("Đã xếp " + mine.tasks.size() + " cảnh vào hàng đợi cho extension \"Media Studio → Flow\".");
                ctx.log("Mở Chrome (đã đăng nhập Flow), bấm biểu tượng extension → Bắt đầu.");
                Map<String, String> seen = new HashMap<>();
                while (true) {
                    if (ctx.cancelled()) {
                        stop(mine, "Đã huỷ trong app");
                        return;
                    }
                    for (Task t : mine.tasks) {
                        String key = t.state + "|" + (t.message == null ? "" : t.message);
                        if (key.equals(seen.get(t.sceneId))) continue;
                        seen.put(t.sceneId, key);
                        switch (t.state) {
                            case "working" -> ctx.log("… Cảnh " + t.n + ": đang tạo trong Flow");
                            case "done" -> ctx.log("  ✔ Cảnh " + t.n + " đã có hình" + (t.message == null ? "" : " (" + t.message + ")"));
                            case "failed" -> ctx.log("  ✖ Cảnh " + t.n + ": " + t.message);
                            default -> { }
                        }
                    }
                    boolean open = mine.tasks.stream().anyMatch(t -> isOpen(t.state));
                    if (!open || mine.stopped) break;
                    Thread.sleep(1000);
                }
                long ok = mine.tasks.stream().filter(t -> "done".equals(t.state)).count();
                ctx.log("✔ Xong " + ok + "/" + mine.tasks.size() + " cảnh" + (mine.stopReason == null ? "" : " · dừng: " + mine.stopReason));
                if (ok == 0) throw new IOException(mine.stopReason != null ? mine.stopReason : "Không tạo được cảnh nào bằng Flow");
            });
            s.jobId = job.id;
            return job;
        }
    }

    private static boolean isOpen(String state) {
        return "pending".equals(state) || "working".equals(state);
    }

    /** The current queue with prompts and reference-image links filled in, for the extension. */
    public Map<String, Object> current() {
        Session s = session;
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) {
            out.put("active", false);
            return out;
        }
        Project p = projects.get(s.projectId);
        String anchorFile = s.anchorFile != null ? s.anchorFile : s.firstDone;
        for (Task t : s.tasks) {
            if (!isOpen(t.state)) continue;
            Scene sc = p.scene(t.sceneId);
            if (sc == null) {
                t.state = "failed";
                t.message = "cảnh đã bị xoá";
                continue;
            }
            t.refs = new ArrayList<>();
            if (!s.useRefs) {
                // Fast mode: no picker, no uploads. The prompt itself carries the shared style + character looks.
                t.prompt = AgentPrompts.textOnlyImagePrompt(p, sc);
                continue;
            }
            if (anchorFile != null) t.refs.add(ref(s.projectId, anchorFile, "STYLE ANCHOR"));
            boolean photo = AgentPrompts.scenePhoto(projects.dir(s.projectId), sc) != null;
            if (photo) t.refs.add(ref(s.projectId, sc.refPhoto, "PHOTO REFERENCE"));
            for (CharacterRef c : AgentPrompts.sceneCharacters(p, sc)) t.refs.add(ref(s.projectId, c.file, "CHARACTER " + c.name));
            StringBuilder prompt = new StringBuilder(AgentPrompts.imagePrompt(p, sc));
            if (!t.refs.isEmpty()) {
                prompt.append("\n\nREFERENCE IMAGES attached: ");
                for (int i = 0; i < t.refs.size(); i++) prompt.append(i == 0 ? "" : "; ").append(t.refs.get(i).role);
                prompt.append(". ");
                if (anchorFile != null) {
                    prompt.append("Same artist as the style anchor: same medium, colour palette, line work and lighting, but a"
                            + " completely new composition. ");
                }
                if (photo) prompt.append(AgentPrompts.PHOTO_RULE).append(' ');
                prompt.append("Keep each character's face, hair and outfit exactly as in their reference image.");
            }
            t.prompt = prompt.toString();
        }
        out.put("active", s.tasks.stream().anyMatch(t -> isOpen(t.state)) && !s.stopped);
        out.put("projectId", s.projectId);
        out.put("projectName", s.projectName);
        out.put("flowProjectUrl", p.flowProjectUrl);
        out.put("aspect", s.aspect);
        out.put("pauseSeconds", s.pauseSeconds);
        out.put("stopped", s.stopped);
        out.put("stopReason", s.stopReason);
        out.put("jobId", s.jobId);
        out.put("tasks", s.tasks);
        return out;
    }

    private Ref ref(String projectId, String file, String role) {
        Ref r = new Ref();
        r.name = file;
        r.role = role;
        r.url = "/api/projects/" + projectId + "/files/assets/" + file;
        r.path = projects.dir(projectId).resolve("assets").resolve(file).toAbsolutePath().toString();
        return r;
    }

    private Task task(String sceneId) {
        Session s = session;
        if (s == null) throw new IllegalStateException("Không có hàng đợi Flow nào");
        return s.tasks.stream().filter(t -> t.sceneId.equals(sceneId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Cảnh không nằm trong hàng đợi"));
    }

    public void started(String sceneId) {
        Task t = task(sceneId);
        if (isOpen(t.state)) t.state = "working";
    }

    public void failed(String sceneId, String message) {
        Task t = task(sceneId);
        t.state = "failed";
        t.message = message == null || message.isBlank() ? "lỗi không rõ" : message;
        String low = t.message.toLowerCase();
        if (low.contains("unusual") || low.contains("bất thường") || low.contains("captcha")) {
            stop(session, "Google Flow báo hoạt động bất thường — dừng lại, hãy đợi 30–60 phút");
        }
    }

    /** Stores the downloaded picture for the scene and assigns it. */
    public String result(String sceneId, byte[] data, String originalName) throws IOException {
        Session s = session;
        Task t = task(sceneId);
        if (data == null || data.length < 1000) throw new IOException("Ảnh trống");
        String ext = ".png";
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) ext = ".jpg";
        else if (data.length > 12 && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') ext = ".webp";
        String name = "scene-" + sceneId + "-flow-" + System.currentTimeMillis() + ext;
        Files.write(projects.dir(s.projectId).resolve("assets").resolve(name), GeminiWatermark.clean(data)); // drop the ✦ mark
        projects.mutate(s.projectId, pr -> {
            Scene x = pr.scene(sceneId);
            if (x != null) {
                x.mediaFile = name;
                x.mediaSource = "flow";
            }
        });
        t.state = "done";
        t.message = originalName == null || originalName.isBlank() ? null : originalName;
        if (s.firstDone == null) s.firstDone = name;
        return name;
    }

    /** Same, from a file Chrome saved (usually in Downloads). Only image files under the user's home folder. */
    public String resultFromPath(String sceneId, String path) throws IOException {
        Path f = Path.of(path).toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        String n = f.getFileName().toString().toLowerCase();
        if (!f.startsWith(home) || !(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp"))) {
            throw new IllegalArgumentException("Chỉ nhận file ảnh trong thư mục người dùng");
        }
        if (!Files.isRegularFile(f) || Files.size(f) > 60L * 1024 * 1024) throw new IOException("Không đọc được file " + f);
        return result(sceneId, Files.readAllBytes(f), f.getFileName().toString());
    }

    public void rememberProject(String url) {
        Session s = session;
        if (s == null || url == null) return;
        url = url.trim();
        boolean google = url.startsWith("https://labs.google/") || url.startsWith("https://flow.google.com/");
        if (!google || !url.contains("/project/")) return;
        int cut = url.indexOf("/edit/");
        final String u = cut > 0 ? url.substring(0, cut) : url;
        if (u.equals(projects.get(s.projectId).flowProjectUrl)) return;
        projects.mutate(s.projectId, pr -> pr.flowProjectUrl = u);
        s.flowProjectUrl = u;
    }

    public void stopFromExtension(String reason) {
        stop(session, reason == null || reason.isBlank() ? "Dừng từ extension" : reason);
    }

    private void stop(Session s, String reason) {
        if (s == null) return;
        s.stopped = true;
        if (s.stopReason == null) s.stopReason = reason;
        for (Task t : s.tasks) {
            if (isOpen(t.state)) {
                t.state = "failed";
                t.message = "chưa làm (" + reason + ")";
            }
        }
    }

    /** ⭐ scene picture if it is not being regenerated, else the first existing still picture outside the batch. */
    private static String anchor(Project p, Path dir, Set<String> batch) {
        if (p.styleRefSceneId != null && !batch.contains(p.styleRefSceneId)) {
            Scene x = p.scene(p.styleRefSceneId);
            if (x != null && AgentPrompts.stillImage(dir, x) != null) return x.mediaFile;
        }
        for (Scene x : p.scenes) {
            if (batch.contains(x.id)) continue;
            if (AgentPrompts.stillImage(dir, x) != null) return x.mediaFile;
        }
        return null;
    }
}
