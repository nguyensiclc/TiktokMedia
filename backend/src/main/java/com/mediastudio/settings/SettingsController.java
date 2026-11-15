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
        return copy;
    }

    @PutMapping
    public AppSettings update(@RequestBody AppSettings body) {
        if (body.lanAccess && (body.lanPin == null || body.lanPin.isBlank())) body.lanPin = newPin();
        return settings.update(body);
    }

    String newPin() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
