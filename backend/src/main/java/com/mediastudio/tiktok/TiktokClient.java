package com.mediastudio.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediastudio.job.JobContext;
import com.mediastudio.settings.AppSettings;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * TikTok Login Kit for Desktop (OAuth 2 + PKCE, loopback redirect) and Content Posting API.
 * Docs: developers.tiktok.com/doc/login-kit-desktop, /doc/content-posting-api-*.
 * Tokens are kept in ~/.mediastudio/tiktok.json on this machine only.
 */
@Service
public class TiktokClient {
    static final String AUTH_URL = "https://www.tiktok.com/v2/auth/authorize/";
    static final String API = "https://open.tiktokapis.com";
    public static final String CALLBACK_PATH = "/api/tiktok/callback/";

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Path tokenFile = Path.of(System.getProperty("user.home"), ".mediastudio", "tiktok.json");
    private final SecureRandom random = new SecureRandom();

    /** Pending authorizations: state → {verifier, redirectUri}. */
    private final Map<String, String[]> pending = new ConcurrentHashMap<>();

    public TiktokClient(SettingsService settings) {
        this.settings = settings;
    }

    // ---------------------------------------------------------------- OAuth

    public String scopes() {
        AppSettings s = settings.get();
        List<String> list = new ArrayList<>(List.of("user.info.basic"));
        if (s.tiktokScopeUpload) list.add("video.upload");
        if (s.tiktokScopePublish) list.add("video.publish");
        return String.join(",", list);
    }

    /** Builds the authorize URL; the redirect must be registered as http://127.0.0.1:*\/api/tiktok/callback/ */
    public String authorizeUrl(int port) throws Exception {
        AppSettings s = settings.get();
        if (blank(s.tiktokClientKey) || blank(s.tiktokClientSecret)) {
            throw new IllegalStateException("Nhập Client key và Client secret của app TikTok trong Cài đặt trước");
        }
        String verifier = randomString(64);
        String state = randomString(24);
        String redirect = "http://127.0.0.1:" + port + CALLBACK_PATH;
        pending.put(state, new String[]{verifier, redirect});
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = "base64url".equals(s.tiktokPkceEncoding)
                ? Base64.getUrlEncoder().withoutPadding().encodeToString(sha)
                : HexFormat.of().formatHex(sha);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_key", s.tiktokClientKey.trim());
        q.put("response_type", "code");
        q.put("scope", scopes());
        q.put("redirect_uri", redirect);
        q.put("state", state);
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        return AUTH_URL + "?" + form(q);
    }

    /** Handles the redirect: verifies state, exchanges the code, stores tokens. */
    public JsonNode handleCallback(String code, String state) throws Exception {
        String[] p = state == null ? null : pending.remove(state);
        if (p == null) throw new IllegalStateException("State không khớp hoặc đã hết hạn — hãy bấm Kết nối lại");
        AppSettings s = settings.get();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("client_key", s.tiktokClientKey.trim());
        body.put("client_secret", s.tiktokClientSecret.trim());
        body.put("code", code);
        body.put("grant_type", "authorization_code");
        body.put("redirect_uri", p[1]);
        body.put("code_verifier", p[0]);
        JsonNode tok = tokenRequest(body);
        saveToken(tok);
        return tok;
    }

    private JsonNode tokenRequest(Map<String, String> body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/v2/oauth/token/"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(body))).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode j = mapper.readTree(res.body());
        if (res.statusCode() >= 300 || j.hasNonNull("error") && !j.path("error").isObject() || !j.hasNonNull("access_token")) {
            throw new IOException("TikTok token lỗi: " + j.path("error").asText("") + " " + j.path("error_description").asText(res.body()));
        }
        return j;
    }

