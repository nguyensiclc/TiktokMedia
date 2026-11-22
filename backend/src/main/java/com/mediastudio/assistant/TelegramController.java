package com.mediastudio.assistant;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/telegram")
public class TelegramController {
    private final TelegramBot bot;

    public TelegramController(TelegramBot bot) {
        this.bot = bot;
    }

    /** {enabled, running, bot (username), error, allowed (count), chats (count), fromEnv} */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return bot.status();
    }

    /** {projectId, folder: assets|output, name | names[], caption?} — sends project files to the Telegram chat(s). */
    @PostMapping("/send")
    @SuppressWarnings("unchecked")
    public Map<String, Object> send(@RequestBody Map<String, Object> body) throws IOException {
        List<String> names = body.get("names") instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                : body.get("name") == null ? List.of() : List.of(String.valueOf(body.get("name")));
        return bot.sendFiles(String.valueOf(body.get("projectId")), String.valueOf(body.getOrDefault("folder", "output")),
                names, body.get("caption") == null ? null : String.valueOf(body.get("caption")));
    }
}
