package com.mediastudio.autopilot;

import com.mediastudio.job.Job;
import com.mediastudio.job.JobService;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.tiktok.StudioPoster;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** The posts list: add topics with a posting time, change times, post now, retry, pause the worker. */
@RestController
@RequestMapping("/api/queue")
public class AutopilotController {
    private final PublishQueue queue;
    private final ProjectService projects;
    private final JobService jobs;

    public AutopilotController(PublishQueue queue, ProjectService projects, JobService jobs) {
        this.queue = queue;
        this.projects = projects;
        this.jobs = jobs;
    }

    /**
     * {topics: "one per line", niche, scenes?, chibi?, draft?, privacy?, aiLabel?, startAt?: ISO instant, intervalHours?: 24}
     * — one new 9:16 project per topic, posted at startAt, startAt + interval, … (no startAt = only prepared).
     * draft = saved as a TikTok Studio draft as soon as it is ready (no time, nothing published).
     */
    @PostMapping
    public List<Project> add(@RequestBody Map<String, Object> body) {
        List<String> topics = Arrays.stream(String.valueOf(body.getOrDefault("topics", "")).split("\\R"))
                .map(String::trim).filter(t -> !t.isEmpty()).toList();
        if (topics.isEmpty()) throw new IllegalArgumentException("Hãy nhập ít nhất một chủ đề");
        String niche = String.valueOf(body.getOrDefault("niche", "history"));
        if (!Project.NICHES.contains(niche)) niche = "story";
        Integer scenes = body.get("scenes") == null ? null : Integer.parseInt(String.valueOf(body.get("scenes")));
        if (scenes != null) scenes = Math.max(1, Math.min(scenes, Project.MAX_SCENES));
        String privacy = String.valueOf(body.getOrDefault("privacy", "SELF_ONLY"));
        if (!StudioPoster.PRIVACY_KEYS.contains(privacy)) throw new IllegalArgumentException("Quyền xem không hợp lệ");
        boolean ai = !Boolean.FALSE.equals(body.get("aiLabel"));
        boolean chibi = Boolean.parseBoolean(String.valueOf(body.getOrDefault("chibi", false)));
        boolean draft = Boolean.parseBoolean(String.valueOf(body.getOrDefault("draft", false)));
        Instant start = draft || blank(body.get("startAt")) ? null : Instant.parse(String.valueOf(body.get("startAt")));
        double hours = body.get("intervalHours") == null ? 24 : Double.parseDouble(String.valueOf(body.get("intervalHours")));
        Duration step = Duration.ofMinutes(Math.max(10, Math.round(hours * 60)));

        List<Project> out = new ArrayList<>();
        for (int i = 0; i < topics.size(); i++) {
            String topic = topics.get(i);
            Instant at = start == null ? null : start.plus(step.multipliedBy(i));
            String n = niche;
            Integer sc = scenes;
            Project p = projects.create(topic, "9:16");
            out.add(projects.mutate(p.id, pr -> {
                pr.topic = topic;
                pr.niche = n;
                pr.slideRatio = "3:4";
                pr.queueScenes = sc;
                pr.queueChibi = chibi;
                pr.queueDraft = draft;
                pr.queuePrivacy = privacy;
                pr.queueAiLabel = ai;
                pr.scheduledAt = at;
                pr.queueState = "QUEUED";
            }));
        }
        Thread.ofVirtual().start(queue::tick);
        return out;
    }

    /**
     * {action: queue | remove | now | retry, scheduledAt?: ISO instant or "" (no time), privacy?, aiLabel?}
     * queue = put an existing project in the list (or just update its time / settings); now = post as soon as ready.
     */
    @PostMapping("/projects/{id}")
    public Project change(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "queue"));
        Project p0 = projects.get(id);
        if ("remove".equals(action) && id.equals(queue.currentProject())) queue.cancelCurrent();
        Project p = projects.mutate(id, pr -> {
            if (body.containsKey("scheduledAt")) pr.scheduledAt = blank(body.get("scheduledAt")) ? null : Instant.parse(String.valueOf(body.get("scheduledAt")));
            if (body.get("privacy") != null && StudioPoster.PRIVACY_KEYS.contains(String.valueOf(body.get("privacy")))) pr.queuePrivacy = String.valueOf(body.get("privacy"));
            if (body.get("aiLabel") != null) pr.queueAiLabel = !Boolean.FALSE.equals(body.get("aiLabel"));
            switch (action) {
                case "remove" -> { pr.queueState = null; pr.queueError = null; pr.queueStep = null; }
                case "now" -> {
                    pr.scheduledAt = Instant.now();
                    if (!"READY".equals(pr.queueState) && !"PREPARING".equals(pr.queueState) && !"POSTING".equals(pr.queueState)) pr.queueState = "QUEUED";
                    pr.queueError = null;
                }
                case "retry" -> { pr.queueState = "QUEUED"; pr.queueError = null; }
                default -> { if (pr.queueState == null) pr.queueState = "QUEUED"; }
            }
        });
        Thread.ofVirtual().start(queue::tick);
        return p;
    }

    /** {paused, current: {jobId, projectId, title, log[]} | null} */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paused", queue.paused());
        Job c = queue.current();
        if (c != null) {
            List<String> log;
            synchronized (c) { // JobService appends under the job's lock
                log = List.copyOf(c.logs.subList(Math.max(0, c.logs.size() - 60), c.logs.size()));
            }
            m.put("current", Map.of("jobId", c.id, "projectId", c.projectId, "title", c.title, "log", log));
        } else {
            m.put("current", null);
        }
        return m;
    }

    /** {paused: true|false} */
    @PostMapping("/pause")
    public Map<String, Object> pause(@RequestBody Map<String, Object> body) throws Exception {
        queue.setPaused(Boolean.TRUE.equals(body.get("paused")));
        if (!queue.paused()) Thread.ofVirtual().start(queue::tick);
        return Map.of("paused", queue.paused());
    }

    /** Stops the item being worked on. */
    @PostMapping("/stop")
    public Map<String, Object> stop() {
        queue.cancelCurrent();
        return Map.of("ok", true);
    }

    private static boolean blank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }
}
