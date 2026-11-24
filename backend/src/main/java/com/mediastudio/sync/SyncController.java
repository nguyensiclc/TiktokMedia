package com.mediastudio.sync;

import com.mediastudio.project.Project;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Posts list shared between computers through a PostgreSQL database: other computers' posts and duplicate topics. */
@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final PostSync sync;

    public SyncController(PostSync sync) {
        this.sync = sync;
    }

    /**
     * {enabled, database, machine, error, machines: [{machineId, machine, updatedAt, posts: [...]}]} (other computers
     * only). ?refresh=true: retry the database now and send this computer's posts first (after changing Settings).
     */
    @GetMapping("/posts")
    public Map<String, Object> posts(@RequestParam(defaultValue = "false") boolean refresh) {
        if (refresh) {
            sync.retryNow();
            sync.publish();
        } else {
            Thread.ofVirtual().start(sync::publish);
        }
        List<PostSync.MachinePosts> machines = sync.others();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", sync.enabled());
        out.put("database", sync.label());
        out.put("machine", sync.machineName());
        out.put("error", sync.lastError());
        out.put("machines", machines);
        out.put("hidden", sync.hidden());
        return out;
    }

    /** {queue?: true} — takes a post held by another computer: made again from scratch here, posted from here. */
    @PostMapping("/posts/{id}/take")
    public Project take(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean queue = body == null || !Boolean.FALSE.equals(body.get("queue"));
        Project p = sync.take(id, queue);
        Thread.ofVirtual().start(sync::publish);
        return p;
    }

    /** Deletes a post held by another computer, from every computer's list. */
    @DeleteMapping("/posts/{id}")
    public Map<String, Object> remove(@PathVariable String id) {
        sync.remove(id);
        return Map.of("ok", true);
    }

    /** {topics: ["...", ...]} → {"topic": [Match, ...]} for the topics that look already made, here or elsewhere. */
    @PostMapping("/check")
    public Map<String, List<PostSync.Match>> check(@RequestBody Map<String, Object> body) {
        Object t = body.get("topics");
        List<String> topics = new ArrayList<>();
        if (t instanceof List<?> l) for (Object o : l) topics.add(String.valueOf(o).trim());
        else if (t != null) for (String s : String.valueOf(t).split("\\R")) topics.add(s.trim());
        return sync.check(topics);
    }
}
