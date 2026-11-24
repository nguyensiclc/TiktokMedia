package com.mediastudio.sync;

import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.settings.SettingsService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/**
 * Shares the posts list between computers through a PostgreSQL database (e.g. a free Neon / Supabase one) so the
 * same topic is not made twice. Only names and states travel, never pictures. The schema lives in
 * {@code db/migration} and is applied with Flyway when the database is opened. A project deleted here stays in the
 * table (deleted = true), so a topic already posted is still recognised after its pictures were cleaned up.
 */
@Service
public class PostSync {
    private static final long OTHERS_CACHE_MS = 5_000;

    /** One post as the other computers see it. */
    public static class Entry {
        public String id;
        public String name;
        public String topic;
        public String niche;
        /** QUEUED, PREPARING, READY, POSTING, POSTED, DRAFTED, FAILED, or NONE (a project outside the list). */
        public String state;
        public Instant scheduledAt;
        public Instant postedAt;
        public Instant createdAt;
        public Instant updatedAt;
        public boolean deleted;
        // progress (V2), so every computer shows the same steps
        public String aspect;
        public int scenes;
        public boolean hasScript;
        public int scenesPrompted;
        public int scenesWithMedia;
        public int slides;
        public boolean hasCaption;
        public boolean queueDraft;
        public String queueStep;
        public String queueError;

        /** What is compared to know whether this post changed since it was last sent. */
        String signature() {
            return String.join("|", String.valueOf(name), String.valueOf(topic), String.valueOf(niche), state,
                    String.valueOf(scheduledAt), String.valueOf(postedAt), String.valueOf(aspect),
                    scenes + "," + hasScript + "," + scenesPrompted + "," + scenesWithMedia + "," + slides + "," + hasCaption
                            + "," + queueDraft, String.valueOf(queueStep), String.valueOf(queueError));
        }
    }

    /** The posts of one computer. */
    public static class MachinePosts {
        public String machineId;
        public String machine;
        public Instant updatedAt;
        public List<Entry> posts = new ArrayList<>();
    }

    private final ProjectService projects;
    private final SettingsService settings;
    private final String machineId;
    private volatile String lastError;
    private volatile boolean stop;

    private HikariDataSource pool;
    private String poolUrl = "";
    /** What this computer last wrote, per project, to only send what changed. */
    private final Map<String, String> published = new HashMap<>();
    private Set<String> publishedIds = null;
    private String publishedName = null;
    private volatile List<MachinePosts> othersCache = List.of();
    private volatile long othersAt;

    public PostSync(ProjectService projects, SettingsService settings) {
        this.projects = projects;
        this.settings = settings;
        this.machineId = loadMachineId();
    }

