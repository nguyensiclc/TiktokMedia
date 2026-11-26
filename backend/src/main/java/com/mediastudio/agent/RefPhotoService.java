package com.mediastudio.agent;

import com.mediastudio.job.JobContext;
import com.mediastudio.project.ProjectService;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modern history videos: downloads the real photo the storyboard found for a scene into
 * assets/scene-&lt;id&gt;-ref.jpg, so the image generator can use it as a reference. A photo is only kept when its
 * page is on a reputable site and that page really shows the image; anything else is skipped (no photo is better
 * than a wrong one), and nothing is ever searched for or picked automatically.
 */
@Service
public class RefPhotoService {
    /** Generic client name (no personal details) — Wikimedia asks automated clients for a User-Agent. */
    private static final String UA = "MediaStudio/1.0 (history reference photos)";
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;
    private static final int MIN_SIDE = 300;
    private static final int MAX_SIDE = 1600;

    /**
     * Sites whose photos are trusted (the page's host must be one of these or a sub-domain): encyclopedias, archives,
     * government / military / university sites, museums and major news agencies and newspapers.
     */
    static final List<String> TRUSTED = List.of(
            "wikipedia.org", "wikimedia.org", "britannica.com",
            "gov", "gov.vn", "gov.uk", "gov.au", "gouv.fr", "mil", "edu", "edu.vn", "ac.uk",
            "si.edu", "iwm.org.uk", "ecpad.fr",
            "baotanglichsu.vn", "btlsqsvn.org.vn",
            "vnanet.vn", "vietnamplus.vn", "nhandan.vn", "qdnd.vn", "vnexpress.net", "tuoitre.vn", "thanhnien.vn",
            "vtv.vn", "vov.vn", "dangcongsan.vn", "tienphong.vn", "laodong.vn", "cand.com.vn",
            "apnews.com", "reuters.com", "afp.com", "bbc.com", "bbc.co.uk", "nytimes.com", "theguardian.com",
            "time.com", "nationalgeographic.com");

    /** What the storyboard found for one scene: the direct image address and the page it is shown on. */
    public record Want(String url, String page) {
        /** Both are needed: a photo without its source page cannot be checked, so it is never used. */
        public boolean isEmpty() {
            return blank(url) || blank(page);
        }
    }

    /** A photo saved for a scene. */
    public record Saved(String file, String source) {
    }

    private final ProjectService projects;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();

    public RefPhotoService(ProjectService projects) {
        this.projects = projects;
    }

