package com.mediastudio.agent;

import com.mediastudio.job.Job;
import com.mediastudio.render.RenderService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{id}")
public class AgentController {
    private final AgentService agent;
    private final RenderService render;
    private final GeminiImageService gemini;

    public AgentController(AgentService agent, RenderService render, GeminiImageService gemini) {
        this.agent = agent;
        this.render = render;
        this.gemini = gemini;
    }

    /** Gemini API image for one scene. */
    @PostMapping("/scenes/{sceneId}/gemini")
    public Job gemini(@PathVariable String id, @PathVariable String sceneId) {
        return gemini.scene(id, sceneId);
    }

    /** {onlyMissing?: true} — Gemini API images for many scenes at once. */
    @PostMapping("/gemini-all")
    public Job geminiAll(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean onlyMissing = body == null || !Boolean.FALSE.equals(body.get("onlyMissing"));
        return gemini.all(id, onlyMissing);
    }

    /** {topic, scenes, language, style, append, niche?: story|facts} */
    @PostMapping("/storyboard")
    public Job storyboard(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String topic = String.valueOf(body.getOrDefault("topic", "")).trim();
        if (topic.isEmpty()) throw new IllegalArgumentException("Hãy nhập chủ đề");
        int scenes = Integer.parseInt(String.valueOf(body.getOrDefault("scenes", 6)));
        return agent.storyboard(id, topic, Math.max(1, Math.min(scenes, 40)),
                String.valueOf(body.getOrDefault("language", "Vietnamese")),
                String.valueOf(body.getOrDefault("style", "")),
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("append", false))),
                body.get("niche") == null ? null : String.valueOf(body.get("niche")));
    }

    /** {note?, storyboard?: true} — read the source images, add characters, then (by default) write the script. */
    @PostMapping("/analyze")
    public Job analyze(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null ? "" : String.valueOf(body.getOrDefault("note", ""));
        boolean then = body == null || !Boolean.FALSE.equals(body.get("storyboard"));
        String ai = body == null ? "claude" : String.valueOf(body.getOrDefault("ai", "claude"));
        String model = body == null || body.get("model") == null ? null : String.valueOf(body.get("model"));
        return agent.analyze(id, note, then, ai, model);
    }

    /** {mediaType: image|video} */
    @PostMapping("/scenes/{sceneId}/flow")
    public Job flow(@PathVariable String id, @PathVariable String sceneId, @RequestBody(required = false) Map<String, String> body) {
        String type = body == null ? "image" : body.getOrDefault("mediaType", "image");
        return agent.flow(id, sceneId, type);
    }

    /** {mediaType: image|video, onlyMissing?: true} — Flow for every scene without a visual. */
    @PostMapping("/flow-all")
    public Job flowAll(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String type = body == null ? "image" : String.valueOf(body.getOrDefault("mediaType", "image"));
        boolean onlyMissing = body == null || !Boolean.FALSE.equals(body.get("onlyMissing"));
        return agent.flowAll(id, type, onlyMissing);
    }

    @PostMapping("/scenes/{sceneId}/canva")
    public Job canva(@PathVariable String id, @PathVariable String sceneId) {
        return agent.canva(id, sceneId);
    }

    /** {onlyMissing: true|false} — every scene in order, sharing one style reference. */
    @PostMapping("/canva-all")
    public Job canvaAll(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean onlyMissing = body == null || !Boolean.FALSE.equals(body.get("onlyMissing"));
        return agent.canvaAll(id, onlyMissing);
    }

    /** {prompt} */
    @PostMapping("/agent")
    public Job custom(@PathVariable String id, @RequestBody Map<String, String> body) {
        String prompt = body.getOrDefault("prompt", "").trim();
        if (prompt.isEmpty()) throw new IllegalArgumentException("Prompt trống");
        return agent.custom(id, prompt);
    }

    @PostMapping("/render")
    public Job render(@PathVariable String id) {
        return render.render(id);
    }

    /** The script text of a scene as a transparent frame-sized PNG (exactly what the render draws); 204 if none. */
    @GetMapping("/scenes/{sceneId}/overlay.png")
    public org.springframework.http.ResponseEntity<byte[]> overlay(@PathVariable String id, @PathVariable String sceneId) throws Exception {
        java.nio.file.Path png = render.previewOverlay(id, sceneId);
        if (png == null) return org.springframework.http.ResponseEntity.noContent().build();
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .header("Cache-Control", "private, max-age=3600")
                .body(java.nio.file.Files.readAllBytes(png));
    }

    /** {withText?: true, coverTitle?, coverTeaser?} (cover text: null = keep, blank = Claude rewrites it) — exports one JPG per scene (+ zip) for a swipe-through photo post. */
    @PostMapping("/slides")
    public Job slides(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean withText = body == null || !Boolean.FALSE.equals(body.get("withText"));
        String title = body == null || body.get("coverTitle") == null ? null : body.get("coverTitle").toString();
        String teaser = body == null || body.get("coverTeaser") == null ? null : body.get("coverTeaser").toString();
        return render.slides(id, withText, title, teaser);
    }
}
