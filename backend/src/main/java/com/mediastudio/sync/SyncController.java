package com.mediastudio.sync;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Posts list shared between computers through a synced folder: other computers' posts and duplicate topics. */
@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final PostSync sync;

    public SyncController(PostSync sync) {
        this.sync = sync;
    }

    /** {enabled, dir, machine, error, machines: [{machine, updatedAt, posts: [...]}]} (other computers only). */
    @GetMapping("/posts")
    public Map<String, Object> posts() {
        sync.publish();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", sync.enabled());
        out.put("dir", sync.enabled() ? sync.dir().toString() : "");
        out.put("machine", sync.machineName());
        out.put("error", sync.lastError());
        out.put("machines", sync.others());
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
