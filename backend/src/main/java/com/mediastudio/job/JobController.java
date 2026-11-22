package com.mediastudio.job;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public List<Job> list(@RequestParam(required = false) String projectId) {
        return jobs.list(projectId);
    }

    @GetMapping("/{id}")
    public Job get(@PathVariable String id) {
        return jobs.get(id);
    }

    /** Last log lines of a job (the chat assistant reads these after waiting on a job). */
    @GetMapping("/{id}/log")
    public List<String> log(@PathVariable String id, @RequestParam(defaultValue = "40") int tail) {
        Job j = jobs.get(id);
        synchronized (j) {
            int n = Math.max(1, Math.min(tail, 500));
            return List.copyOf(j.logs.subList(Math.max(0, j.logs.size() - n), j.logs.size()));
        }
    }

    @GetMapping("/{id}/stream")
    public SseEmitter stream(@PathVariable String id) {
        return jobs.subscribe(id);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable String id) {
        jobs.cancel(id);
    }
}