    private synchronized void saveToken(JsonNode tok) throws IOException {
        ObjectNode t = mapper.createObjectNode();
        long now = Instant.now().getEpochSecond();
        t.put("access_token", tok.path("access_token").asText());
        t.put("refresh_token", tok.path("refresh_token").asText());
        t.put("open_id", tok.path("open_id").asText());
        t.put("scope", tok.path("scope").asText());
        t.put("expires_at", now + tok.path("expires_in").asLong(7200) - 60);
        t.put("refresh_expires_at", now + tok.path("refresh_expires_in").asLong(15552000));
        Files.createDirectories(tokenFile.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(tokenFile.toFile(), t);
    }

    private synchronized JsonNode token() throws IOException {
        return Files.exists(tokenFile) ? mapper.readTree(tokenFile.toFile()) : null;
    }

    public synchronized void logout() throws IOException {
        Files.deleteIfExists(tokenFile);
    }

    /** A valid access token, refreshing it (and rotating the refresh token) when needed. */
    public synchronized String accessToken() throws Exception {
        JsonNode t = token();
        if (t == null) throw new IllegalStateException("Chưa kết nối TikTok — vào Cài đặt → TikTok → Kết nối");
        long now = Instant.now().getEpochSecond();
        if (t.path("expires_at").asLong() > now) return t.path("access_token").asText();
        if (t.path("refresh_expires_at").asLong() <= now) {
            throw new IllegalStateException("Phiên TikTok đã hết hạn — hãy kết nối lại");
        }
        AppSettings s = settings.get();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("client_key", s.tiktokClientKey.trim());
        body.put("client_secret", s.tiktokClientSecret.trim());
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", t.path("refresh_token").asText());
        JsonNode fresh = tokenRequest(body);
        saveToken(fresh);
        return fresh.path("access_token").asText();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        AppSettings s = settings.get();
        m.put("configured", !blank(s.tiktokClientKey) && !blank(s.tiktokClientSecret));
        try {
            JsonNode t = token();
            boolean connected = t != null && t.path("refresh_expires_at").asLong() > Instant.now().getEpochSecond();
            m.put("connected", connected);
            if (connected) {
                m.put("scopes", Arrays.asList(t.path("scope").asText().split(",")));
                try {
                    JsonNode u = userInfo();
                    m.put("displayName", u.path("display_name").asText(""));
                    m.put("avatarUrl", u.path("avatar_url").asText(""));
                } catch (Exception e) {
                    m.put("userError", e.getMessage());
                }
            }
        } catch (Exception e) {
            m.put("connected", false);
        }
        return m;
    }

    // ---------------------------------------------------------------- API

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken()).GET().build();
        return unwrap(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : mapper.writeValueAsString(body),
                        StandardCharsets.UTF_8)).build();
        return unwrap(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    /** TikTok wraps responses as {data, error:{code, message, log_id}}; code "ok" means success. */
    private JsonNode unwrap(HttpResponse<String> res) throws IOException {
        JsonNode j = mapper.readTree(res.body());
        String code = j.path("error").path("code").asText("ok");
        if (res.statusCode() >= 300 || !"ok".equals(code)) {
            throw new IOException("TikTok: " + code + " — " + j.path("error").path("message").asText(res.body()));
        }
        return j.path("data");
    }

    public JsonNode userInfo() throws Exception {
        return get("/v2/user/info/?fields=open_id,avatar_url,display_name").path("user");
    }

    /** Needs video.publish. Must be shown before a direct post (nickname, privacy options, toggles). */
    public JsonNode creatorInfo() throws Exception {
        return post("/v2/post/publish/creator_info/query/", null);
    }

    /**
     * Uploads a local video. mode "draft" → inbox (video.upload); "direct" → direct post (video.publish).
     * Returns publish_id.
     */
    public String upload(Path video, String mode, Map<String, Object> postInfo, JobContext ctx) throws Exception {
        long size = Files.size(video);
        // < 5 MB must be one chunk; ≤ 64 MB may be one chunk; above that 10 MB chunks with the remainder in the last.
        long chunk = size <= 64L * 1000 * 1000 ? size : 10L * 1000 * 1000;
        long count = Math.max(1, size / chunk);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source", "FILE_UPLOAD");
        source.put("video_size", size);
        source.put("chunk_size", chunk);
        source.put("total_chunk_count", count);
        Map<String, Object> body = new LinkedHashMap<>();
        if ("direct".equals(mode)) body.put("post_info", postInfo);
        body.put("source_info", source);

        String path = "direct".equals(mode) ? "/v2/post/publish/video/init/" : "/v2/post/publish/inbox/video/init/";
        ctx.log("▶ Khởi tạo upload (" + ("direct".equals(mode) ? "đăng thẳng" : "gửi vào hộp nháp") + ", "
                + String.format("%.1f MB, %d phần", size / 1e6, count) + ")");
        JsonNode init = post(path, body);
        String publishId = init.path("publish_id").asText();
        String uploadUrl = init.path("upload_url").asText();
        if (publishId.isEmpty() || uploadUrl.isEmpty()) throw new IOException("TikTok không trả upload_url");

        try (InputStream in = Files.newInputStream(video)) {
            for (long i = 0; i < count; i++) {
                if (ctx.cancelled()) throw new IOException("Đã huỷ");
                long start = i * chunk;
                long end = (i == count - 1) ? size - 1 : start + chunk - 1;
                byte[] part = in.readNBytes((int) (end - start + 1));
                HttpRequest put = HttpRequest.newBuilder(URI.create(uploadUrl)).timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "video/mp4")
                        .header("Content-Range", "bytes " + start + "-" + end + "/" + size)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(part)).build();
                HttpResponse<String> r = http.send(put, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 201 && r.statusCode() != 206) {
                    throw new IOException("Upload phần " + (i + 1) + " lỗi HTTP " + r.statusCode() + ": " + r.body());
                }
                ctx.log(String.format("  ↑ phần %d/%d (%d%%)", i + 1, count, (end + 1) * 100 / size));
            }
        }
        return publishId;
    }

    public JsonNode publishStatus(String publishId) throws Exception {
        return post("/v2/post/publish/status/fetch/", Map.of("publish_id", publishId));
    }

    // ---------------------------------------------------------------- utils

    private String randomString(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private static String form(Map<String, String> m) {
        return m.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
