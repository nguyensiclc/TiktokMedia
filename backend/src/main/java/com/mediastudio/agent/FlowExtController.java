package com.mediastudio.agent;

import com.mediastudio.job.Job;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Endpoints for the "Media Studio → Flow" Chrome extension (local computer only; see LanAccessFilter). */
@RestController
public class FlowExtController {
    private final FlowExtService ext;
    private final FlowApiService flowApi;
    private final com.mediastudio.settings.SettingsService settings;

    public FlowExtController(FlowExtService ext, FlowApiService flowApi, com.mediastudio.settings.SettingsService settings) {
        this.ext = ext;
        this.flowApi = flowApi;
        this.settings = settings;
    }

    /**
     * {onlyMissing?: true, sceneIds?: [...]} — the "Flow" buttons: the local image service (default) or the extension
     * queue, per Settings → flowEngine. The returned job's type says which ("flow-api" or "flow").
     */
    @PostMapping("/api/projects/{id}/flow-gen")
    @SuppressWarnings("unchecked")
    public Job generate(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean onlyMissing = body == null || !Boolean.FALSE.equals(body.get("onlyMissing"));
        List<String> ids = body == null ? null : (List<String>) body.get("sceneIds");
        if ("extension".equals(settings.get().flowEngine)) return ext.queue(id, onlyMissing, ids);
        return flowApi.scenes(id, ids, onlyMissing);
    }

    /** Is the local image service up? {ok, url, error?} */
    @GetMapping("/api/flow-api/health")
    public Map<String, Object> apiHealth() {
        String bad = flowApi.health();
        return bad == null ? Map.of("ok", true, "url", settings.get().flowApiUrl)
                : Map.of("ok", false, "url", settings.get().flowApiUrl, "error", bad);
    }

    /** {onlyMissing?: true, sceneIds?: [...]} — put scenes in the extension's queue. */
    @PostMapping("/api/projects/{id}/flow-ext")
    @SuppressWarnings("unchecked")
    public Job queue(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean onlyMissing = body == null || !Boolean.FALSE.equals(body.get("onlyMissing"));
        List<String> ids = body == null ? null : (List<String>) body.get("sceneIds");
        return ext.queue(id, onlyMissing, ids);
    }

    @GetMapping("/api/flow-ext/session")
    public Map<String, Object> session() {
        return ext.current();
    }

    @GetMapping("/api/flow-ext/ping")
    public Map<String, Object> ping() {
        // Folder the extension asks Chrome to save Flow's downloads into, so Chrome never shows its "Save as" dialog.
        java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home"), "MediaStudio", "flow-downloads");
        try { java.nio.file.Files.createDirectories(dir); } catch (IOException ignored) { }
        return Map.of("app", "Media Studio", "ok", true, "downloadDir", dir.toString());
    }

    @PostMapping("/api/flow-ext/tasks/{sceneId}/start")
    public Map<String, Object> start(@PathVariable String sceneId) {
        ext.started(sceneId);
        return Map.of("ok", true);
    }

    /** {message} */
    @PostMapping("/api/flow-ext/tasks/{sceneId}/fail")
    public Map<String, Object> fail(@PathVariable String sceneId, @RequestBody(required = false) Map<String, String> body) {
        ext.failed(sceneId, body == null ? null : body.get("message"));
        return Map.of("ok", true);
    }

    /** multipart "file" — the picture bytes. */
    @PostMapping("/api/flow-ext/tasks/{sceneId}/result")
    public Map<String, Object> result(@PathVariable String sceneId, @RequestParam("file") MultipartFile file) throws IOException {
        return Map.of("file", ext.result(sceneId, file.getBytes(), file.getOriginalFilename()));
    }

    /** {path} — a file Chrome downloaded. */
    @PostMapping("/api/flow-ext/tasks/{sceneId}/result-path")
    public Map<String, Object> resultPath(@PathVariable String sceneId, @RequestBody Map<String, String> body) throws IOException {
        return Map.of("file", ext.resultFromPath(sceneId, body.get("path")));
    }

    /** {url} — the Flow project page the extension works in. */
    @PostMapping("/api/flow-ext/project")
    public Map<String, Object> project(@RequestBody Map<String, String> body) {
        ext.rememberProject(body.get("url"));
        return Map.of("ok", true);
    }

    /** {reason} */
    @PostMapping("/api/flow-ext/stop")
    public Map<String, Object> stop(@RequestBody(required = false) Map<String, String> body) {
        ext.stopFromExtension(body == null ? null : body.get("reason"));
        return Map.of("ok", true);
    }
}