    /**
     * Downloads one photo per scene id. A scene whose photo is not from a reputable page, is not shown on that page,
     * or cannot be downloaded is logged and skipped: it is then drawn from its description only.
     *
     * @param sceneNumbers scene id → its number in the video, for the log
     * @return scene id → saved photo, for the scenes that got one
     */
    public Map<String, Saved> fetch(String projectId, Map<String, Want> wants, Map<String, Integer> sceneNumbers,
                                    JobContext ctx) throws IOException {
        Path assets = projects.folder(projectId, "assets");
        Map<String, Saved> out = new LinkedHashMap<>();
        Map<String, Path> byUrl = new HashMap<>(); // one photo used by several scenes is downloaded once
        Map<String, String> pages = new HashMap<>(); // page address → its text (null = could not be read)
        for (var e : wants.entrySet()) {
            if (ctx.cancelled()) break;
            String sid = e.getKey();
            Want w = e.getValue();
            if (w == null || w.isEmpty()) continue;
            String url = w.url().trim();
            String page = w.page().trim();
            String label = "  · cảnh " + sceneNumbers.getOrDefault(sid, 0) + ": ";
            try {
                if (!isWebUrl(url) || !isWebUrl(page)) {
                    ctx.log(label + "bỏ qua ảnh thật (địa chỉ không hợp lệ)");
                    continue;
                }
                if (!trusted(page)) {
                    ctx.log(label + "bỏ qua ảnh thật (nguồn không đủ uy tín: " + URI.create(page).getHost() + ")");
                    continue;
                }
                if (!pages.containsKey(page)) pages.put(page, readPage(page));
                String html = pages.get(page);
                if (html == null || !pageShows(html, url)) {
                    ctx.log(label + "bỏ qua ảnh thật (trang nguồn không có ảnh này)");
                    continue;
                }
                Path target = assets.resolve("scene-" + sid + "-ref.jpg");
                Path known = byUrl.get(url);
                if (known != null && Files.exists(known)) {
                    Files.copy(known, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else if (download(url, target)) {
                    byUrl.put(url, target);
                } else {
                    ctx.log(label + "bỏ qua ảnh thật (không tải được hoặc ảnh quá nhỏ)");
                    continue;
                }
                out.put(sid, new Saved(target.getFileName().toString(), page));
            } catch (IllegalArgumentException ex) {
                ctx.log(label + "bỏ qua ảnh thật (địa chỉ không hợp lệ)");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return out;
    }

    /** The page's host is a reputable site from {@link #TRUSTED} (or a sub-domain of one). */
    static boolean trusted(String page) {
        try {
            String host = URI.create(page.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            for (String t : TRUSTED) if (host.equals(t) || host.endsWith("." + t)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The page really shows that image: its file name appears in the page (thumbnail sizes like "220px-" and URL
     * encoding ignored). Stops a made-up or mixed-up address from being used.
     */
    static boolean pageShows(String html, String imageUrl) {
        String path = URI.create(imageUrl.trim()).getPath();
        if (path == null) return false;
        String name = norm(path.substring(path.lastIndexOf('/') + 1).replaceFirst("^\\d+px-", ""));
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (base.length() < 6) return false; // names like "1.jpg" or "img.jpg" prove nothing
        return norm(html).contains(name);
    }

    private static String norm(String s) {
        String safe = s.replaceAll("%(?![0-9A-Fa-f]{2})", "%25").replace("+", "%2B");
        try {
            safe = java.net.URLDecoder.decode(safe, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
        }
        return safe.replace("&amp;", "&").replace(' ', '_').toLowerCase();
    }

    /** The page's text, or null when it cannot be read. */
    private String readPage(String page) throws InterruptedException {
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(page)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", UA).header("Accept", "text/html,*/*;q=0.5").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) return null;
                byte[] b = readLimited(in, MAX_PAGE_BYTES);
                return b == null ? null : new String(b, StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Downloads an image, checks it really is a usable picture, scales it down and saves it as JPEG. */
    boolean download(String url, Path target) throws InterruptedException {
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                    .header("User-Agent", UA).header("Accept", "image/jpeg,image/png,image/*;q=0.8").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) return false;
                bytes = readLimited(in, MAX_BYTES);
            }
            if (bytes == null) return false;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null || img.getWidth() < MIN_SIDE || img.getHeight() < MIN_SIDE) return false;
            Files.write(target, toJpeg(img));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        long total = 0;
        for (int n; (n = in.read(chunk)) > 0; ) {
            total += n;
            if (total > max) return null;
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** RGB JPEG, longest side at most {@value #MAX_SIDE} px. */
    static byte[] toJpeg(BufferedImage src) throws IOException {
        double k = Math.min(1.0, (double) MAX_SIDE / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * k));
        int h = Math.max(1, (int) Math.round(src.getHeight() * k));
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "jpg", out)) throw new IOException("không ghi được JPEG");
        return out.toByteArray();
    }

    /** A public http(s) address: never this machine or the local network (the URL comes from web pages). */
    static boolean isWebUrl(String s) {
        if (blank(s)) return false;
        try {
            URI u = URI.create(s.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (!scheme.equals("https") && !scheme.equals("http") || u.getHost() == null) return false;
            for (var a : java.net.InetAddress.getAllByName(u.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
