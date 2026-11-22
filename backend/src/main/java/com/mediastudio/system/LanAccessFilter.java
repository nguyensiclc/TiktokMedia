package com.mediastudio.system;

import com.mediastudio.settings.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server listens on all interfaces so a phone on the same Wi-Fi can reach it, but only this computer
 * is trusted by default. Any other device is refused unless "LAN access" is on in Settings, comes from a private
 * network address, and has entered the PIN (then it gets a random session cookie). Even then it cannot change
 * settings, sign in/out of services, run free-form agent commands or read secrets.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LanAccessFilter extends OncePerRequestFilter {
    static final String COOKIE = "ms_lan";
    private static final long SESSION_DAYS = 30;

    private final SettingsService settings;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();       // ip -> [count]
    private final Map<String, Instant> locked = new ConcurrentHashMap<>();    // ip -> until

    public LanAccessFilter(SettingsService settings) {
        this.settings = settings;
    }

    /** True when the request comes from this computer. */
    public static boolean isLocal(HttpServletRequest req) {
        try {
            return InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Server mode (MEDIASTUDIO_REMOTE_ADMIN=true, e.g. a Mac with no screen): devices that entered the PIN may do
     * everything this computer can (settings, sign-ins, free-form commands).
     */
    public static boolean remoteAdmin() {
        return "true".equalsIgnoreCase(System.getenv("MEDIASTUDIO_REMOTE_ADMIN"));
    }

    /** PIN fixed by the environment (MEDIASTUDIO_LAN_PIN): turns access from other devices on without Settings. */
    public static String envPin() {
        String p = System.getenv("MEDIASTUDIO_LAN_PIN");
        return p == null || p.isBlank() ? null : p.trim();
    }

    static boolean isPrivate(String addr) {
        try {
            InetAddress a = InetAddress.getByName(addr);
            if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
            byte[] b = a.getAddress();
            // 100.64.0.0/10: carrier-grade NAT, used by Tailscale
            return b.length == 4 && (b[0] & 255) == 100 && (b[1] & 0xC0) == 64;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (isLocal(req)) {
            chain.doFilter(req, res);
            return;
        }
        String ip = req.getRemoteAddr();
        var s = settings.get();
        String pin = envPin() != null ? envPin() : s.lanPin;
        boolean on = envPin() != null || s.lanAccess;
        if (!on || pin == null || pin.isBlank() || !isPrivate(ip)) {
            deny(res, 403, "Truy cập từ thiết bị khác đang tắt. Bật \"Dùng trên điện thoại\" trong Cài đặt trên máy tính.");
            return;
        }
        String path = req.getRequestURI();

        if ("/api/lan/login".equals(path) && "POST".equals(req.getMethod())) {
            login(req, res, ip, pin);
            return;
        }
        if (!authenticated(req)) {
            if (path.startsWith("/api/")) deny(res, 401, "Cần nhập mã PIN");
            else loginPage(res);
            return;
        }
        if (remoteAdmin()) {
            chain.doFilter(req, res);
            return;
        }
        // Things a phone must never do, even with the PIN.
        String m = req.getMethod();
        boolean writes = !"GET".equals(m) && !"HEAD".equals(m);
        if (path.startsWith("/api/system/") || path.startsWith("/api/lan/") || path.startsWith("/api/flow-ext/")
                || path.matches("/api/projects/[^/]+/flow-ext")
                || (path.equals("/api/settings") && writes)
                || path.endsWith("/open-folder")
                || path.startsWith("/api/tiktok/login") || path.startsWith("/api/tiktok/logout")
                || (path.matches("/api/projects/[^/]+/agent") && writes)) {
            deny(res, 403, "Việc này chỉ làm được trên máy tính.");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean authenticated(HttpServletRequest req) {
        if (req.getCookies() == null) return false;
        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName())) {
                Instant until = sessions.get(c.getValue());
                if (until != null && until.isAfter(Instant.now())) return true;
                sessions.remove(c.getValue());
            }
        }
        return false;
    }

    private void login(HttpServletRequest req, HttpServletResponse res, String ip, String pin) throws IOException {
        Instant lock = locked.get(ip);
        if (lock != null && lock.isAfter(Instant.now())) {
            deny(res, 429, "Nhập sai nhiều lần. Thử lại sau vài phút.");
            return;
        }
        String body = new String(req.getInputStream().readNBytes(512), StandardCharsets.UTF_8);
        String given = body.replaceAll("(?s).*\"pin\"\\s*:\\s*\"([^\"]*)\".*", "$1");
        if (given.equals(body)) given = "";
        boolean ok = MessageDigest.isEqual(given.trim().getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            int n = fails.computeIfAbsent(ip, k -> new int[1])[0] + 1;
            fails.get(ip)[0] = n;
            if (n >= 5) {
                locked.put(ip, Instant.now().plusSeconds(300));
                fails.remove(ip);
            }
            deny(res, 401, "Mã PIN không đúng");
            return;
        }
        fails.remove(ip);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, Instant.now().plusSeconds(SESSION_DAYS * 86400));
        Cookie c = new Cookie(COOKIE, token);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge((int) (SESSION_DAYS * 86400));
        c.setAttribute("SameSite", "Lax");
        res.addCookie(c);
        res.setContentType("application/json");
        res.getWriter().write("{\"ok\":true}");
    }

    private void deny(HttpServletResponse res, int code, String msg) throws IOException {
        res.setStatus(code);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }

    private void loginPage(HttpServletResponse res) throws IOException {
        res.setStatus(200);
        res.setContentType("text/html;charset=UTF-8");
        res.setHeader("Cache-Control", "no-store");
        res.getWriter().write("""
                <!doctype html><html lang="vi"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
                <title>Media Studio</title>
                <style>
                body{margin:0;min-height:100dvh;display:flex;align-items:center;justify-content:center;background:#0f1115;
                color:#e6e8ee;font-family:system-ui,-apple-system,sans-serif;padding:16px}
                .box{width:100%;max-width:340px;background:#171a21;border:1px solid #2a2f3a;border-radius:14px;padding:22px}
                h1{font-size:20px;margin:0 0 6px}p{color:#8b93a5;margin:0 0 16px;font-size:14px}
                input{width:100%;box-sizing:border-box;font-size:22px;letter-spacing:.3em;text-align:center;padding:12px;
                border-radius:10px;border:1px solid #2a2f3a;background:#0f1115;color:#e6e8ee}
                button{width:100%;margin-top:12px;font-size:17px;padding:13px;border:0;border-radius:10px;background:#7c5cff;color:#fff}
                #e{color:#ff6b6b;font-size:14px;margin-top:10px;min-height:18px}
                </style></head><body><div class="box"><h1>🎬 Media Studio</h1>
                <p>Nhập mã PIN hiển thị trong Cài đặt trên máy tính.</p>
                <input id="p" inputmode="numeric" autocomplete="one-time-code" placeholder="••••••" autofocus>
                <button id="b">Vào</button><div id="e"></div></div>
                <script>
                const go=async()=>{const r=await fetch('/api/lan/login',{method:'POST',headers:{'Content-Type':'application/json'},
                body:JSON.stringify({pin:document.getElementById('p').value})});
                if(r.ok)location.reload();else{const j=await r.json().catch(()=>({}));document.getElementById('e').textContent=j.error||'Lỗi'}};
                document.getElementById('b').onclick=go;
                document.getElementById('p').onkeydown=e=>{if(e.key==='Enter')go()};
                </script></body></html>
                """);
    }
}
