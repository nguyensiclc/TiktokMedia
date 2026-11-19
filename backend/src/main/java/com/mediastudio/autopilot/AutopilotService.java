package com.mediastudio.autopilot;

import com.mediastudio.agent.AgentService;
import com.mediastudio.agent.FlowApiService;
import com.mediastudio.agent.FlowExtService;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobContext;
import com.mediastudio.job.JobService;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.project.Scene;
import com.mediastudio.render.RenderService;
import com.mediastudio.settings.SettingsService;
import com.mediastudio.tiktok.StudioPoster;
import com.mediastudio.tiktok.TiktokController;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The steps of one post, each one the app's normal job (visible in the project), started and awaited in turn:
 * prepare = script → Flow pictures → 3:4 swipe photos → TikTok caption (steps already done are skipped);
 * publish = TikTok Studio post. {@link PublishQueue} decides when each runs.
 */
@Service
public class AutopilotService {
    /** Missing pictures above this share stop the post before anything is published. */
    static final double MAX_MISSING = 0.15;

    private final JobService jobs;
    private final ProjectService projects;
    private final AgentService agent;
    private final FlowApiService flowApi;
    private final FlowExtService flowExt;
    private final SettingsService settings;
    private final RenderService render;
    private final TiktokController tiktok;
    private final StudioPoster studio;

    public AutopilotService(JobService jobs, ProjectService projects, AgentService agent, FlowApiService flowApi,
                            FlowExtService flowExt, SettingsService settings, RenderService render,
                            TiktokController tiktok, StudioPoster studio) {
        this.jobs = jobs;
        this.projects = projects;
        this.agent = agent;
        this.flowApi = flowApi;
        this.flowExt = flowExt;
        this.settings = settings;
        this.render = render;
        this.tiktok = tiktok;
        this.studio = studio;
    }

    /** Script, pictures, swipe photos and caption, skipping what the project already has. */
    public void prepare(String id, JobContext ctx) throws Exception {
        Project p = projects.get(id);
        boolean hasScript = p.scenes.stream().anyMatch(s -> s.narration != null && !s.narration.isBlank());
        if (!hasScript) {
            String topic = p.topic == null || p.topic.isBlank() ? p.name : p.topic;
            int n = p.queueScenes != null ? p.queueScenes : "history".equals(p.niche) ? 34 : "facts".equals(p.niche) ? 7 : 12;
            step(id, ctx, "Kịch bản", () -> agent.storyboard(id, topic, n, "Vietnamese", p.queueChibi ? Project.CHIBI_STYLE : "", false, p.niche));
        } else {
            ctx.log("  ✔ Kịch bản: đã có");
        }
        int total = projects.get(id).scenes.size();
        if (!"extension".equals(settings.get().flowEngine)
                && projects.get(id).characters.stream().anyMatch(c -> c.name != null && !c.name.isBlank() && c.file == null)) {
            try {
                step(id, ctx, "Chân dung nhân vật", () -> flowApi.portraits(id, null));
            } catch (IOException e) {
                if (ctx.cancelled() || refused(e)) throw e;
                ctx.log("    ⚠ " + e.getMessage() + " — vẫn tạo hình các cảnh");
            }
        }

        // Pictures: a second pass for the scenes the first one missed.
        for (int pass = 1; pass <= 2 && missing(id) > 0; pass++) {
            boolean ext = "extension".equals(settings.get().flowEngine);
            String label = "Tạo hình Flow" + (pass > 1 ? " (lần " + pass + ", còn " + missing(id) + " cảnh)" : "");
            try {
                step(id, ctx, label, () -> ext ? flowExt.queue(id, true, null) : flowApi.scenes(id, null, true));
            } catch (IOException e) {
                if (ctx.cancelled() || refused(e)) throw e; // never retry a Google refusal
                ctx.log("    ⚠ " + e.getMessage());
            }
        }
        int miss = missing(id);
        if (miss > Math.max(1, total * MAX_MISSING)) throw new IOException("còn " + miss + "/" + total + " cảnh chưa có hình");
        if (miss > 0) ctx.log("    ⚠ " + miss + " cảnh không có hình, sẽ bỏ qua khi xuất ảnh");
        if (miss == 0 && total > 0) ctx.log("  ✔ Hình: đủ " + total + " cảnh");

        if (projects.get(id).slideFiles.isEmpty()) {
            step(id, ctx, "Xuất danh sách ảnh 3:4", () -> render.slides(id, true, null, null, "3:4"));
        } else {
            ctx.log("  ✔ Danh sách ảnh: đã có");
        }
        String cap = projects.get(id).tiktokCaption;
        if (cap == null || cap.isBlank()) {
            step(id, ctx, "Viết caption", () -> tiktok.caption(id, Map.of()));
        } else {
            ctx.log("  ✔ Caption: đã có");
        }
    }

    /** TikTok Studio post with the project's queue settings. */
    public void publish(String id, JobContext ctx) throws Exception {
        Project p = projects.get(id);
        step(id, ctx, "Đăng lên TikTok Studio", () -> studio.post(id, p.queuePrivacy, null, p.queueAiLabel));
        projects.mutate(id, pr -> pr.postedAt = Instant.now());
    }

    /** Google Flow refused because of "unusual activity". */
    static boolean refused(Throwable e) {
        String m = e.getMessage();
        return m != null && (m.contains("hoạt động bất thường") || m.contains("FLOW_REFUSED"));
    }

    private int missing(String id) {
        int n = 0;
        for (Scene s : projects.get(id).scenes) {
            if (s.imagePrompt != null && !s.imagePrompt.isBlank() && s.mediaFile == null) n++;
        }
        return n;
    }

    /** Starts a job, waits for it, passes cancellation on, and fails when the job fails. */
    private void step(String id, JobContext ctx, String label, Supplier<Job> start) throws Exception {
        ctx.log("  ▶ " + label);
        projects.mutate(id, pr -> pr.queueStep = label);
        Job j = start.get();
        long t0 = System.currentTimeMillis();
        while (j.status == Job.Status.RUNNING) {
            if (ctx.cancelled()) {
                jobs.cancel(j.id);
                throw new IOException("Đã huỷ");
            }
            Thread.sleep(2000);
        }
        long sec = (System.currentTimeMillis() - t0) / 1000;
        if (j.status == Job.Status.FAILED) throw new IOException(label + ": " + j.error);
        ctx.log("    ✔ " + label + " (" + (sec / 60) + " phút " + (sec % 60) + " giây)");
    }
}
