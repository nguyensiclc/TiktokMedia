package com.mediastudio.sync;

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
        return out;
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
