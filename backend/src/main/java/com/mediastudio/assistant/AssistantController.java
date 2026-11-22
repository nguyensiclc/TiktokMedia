package com.mediastudio.assistant;

import com.mediastudio.job.Job;
import com.mediastudio.system.LanAccessFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/** Chat window ↔ assistant. The reply streams over the usual job SSE: /api/jobs/{jobId}/stream. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    /** {sessionId, messages[], jobId (reply being written) | null, costUsd} */
    @GetMapping("/chat")
    public AssistantService.Chat chat() {
        return assistant.chat();
    }

    /** {text, projectId?} — projectId = the project open in the app, so "this video" means something. */
    @PostMapping("/chat")
    public Job send(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String text = body.getOrDefault("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Tin nhắn trống");
        if (text.length() > 20000) throw new IllegalArgumentException("Tin nhắn quá dài");
        // Devices that only have the PIN get the same limits through the assistant as through the UI.
        boolean restricted = !LanAccessFilter.isLocal(req) && !LanAccessFilter.remoteAdmin();
        return assistant.send(text, body.get("projectId"), restricted);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel() {
        assistant.cancel();
        return Map.of("ok", true);
    }

    /** New conversation. */
    @DeleteMapping("/chat")
    public AssistantService.Chat reset() throws IOException {
        return assistant.reset();
    }
}
