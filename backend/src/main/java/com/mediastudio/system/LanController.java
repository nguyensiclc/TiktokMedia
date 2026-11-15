package com.mediastudio.system;

import com.mediastudio.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Info for the "use on your phone" card in Settings. Reachable from this computer only (see LanAccessFilter). */
@RestController
@RequestMapping("/api/lan")
public class LanController {
    private final SettingsService settings;
    private final SecureRandom random = new SecureRandom();

    public LanController(SettingsService settings) {
        this.settings = settings;
    }

    /** {enabled, pin, urls: ["http://192.168.1.5:8787", ...]} */
    @GetMapping("/info")
    public Map<String, Object> info(HttpServletRequest req) {
        var s = settings.get();
        List<String> urls = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && LanAccessFilter.isPrivate(a.getHostAddress())) {
                        urls.add("http://" + a.getHostAddress() + ":" + req.getLocalPort());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Map.of("enabled", s.lanAccess, "pin", s.lanPin == null ? "" : s.lanPin, "urls", urls);
    }

    /** New PIN (also signs out phones that used the old one the next time they enter it). */
    @PostMapping("/pin")
    public Map<String, Object> newPin(HttpServletRequest req) {
        var s = settings.get();
        s.lanPin = String.format("%06d", random.nextInt(1_000_000));
        settings.update(s);
        return info(req);
    }
}