    @PostConstruct
    void start() {
        Thread.ofVirtual().name("post-sync").start(() -> {
            while (!stop) {
                try {
                    publish();
                } catch (Exception ignored) {
                    // reported through lastError
                }
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @PreDestroy
    synchronized void shutdown() {
        stop = true;
        closePool();
    }

    public boolean enabled() {
        String u = settings.get().syncDbUrl;
        return u != null && !u.isBlank();
    }

    /** Host / database shown in Settings (never the password). */
    public String label() {
        if (!enabled()) return "";
        try {
            Conn c = parse(settings.get().syncDbUrl);
            return c.label;
        } catch (Exception e) {
            return "";
        }
    }

    public String machineName() {
        String n = settings.get().machineName;
        if (n != null && !n.isBlank()) return n.trim();
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "Máy " + machineId.substring(0, 4);
        }
    }

    public String machineId() {
        return machineId;
    }

    public String lastError() {
        return lastError;
    }

    // ---- connection ----

    record Conn(String jdbcUrl, String user, String password, String label) {
    }

    /**
     * Accepts what Neon / Supabase show ({@code postgresql://user:pass@host/db?sslmode=require}), {@code postgres://…}
     * or a JDBC URL. Remote hosts get sslmode=require when none is given.
     */
    static Conn parse(String raw) {
        String s = raw.trim();
        if (s.startsWith("psql ")) s = s.substring(5).trim();
        s = s.replaceAll("^['\"]|['\"]$", "");
        if (s.startsWith("jdbc:")) s = s.substring(5);
        if (s.startsWith("postgres://")) s = "postgresql://" + s.substring("postgres://".length());
        if (!s.startsWith("postgresql://")) throw new IllegalArgumentException("Chuỗi kết nối phải bắt đầu bằng postgresql://");
        URI u = URI.create(s);
        String host = u.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Chuỗi kết nối thiếu tên máy chủ");
        String db = u.getRawPath() == null || u.getRawPath().length() <= 1 ? "postgres" : u.getRawPath().substring(1);
        String user = null, pass = null;
        if (u.getRawUserInfo() != null) {
            String[] up = u.getRawUserInfo().split(":", 2);
            user = URLDecoder.decode(up[0], StandardCharsets.UTF_8);
            if (up.length > 1) pass = URLDecoder.decode(up[1], StandardCharsets.UTF_8);
        }
        List<String> params = new ArrayList<>();
        if (u.getRawQuery() != null) {
            for (String p : u.getRawQuery().split("&")) {
                if (p.isBlank()) continue;
                // libpq's channel_binding is called channelBinding in the JDBC driver
                params.add(p.startsWith("channel_binding=") ? "channelBinding=" + p.substring(16) : p);
            }
        }
        boolean local = host.equals("localhost") || host.equals("127.0.0.1");
        if (!local && params.stream().noneMatch(p -> p.startsWith("sslmode="))) params.add("sslmode=require");
        String port = u.getPort() > 0 ? ":" + u.getPort() : "";
        String jdbc = "jdbc:postgresql://" + host + port + "/" + db + (params.isEmpty() ? "" : "?" + String.join("&", params));
        return new Conn(jdbc, user, pass, host + port + "/" + URLDecoder.decode(db, StandardCharsets.UTF_8));
    }

    /** The pool for the URL in Settings, (re)opened and migrated when that URL changed. Null when off. */
    private synchronized HikariDataSource pool() {
        if (!enabled()) {
            closePool();
            return null;
        }
        String url = settings.get().syncDbUrl.trim();
        if (pool != null && url.equals(poolUrl)) return pool;
        closePool();
        Conn c = parse(url);
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(c.jdbcUrl());
        if (c.user() != null) hc.setUsername(c.user());
        if (c.password() != null) hc.setPassword(c.password());
        hc.setPoolName("post-sync");
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(0);
        hc.setIdleTimeout(60_000);       // free databases sleep when idle: do not hold connections open
        hc.setConnectionTimeout(8_000);  // enough for a sleeping free database to wake up, short enough not to hang
        hc.setInitializationFailTimeout(-1);
        HikariDataSource ds = new HikariDataSource(hc);
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
        pool = ds;
        poolUrl = url;
        published.clear();
        publishedIds = null;
        publishedName = null;
        othersAt = 0;
        return pool;
    }

    private void closePool() {
        if (pool != null) {
            pool.close();
            pool = null;
            poolUrl = "";
        }
    }

    /** After a failure, reads skip the database for a while instead of each waiting for a connection timeout. */
    private volatile long failedAt;

    private boolean backingOff() {
        return System.currentTimeMillis() - failedAt < 30_000;
    }

    private void fail(Exception e) {
        failedAt = System.currentTimeMillis();
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        lastError = "Không kết nối được cơ sở dữ liệu: " + m;
    }

    // ---- writing ----

    /** Sends this computer's posts that changed since the last time (and marks deleted projects). */
    public synchronized void publish() {
        HikariDataSource ds;
        try {
            ds = pool();
        } catch (Exception e) {
            fail(e);
            return;
        }
        if (ds == null) {
            lastError = null;
            return;
        }
        List<Project> list = projects.list();
        Set<String> ids = new HashSet<>();
        List<Entry> changed = new ArrayList<>();
        for (Project p : list) {
            ids.add(p.id);
            Entry e = entry(p);
            if (!e.signature().equals(published.get(p.id))) changed.add(e);
        }
        String name = machineName();
        boolean idsChanged = !ids.equals(publishedIds);
        if (changed.isEmpty() && !idsChanged && name.equals(publishedName)) {
            lastError = null;
            return;
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement m = c.prepareStatement("""
                    INSERT INTO machines (id, name, updated_at) VALUES (?, ?, now())
                    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, updated_at = now()""")) {
                m.setString(1, machineId);
                m.setString(2, name);
                m.executeUpdate();
            }
            if (!changed.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO posts (machine_id, project_id, name, topic, niche, state, scheduled_at, posted_at,
                                           created_at, updated_at, deleted,
                                           aspect, scenes, has_script, scenes_prompted, scenes_with_media, slides,
                                           has_caption, queue_draft, queue_step, queue_error)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (machine_id, project_id) DO UPDATE SET
                            name = EXCLUDED.name, topic = EXCLUDED.topic, niche = EXCLUDED.niche, state = EXCLUDED.state,
                            scheduled_at = EXCLUDED.scheduled_at, posted_at = EXCLUDED.posted_at,
                            created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at, deleted = FALSE,
                            aspect = EXCLUDED.aspect, scenes = EXCLUDED.scenes, has_script = EXCLUDED.has_script,
                            scenes_prompted = EXCLUDED.scenes_prompted, scenes_with_media = EXCLUDED.scenes_with_media,
                            slides = EXCLUDED.slides, has_caption = EXCLUDED.has_caption,
                            queue_draft = EXCLUDED.queue_draft, queue_step = EXCLUDED.queue_step,
                            queue_error = EXCLUDED.queue_error""")) {
                    for (Entry e : changed) {
                        ps.setString(1, machineId);
                        ps.setString(2, e.id);
                        ps.setString(3, e.name == null ? "" : e.name);
                        ps.setString(4, e.topic == null ? "" : e.topic);
                        ps.setString(5, e.niche);
                        ps.setString(6, e.state);
                        ps.setTimestamp(7, ts(e.scheduledAt));
                        ps.setTimestamp(8, ts(e.postedAt));
                        ps.setTimestamp(9, ts(e.createdAt));
                        ps.setTimestamp(10, ts(e.updatedAt));
                        ps.setString(11, e.aspect);
                        ps.setInt(12, e.scenes);
                        ps.setBoolean(13, e.hasScript);
                        ps.setInt(14, e.scenesPrompted);
                        ps.setInt(15, e.scenesWithMedia);
                        ps.setInt(16, e.slides);
                        ps.setBoolean(17, e.hasCaption);
                        ps.setBoolean(18, e.queueDraft);
                        ps.setString(19, e.queueStep);
                        ps.setString(20, e.queueError);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (idsChanged) {
                try (PreparedStatement d = c.prepareStatement(
                        "UPDATE posts SET deleted = TRUE WHERE machine_id = ? AND NOT deleted AND NOT (project_id = ANY (?))")) {
                    d.setString(1, machineId);
                    d.setArray(2, c.createArrayOf("varchar", ids.toArray()));
                    d.executeUpdate();
                }
            }
            c.commit();
            for (Entry e : changed) published.put(e.id, e.signature());
            othersAt = 0;
            publishedIds = ids;
            publishedName = name;
            lastError = null;
        } catch (Exception e) {
            fail(e);
        }
    }

    // ---- reading ----

    /** The other computers' posts (cached for a few seconds: the posts page polls often). */
    /** Forget the back-off and cache (Settings just changed: try again now). */
    public void retryNow() {
        failedAt = 0;
        othersAt = 0;
    }

    public List<MachinePosts> others() {
        if (!enabled()) return List.of();
        if (System.currentTimeMillis() - othersAt < OTHERS_CACHE_MS || backingOff()) return othersCache;
        List<MachinePosts> out = new ArrayList<>();
        try {
            HikariDataSource ds = pool();
            if (ds == null) return List.of();
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                    SELECT m.id AS mid, m.name AS mname, m.updated_at AS mupdated, p.*
                    FROM posts p JOIN machines m ON m.id = p.machine_id
                    WHERE p.machine_id <> ?
                    ORDER BY m.name, p.created_at DESC NULLS LAST""")) {
                ps.setString(1, machineId);
                Map<String, MachinePosts> byMachine = new LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MachinePosts mp = byMachine.computeIfAbsent(rs.getString("mid"), k -> new MachinePosts());
                        if (mp.machineId == null) {
                            mp.machineId = rs.getString("mid");
                            mp.machine = rs.getString("mname");
                            mp.updatedAt = instant(rs.getTimestamp("mupdated"));
                        }
                        mp.posts.add(row(rs));
                    }
                }
                out.addAll(byMachine.values());
            }
            othersCache = out;
            othersAt = System.currentTimeMillis();
            if (lastError != null && lastError.startsWith("Không kết nối")) lastError = null;
        } catch (Exception e) {
            fail(e);
            return othersCache;
        }
        return out;
    }

    /** This computer's own posts: the projects here plus the deleted ones the database still remembers. */
    public List<Entry> own() {
        Map<String, Entry> byId = new LinkedHashMap<>();
        try {
            HikariDataSource ds = backingOff() ? null : pool();
            if (ds != null) {
                try (Connection c = ds.getConnection();
                     PreparedStatement ps = c.prepareStatement("SELECT * FROM posts WHERE machine_id = ? AND deleted")) {
                    ps.setString(1, machineId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Entry e = row(rs);
                            byId.put(e.id, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            fail(e);
        }
        for (Project p : projects.list()) byId.put(p.id, entry(p));
        return new ArrayList<>(byId.values());
    }

    private static Entry row(ResultSet rs) throws SQLException {
        Entry e = new Entry();
        e.id = rs.getString("project_id");
        e.name = rs.getString("name");
        e.topic = rs.getString("topic");
        e.niche = rs.getString("niche");
        e.state = rs.getString("state");
        e.scheduledAt = instant(rs.getTimestamp("scheduled_at"));
        e.postedAt = instant(rs.getTimestamp("posted_at"));
        e.createdAt = instant(rs.getTimestamp("created_at"));
        e.updatedAt = instant(rs.getTimestamp("updated_at"));
        e.deleted = rs.getBoolean("deleted");
        e.aspect = rs.getString("aspect");
        e.scenes = rs.getInt("scenes");
        e.hasScript = rs.getBoolean("has_script");
        e.scenesPrompted = rs.getInt("scenes_prompted");
        e.scenesWithMedia = rs.getInt("scenes_with_media");
        e.slides = rs.getInt("slides");
        e.hasCaption = rs.getBoolean("has_caption");
        e.queueDraft = rs.getBoolean("queue_draft");
        e.queueStep = rs.getString("queue_step");
        e.queueError = rs.getString("queue_error");
        return e;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static Entry entry(Project p) {
        Entry e = new Entry();
        e.id = p.id;
        e.name = p.name;
        e.topic = p.topic;
        e.niche = p.niche;
        e.state = state(p);
        e.scheduledAt = p.scheduledAt;
        e.postedAt = p.postedAt;
        e.createdAt = p.createdAt;
        e.updatedAt = p.updatedAt;
        e.aspect = p.aspect;
        e.scenes = p.scenes.size();
        e.hasScript = p.scenes.stream().anyMatch(s -> s.narration != null && !s.narration.isBlank());
        e.scenesPrompted = (int) p.scenes.stream().filter(s -> s.imagePrompt != null && !s.imagePrompt.isBlank()).count();
        e.scenesWithMedia = (int) p.scenes.stream().filter(s -> s.mediaFile != null).count();
        e.slides = p.slideFiles == null ? 0 : p.slideFiles.size();
        e.hasCaption = p.tiktokCaption != null && !p.tiktokCaption.isBlank();
        e.queueDraft = p.queueDraft;
        e.queueStep = p.queueStep;
        e.queueError = p.queueError;
        return e;
    }

    static String state(Project p) {
        if (p.queueState != null) {
            if ("POSTED".equals(p.queueState) && p.queueDraft) return "DRAFTED";
            return p.queueState;
        }
        boolean posted = p.tiktokPosts.stream().anyMatch(t -> "POSTED_VIA_STUDIO".equals(t.status));
        boolean drafted = p.tiktokPosts.stream().anyMatch(t -> "DRAFT_VIA_STUDIO".equals(t.status));
        return posted ? "POSTED" : drafted ? "DRAFTED" : "NONE";
    }

    // ---- duplicate topics ----

    private static final Set<String> STOP = Set.of("va", "cua", "la", "cac", "nhung", "trong", "voi", "the", "mot", "ve",
            "o", "tai", "cho", "den", "tu", "da", "bi", "duoc", "and", "of", "a", "in");

    /** Lower case, no accents (đ → d), only letters and digits separated by single spaces. */
    static String normalize(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return t.replaceAll("[^a-z0-9]+", " ").trim();
    }

    static Set<String> words(String norm) {
        Set<String> w = new LinkedHashSet<>();
        for (String x : norm.split(" ")) if (!x.isEmpty() && !STOP.contains(x)) w.add(x);
        return w;
    }

    /**
     * Same topic? Equal after normalising, a short title inside a longer one, or (with at least 3 meaningful words
     * on each side) 80 % of the shorter one's words in the other. Different years or numbers always mean different
     * topics (Bạch Đằng 938 ≠ 1288).
     */
    static boolean similar(String a, String b) {
        String na = normalize(a), nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb)) return true;
        Set<String> wa = words(na), wb = words(nb);
        Set<String> numA = new HashSet<>(), numB = new HashSet<>();
        for (String w : wa) if (w.matches("\\d+")) numA.add(w);
        for (String w : wb) if (w.matches("\\d+")) numB.add(w);
        if (!numA.isEmpty() && !numB.isEmpty() && !numA.containsAll(numB) && !numB.containsAll(numA)) return false;
        // A short title inside a longer one: "Thánh Gióng" ~ "Sự tích Thánh Gióng" (not tiny ones like "Hà Nội").
        String shorter = na.length() <= nb.length() ? na : nb, longer = shorter == na ? nb : na;
        if (shorter.length() >= 8 && shorter.contains(" ") && (" " + longer + " ").contains(" " + shorter + " ")) return true;
        if (wa.size() < 3 || wb.size() < 3) return false;
        Set<String> common = new HashSet<>(wa);
        common.retainAll(wb);
        return common.size() >= 0.8 * Math.min(wa.size(), wb.size());
    }

    /** One possible duplicate of a topic being added. */
    public static class Match {
        public String topic;
        public String name;
        public String machine;
        public boolean thisMachine;
        public String state;
        public boolean deleted;
        public Instant postedAt;
        public Instant scheduledAt;
        public Instant createdAt;
    }

    /** For each topic, the posts on this computer and the others that look like the same topic. */
    public Map<String, List<Match>> check(List<String> topics) {
        othersAt = 0; // a fresh read: another computer may have added the same topic a moment ago
        List<MachinePosts> all = new ArrayList<>();
        MachinePosts me = new MachinePosts();
        me.machine = machineName();
        me.machineId = machineId;
        me.posts = own();
        all.add(me);
        all.addAll(others());
        Map<String, List<Match>> out = new LinkedHashMap<>();
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) continue;
            List<Match> found = new ArrayList<>();
            for (MachinePosts f : all) {
                for (Entry e : f.posts) {
                    if (!similar(topic, e.name) && !similar(topic, e.topic)) continue;
                    Match m = new Match();
                    m.topic = topic;
                    m.name = e.name;
                    m.machine = f.machine;
                    m.thisMachine = f == me;
                    m.state = e.state;
                    m.deleted = e.deleted;
                    m.postedAt = e.postedAt;
                    m.scheduledAt = e.scheduledAt;
                    m.createdAt = e.createdAt;
                    found.add(m);
                }
            }
            if (!found.isEmpty()) out.put(topic, found);
        }
        return out;
    }

    private static String loadMachineId() {
        Path f = Path.of(System.getProperty("user.home"), ".mediastudio", "machine-id");
        try {
            if (Files.exists(f)) {
                String id = Files.readString(f).trim();
                if (id.matches("[a-z0-9]{8,32}")) return id;
            }
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Files.createDirectories(f.getParent());
            Files.writeString(f, id);
            return id;
        } catch (IOException e) {
            return "m" + Integer.toHexString(System.getProperty("user.home").hashCode());
        }
    }
}
