package com.mediastudio.autopilot;

import com.mediastudio.job.Job;
import com.mediastudio.job.JobService;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.settings.SettingsService;
import com.mediastudio.sync.PostSync;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Background worker for the posts list. One item at a time (Flow and the TikTok Chrome profile can only do one
 * thing at once). Every ~20 s, when nothing is running:
 * <ol>
 *   <li>a READY post whose time has come is published (a draft post: right away, saved as a TikTok draft);</li>
 *   <li>otherwise the next QUEUED post (earliest time first) is prepared, ahead of its time, and becomes READY.</li>
 * </ol>
 * States: QUEUED → PREPARING → READY → POSTING → POSTED, or FAILED (with the error) at any step.
 */
@Service
public class PublishQueue {
    public static final Set<String> ACTIVE = Set.of("QUEUED", "PREPARING", "READY", "POSTING");

    private final ProjectService projects;
    private final JobService jobs;
    private final AutopilotService autopilot;
    private final SettingsService settings;
    private final PostSync sync;
    private volatile Job current;
    private volatile String currentProject;
    private volatile boolean paused;
    private volatile boolean stop;

    public PublishQueue(ProjectService projects, JobService jobs, AutopilotService autopilot, SettingsService settings, PostSync sync) {
        this.sync = sync;
        this.projects = projects;
        this.jobs = jobs;
        this.autopilot = autopilot;
        this.settings = settings;
    }

    @PostConstruct
    void start() {
        paused = Files.exists(pauseFile());
        // A restart cut off whatever was running.
        for (Project p : projects.list()) {
            if ("PREPARING".equals(p.queueState)) {
                projects.mutate(p.id, pr -> { pr.queueState = "QUEUED"; pr.queueStep = null; });
            } else if ("POSTING".equals(p.queueState)) {
                projects.mutate(p.id, pr -> {
                    pr.queueState = "FAILED";
                    pr.queueError = "Đang đăng thì app tắt: kiểm tra trên TikTok xem bài đã lên chưa trước khi đăng lại";
                });
            }
        }
        Thread.ofVirtual().name("publish-queue").start(() -> {
            while (!stop) {
                try {
                    Thread.sleep(20_000);
                    tick();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) {
                    // a bad project file must not kill the worker
                }
            }
        });
    }

    @PreDestroy
    void shutdown() {
        stop = true;
    }

    private Path pauseFile() {
        return settings.get().workspace().resolve("queue-paused");
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean v) throws Exception {
        paused = v;
        if (v) Files.writeString(pauseFile(), "1");
        else Files.deleteIfExists(pauseFile());
    }

    public Job current() {
        Job c = current;
        return c != null && c.status == Job.Status.RUNNING ? c : null;
    }

    public String currentProject() {
        return current() == null ? null : currentProject;
    }

    /** Runs the next thing to do, if any and nothing is running. Also called right after a change in the list. */
    public synchronized void tick() {
        if (paused || current() != null) return;
        Instant now = Instant.now();
        // posts another computer took over or deleted are not made or posted here
        Set<String> hidden = sync.hidden();
        List<Project> all = projects.list().stream().filter(p -> !hidden.contains(p.id)).toList();
        Optional<Project> due = all.stream()
                .filter(p -> "READY".equals(p.queueState)
                        && (p.queueDraft || (p.scheduledAt != null && !p.scheduledAt.isAfter(now))))
                .min(Comparator.comparing((Project p) -> p.scheduledAt == null ? Instant.MIN : p.scheduledAt));
        if (due.isPresent()) {
            String id = due.get().id;
            if (!sync.stillMine(id)) return;
            set(id, "POSTING", null);
            currentProject = id;
            current = jobs.start("autopilot", (due.get().queueDraft ? "Lưu nháp: " : "Đăng: ") + due.get().name, id, null, ctx -> {
                try {
                    autopilot.publish(id, ctx);
                    projects.mutate(id, pr -> { pr.queueState = "POSTED"; pr.queueStep = null; pr.queueError = null; });
                } catch (Exception e) {
                    set(id, "FAILED", e.getMessage());
                    throw e;
                }
            });
            return;
        }
        Optional<Project> next = all.stream()
                .filter(p -> "QUEUED".equals(p.queueState))
                .min(Comparator.comparing((Project p) -> p.scheduledAt == null ? Instant.MAX : p.scheduledAt));
        if (next.isPresent()) {
            String id = next.get().id;
            if (!sync.stillMine(id)) return;
            set(id, "PREPARING", null);
            currentProject = id;
            current = jobs.start("autopilot", "Chuẩn bị: " + next.get().name, id, null, ctx -> {
                try {
                    autopilot.prepare(id, ctx);
                    projects.mutate(id, pr -> { pr.queueState = "READY"; pr.queueStep = null; pr.queueError = null; });
                    Thread.ofVirtual().start(() -> { try { Thread.sleep(3000); } catch (InterruptedException ignored) { } tick(); });
                    Project done = projects.get(id);
                    ctx.log("✔ Sẵn sàng" + (done.queueDraft ? ", lưu nháp TikTok ngay"
                            : done.scheduledAt == null ? " (chưa hẹn giờ đăng)" : ", chờ đến giờ đăng"));
                } catch (Exception e) {
                    set(id, "FAILED", e.getMessage());
                    if (AutopilotService.refused(e)) {
                        // Other queued posts would hit Google again right away: pause until the user resumes.
                        setPaused(true);
                        ctx.log("⏸ Đã tạm dừng danh sách đăng vì Google Flow báo hoạt động bất thường.");
                    }
                    throw e;
                }
            });
        }
    }

    private void set(String id, String state, String error) {
        projects.mutate(id, pr -> {
            pr.queueState = state;
            pr.queueError = error;
            if (!"PREPARING".equals(state) && !"POSTING".equals(state)) pr.queueStep = null;
        });
    }

    /** Stops the running item (it goes back to QUEUED / READY so it runs again later, or stays FAILED). */
    public void cancelCurrent() {
        Job c = current();
        if (c != null) jobs.cancel(c.id);
    }
}
