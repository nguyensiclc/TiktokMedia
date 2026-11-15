package com.mediastudio.job;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Runs long tasks (Claude CLI, Voicebox, FFmpeg) in the background and streams logs over SSE. */
@Service
public class JobService {
    public interface Work {
        void run(JobContext ctx) throws Exception;
    }

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Runnable>> cancelHooks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    public Job start(String type, String title, String projectId, String sceneId, Work work) {
        Job job = new Job();
        job.id = UUID.randomUUID().toString().substring(0, 8);
        job.type = type;
        job.title = title;
        job.projectId = projectId;
        job.sceneId = sceneId;
        jobs.put(job.id, job);

        JobContext ctx = new JobContext() {
            public Job job() { return job; }
            public void log(String line) { append(job, line); }
            public void attach(Process p) { processes.put(job.id, p); }
            public void onCancel(Runnable r) { cancelHooks.computeIfAbsent(job.id, k -> new CopyOnWriteArrayList<>()).add(r); }
            public boolean cancelled() { return cancelled.contains(job.id); }
        };

        futures.put(job.id, pool.submit(() -> {
            try {
                work.run(ctx);
                if (cancelled.contains(job.id)) throw new CancellationException("Đã huỷ");
                finish(job, Job.Status.DONE, null);
            } catch (Throwable t) {
                String msg = cancelled.contains(job.id) ? "Đã huỷ"
                        : t.getMessage() == null ? t.toString() : t.getMessage();
                append(job, "✖ " + msg);
                finish(job, Job.Status.FAILED, msg);
            } finally {
                processes.remove(job.id);
                cancelHooks.remove(job.id);
                futures.remove(job.id);
            }
        }));
        return job;
    }

    public Job get(String id) {
        Job j = jobs.get(id);
        if (j == null) throw new NoSuchElementException("Job not found");
        return j;
    }

    public List<Job> list(String projectId) {
        return jobs.values().stream()
                .filter(j -> projectId == null || projectId.equals(j.projectId))
                .sorted(Comparator.comparing((Job j) -> j.createdAt).reversed())
                .limit(50)
                .toList();
    }

    public void cancel(String id) {
        cancelled.add(id);
        Process p = processes.get(id);
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        List<Runnable> hooks = cancelHooks.get(id);
        if (hooks != null) hooks.forEach(r -> {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        });
        Future<?> f = futures.get(id);
        if (f != null) f.cancel(true);
    }

    public SseEmitter subscribe(String id) {
        Job job = get(id);
        SseEmitter emitter = new SseEmitter(0L);
        synchronized (job) {
            try {
                for (String line : job.logs) emitter.send(SseEmitter.event().name("log").data(line));
                if (job.status != Job.Status.RUNNING) {
                    emitter.send(SseEmitter.event().name("status").data(statusPayload(job)));
                    emitter.complete();
                    return emitter;
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
                return emitter;
            }
            emitters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(emitter);
        }
        emitter.onCompletion(() -> remove(id, emitter));
        emitter.onTimeout(() -> remove(id, emitter));
        emitter.onError(e -> remove(id, emitter));
        return emitter;
    }

    private void append(Job job, String line) {
        synchronized (job) {
            job.logs.add(line);
            if (job.logs.size() > 5000) job.logs.remove(0);
            broadcast(job.id, "log", line);
        }
    }

    private void finish(Job job, Job.Status status, String error) {
        synchronized (job) {
            job.status = status;
            job.error = error;
            job.finishedAt = Instant.now();
            broadcast(job.id, "status", statusPayload(job));
            List<SseEmitter> list = emitters.remove(job.id);
            if (list != null) list.forEach(SseEmitter::complete);
        }
    }

    private Map<String, Object> statusPayload(Job job) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", job.status.name());
        m.put("error", job.error);
        return m;
    }

    private void broadcast(String id, String event, Object data) {
        List<SseEmitter> list = emitters.get(id);
        if (list == null) return;
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(event).data(data));
            } catch (Exception ex) {
                remove(id, e);
            }
        }
    }

    private void remove(String id, SseEmitter e) {
        List<SseEmitter> list = emitters.get(id);
        if (list != null) list.remove(e);
    }
}
