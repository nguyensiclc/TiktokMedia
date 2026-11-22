package com.mediastudio.agent;

import com.mediastudio.job.Job;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Drama series written in the app: ideas → outline + episode 1 → later episodes. */
@RestController
@RequestMapping("/api/projects/{id}/drama")
public class DramaController {
    private final DramaService drama;

    public DramaController(DramaService drama) {
        this.drama = drama;
    }

    /** {genre?, note?} — three new ideas. */
    @PostMapping("/ideas")
    public Job ideas(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return drama.ideas(id, str(body, "genre"), str(body, "note"));
    }

    /** {index} of a proposed idea, or {logline} typed by the user — outline of the series, then episode 1. */
    @PostMapping("/series")
    public Job series(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object i = body.get("index");
        return drama.series(id, i == null ? null : Integer.valueOf(String.valueOf(i)), str(body, "logline"));
    }

    /** {n} — writes or rewrites that episode's screenplay. */
    @PostMapping("/episode")
    public Job episode(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return drama.episode(id, Integer.parseInt(String.valueOf(body.getOrDefault("n", 1))));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
