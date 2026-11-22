package com.mediastudio.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediastudio.system.LanAccessFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public AppSettings get(HttpServletRequest req) {
        AppSettings s = settings.get();
        if (LanAccessFilter.isLocal(req)) return s;
        // Another device (phone): same settings, minus secrets.
        AppSettings copy = mapper.convertValue(s, AppSettings.class);
        copy.tiktokClientSecret = "";
        copy.lanPin = "";
        copy.geminiApiKey = "";
        copy.telegramBotToken = "";
        return copy;
    }

    @PutMapping
    public AppSettings update(@RequestBody AppSettings body, HttpServletRequest req) {
        if (!LanAccessFilter.isLocal(req)) {
            // Other devices got the settings without secrets: blank means "unchanged", not "erase".
            AppSettings cur = settings.get();
            if (blank(body.tiktokClientSecret)) body.tiktokClientSecret = cur.tiktokClientSecret;
            if (blank(body.geminiApiKey)) body.geminiApiKey = cur.geminiApiKey;
            if (blank(body.lanPin)) body.lanPin = cur.lanPin;
            if (blank(body.telegramBotToken)) body.telegramBotToken = cur.telegramBotToken;
        }
        if (body.lanAccess && (body.lanPin == null || body.lanPin.isBlank())) body.lanPin = newPin();
        settings.update(body);
        return get(req);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    String newPin() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
