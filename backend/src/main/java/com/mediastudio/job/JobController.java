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

    @GetMapping("/{id}/stream")
    public SseEmitter stream(@PathVariable String id) {
        return jobs.subscribe(id);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable String id) {
        jobs.cancel(id);
    }
}
