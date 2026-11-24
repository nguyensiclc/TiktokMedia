package com.mediastudio.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediastudio.agent.AgentService;
import com.mediastudio.agent.McpConfigWriter;
import com.mediastudio.job.Job;
import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import com.mediastudio.voice.VieneuClient;
import com.mediastudio.voice.VoiceboxClient;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class SystemController {
    private final SettingsService settings;
    private final VoiceboxClient voicebox;
    private final McpConfigWriter mcp;

    private final AgentService agent;

    private final VieneuClient vieneu;

    public SystemController(SettingsService settings, VoiceboxClient voicebox, McpConfigWriter mcp, AgentService agent,
                            VieneuClient vieneu) {
        this.vieneu = vieneu;
        this.settings = settings;
        this.voicebox = voicebox;
        this.mcp = mcp;
        this.agent = agent;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }

    /** Checks each external dependency and reports version / error. */
    @GetMapping("/system/check")
    public List<Map<String, Object>> check() {
        AppSettings s = settings.get();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(tool("FFmpeg", List.of(s.ffmpegPath, "-version")));
        out.add(tool("FFprobe", List.of(s.ffprobePath, "-version")));
        out.add(tool("Claude CLI", List.of(s.claudePath, "--version")));
        out.add(tool("Node / npx (Playwright MCP)", List.of(isWindows() ? "cmd" : "sh", isWindows() ? "/c" : "-c", "npx --version")));
        if ("voicebox".equals(s.voiceProvider)) {
            try {
                int n = voicebox.profiles().size();
                out.add(item("Voicebox", true, n + " voice profile tại " + s.voiceboxUrl));
            } catch (Exception e) {
                out.add(item("Voicebox", false, "Không kết nối được " + s.voiceboxUrl + " — hãy mở ứng dụng Voicebox"));
            }
        } else {
            boolean py = s.vieneuPython != null && !s.vieneuPython.isBlank() && Files.exists(Path.of(s.vieneuPython));
            if (vieneu.reachable()) out.add(item("VieNeu-TTS", true, "Đang chạy tại " + s.vieneuUrl));
            else if (py && s.vieneuAutoStart) out.add(item("VieNeu-TTS", true, "Chưa chạy — sẽ tự khởi động khi tạo giọng"));
            else out.add(item("VieNeu-TTS", false, py ? "Chưa chạy tại " + s.vieneuUrl : "Chưa cấu hình python.exe của venv VieNeu"));
        }
        return out;
    }

    /**
     * Opens a console running interactive Claude CLI with the app's MCP config,
     * so the user can type /mcp and complete OAuth (Canva) once.
     */
    @PostMapping("/system/mcp-login")
    public Map<String, Object> mcpLogin() throws IOException {
        Path cfg = mcp.writeGlobal();
        AppSettings s = settings.get();
        if (!isWindows()) throw new UnsupportedOperationException("Chạy thủ công: claude --mcp-config " + cfg);
        // A .bat avoids `start`'s quoted-title rule (Java doesn't quote args without spaces).
        Path bat = s.workspace().resolve("mcp-login.bat");
        String script = String.join("\r\n",
                "@echo off",
                "chcp 65001 >nul",
                "title MediaStudio - Claude MCP login",
                "echo.",
                "echo  Dang nhap MCP cho Media Studio",
                "echo  1. Go /mcp roi Enter",
                "echo  2. Chon canva -^> Authenticate, dang nhap trong trinh duyet",
                "echo  3. Xong thi go /exit hoac dong cua so nay",
                "echo.",
                "\"" + s.claudePath + "\" --mcp-config \"" + cfg + "\" --strict-mcp-config",
                "");
        Files.writeString(bat, script, StandardCharsets.UTF_8);
        new ProcessBuilder("cmd", "/c", "start", bat.getFileName().toString())
                .directory(s.workspace().toFile()).start();
        return Map.of("config", cfg.toString(),
                "hint", "Trong cửa sổ Claude vừa mở, gõ /mcp → chọn canva → Authenticate.");
    }

    /** Is the Flow browser profile signed in to Google, and is Chrome still holding it? */
    @GetMapping("/system/flow-status")
    public Map<String, Object> flowStatus() {
        Path profile = settings.get().browserProfile();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profile", profile.toString());
        m.put("profileExists", Files.isDirectory(profile));
        m.put("account", googleAccount(profile));
        m.put("chromeOpen", profileInUse(profile));
        return m;
    }

    /** Runs a short Claude + Playwright job that opens Flow and reports whether it is usable. */
    @PostMapping("/system/flow-check")
    public Job flowCheck() {
        if (profileInUse(settings.get().browserProfile())) {
            throw new IllegalStateException("Chrome vẫn đang mở với profile Flow — hãy đóng Chrome rồi thử lại");
        }
        return agent.checkFlow();
    }

    /** Email of the Google account stored in the Chrome profile, or null. */
    private String googleAccount(Path profile) {
        Path prefs = profile.resolve("Default").resolve("Preferences");
        if (!Files.exists(prefs)) return null;
        try {
            JsonNode n = new ObjectMapper().readTree(prefs.toFile());
            for (JsonNode a : n.path("account_info")) {
                String email = a.path("email").asText("");
                if (!email.isBlank()) return email;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static boolean profileInUse(Path profile) {
        return AgentService.profileInUse(profile);
    }

    /** Opens Chrome with the same persistent profile Playwright MCP uses, to sign in to Google Flow. */
    @PostMapping("/system/flow-login")
    public Map<String, Object> flowLogin() throws IOException {
        AppSettings s = settings.get();
        Path profile = s.browserProfile();
        Files.createDirectories(profile);
        String chrome = findChrome(s);
        if (chrome == null) throw new IllegalStateException("Không tìm thấy chrome.exe — điền đường dẫn trong Cài đặt");
        new ProcessBuilder(chromeArgs(chrome, profile, s.flowUrl)).start();
        return Map.of("profile", profile.toString(),
                "hint", "Đăng nhập Google trong cửa sổ Chrome này, rồi ĐÓNG Chrome trước khi chạy tác vụ Flow.");
    }

    /** Opens Chrome with the Playwright profile at TikTok's login page (for "post to TikTok Studio"). */
    @PostMapping("/system/tiktok-login")
    public Map<String, Object> tiktokLogin() throws IOException {
        AppSettings s = settings.get();
        Path profile = s.browserProfile();
        Files.createDirectories(profile);
        String chrome = findChrome(s);
        if (chrome == null) throw new IllegalStateException("Không tìm thấy chrome.exe — điền đường dẫn trong Cài đặt");
        new ProcessBuilder(chromeArgs(chrome, profile,
                "https://www.tiktok.com/login?redirect_url=https%3A%2F%2Fwww.tiktok.com%2Ftiktokstudio%2Fupload")).start();
        return Map.of("profile", profile.toString(),
                "hint", "Đăng nhập TikTok trong cửa sổ Chrome này, rồi ĐÓNG Chrome trước khi bấm Đăng.");
    }

    /**
     * Command line for the manual sign-in window. On macOS Playwright starts Chrome with --use-mock-keychain, so its
     * cookies are encrypted with a fixed key instead of the Keychain one: the sign-in window must use the same flag,
     * or Playwright cannot read the session (and overwrites it, so the next manual Chrome asks to sign in again).
     */
    static List<String> chromeArgs(String chrome, Path profile, String url) {
        List<String> cmd = new ArrayList<>(List.of(chrome, "--user-data-dir=" + profile.toAbsolutePath()));
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) cmd.add("--use-mock-keychain");
        cmd.add(url);
        return cmd;
    }

    private static String findChrome(AppSettings s) {
        if (s.chromePath != null && !s.chromePath.isBlank()) return s.chromePath;
        List<String> candidates = new ArrayList<>();
        for (String env : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            String base = System.getenv(env);
            if (base != null) candidates.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        candidates.add("/usr/bin/google-chrome");
        return candidates.stream().filter(c -> Files.exists(Path.of(c))).findFirst().orElse(null);
    }

    private static Map<String, Object> tool(String name, List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return item(name, false, "Quá thời gian");
            }
            String first = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .lines().findFirst().orElse("").trim();
            return item(name, p.exitValue() == 0, first);
        } catch (Exception e) {
            return item(name, false, "Không chạy được: " + String.join(" ", cmd));
        }
    }

    private static Map<String, Object> item(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
