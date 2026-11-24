package com.mediastudio.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import com.mediastudio.settings.SettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Shares the posts list between computers through a synced folder (Google Drive / OneDrive), so the same topic is not
 * made twice. Only names and states travel, never pictures. Each computer writes only {@code posts-<machine id>.json},
 * so the cloud client never has two writers on one file. A project deleted here stays in the file (marked deleted),
 * so a topic already posted is still recognised after its pictures were cleaned up.
 */
@Service
public class PostSync {
    static final String PREFIX = "posts-";

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
    }

    /** The file of one computer. */
    public static class MachineFile {
        public String machineId;
        public String machine;
        public Instant updatedAt;
        public List<Entry> posts = new ArrayList<>();
    }

    private final ProjectService projects;
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final String machineId;
    private volatile String lastWritten = "";
    private volatile String lastError;
    private volatile boolean stop;

    public PostSync(ProjectService projects, SettingsService settings) {
        this.projects = projects;
        this.settings = settings;
        this.mapper = projects.mapper();
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
    void shutdown() {
        stop = true;
    }

    public boolean enabled() {
        String d = settings.get().syncDir;
        return d != null && !d.isBlank();
    }

    public Path dir() {
        return Path.of(settings.get().syncDir.trim());
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

    /** Writes this computer's file when something changed. */
    public synchronized void publish() {
        if (!enabled()) {
            lastError = null;
            return;
        }
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            lastError = "Không thấy thư mục " + dir + " (đã cài Google Drive / OneDrive và đồng bộ thư mục này chưa?)";
            return;
        }
        try {
            Path own = dir.resolve(PREFIX + machineId + ".json");
            Map<String, Entry> byId = new LinkedHashMap<>();
            // Keep what this computer published before, so deleted projects are still remembered.
            if (Files.exists(own)) {
                MachineFile old = mapper.readValue(own.toFile(), MachineFile.class);
                for (Entry e : old.posts) {
                    if (e.id == null) continue;
                    e.deleted = true;
                    byId.put(e.id, e);
                }
            }
            for (Project p : projects.list()) byId.put(p.id, entry(p));
            MachineFile f = new MachineFile();
            f.machineId = machineId;
            f.machine = machineName();
            f.posts = new ArrayList<>(byId.values());
            f.posts.sort(Comparator.comparing((Entry e) -> e.createdAt == null ? Instant.EPOCH : e.createdAt).reversed());
            String body = mapper.writeValueAsString(f.posts) + f.machine;
            if (body.equals(lastWritten) && Files.exists(own)) {
                lastError = null;
                return;
            }
            f.updatedAt = Instant.now();
            Path tmp = dir.resolve("." + PREFIX + machineId + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(f), StandardCharsets.UTF_8);
            Files.move(tmp, own, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastWritten = body;
            lastError = null;
        } catch (Exception e) {
            lastError = "Không ghi được danh sách vào thư mục đồng bộ: " + e.getMessage();
        }
    }

    /** The other computers' files (not this one). Unreadable files (a sync in progress) are skipped. */
    public List<MachineFile> others() {
        if (!enabled() || !Files.isDirectory(dir())) return List.of();
        List<MachineFile> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir())) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.startsWith(PREFIX) || !n.endsWith(".json") || n.equals(PREFIX + machineId + ".json")) continue;
                try {
                    MachineFile f = mapper.readValue(p.toFile(), MachineFile.class);
                    if (f.posts == null) f.posts = new ArrayList<>();
                    out.add(f);
                } catch (IOException ignored) {
                    // half-synced or conflicted copy: read it next time
                }
            }
        } catch (IOException e) {
            lastError = "Không đọc được thư mục đồng bộ: " + e.getMessage();
        }
        out.sort(Comparator.comparing((MachineFile f) -> f.machine == null ? "" : f.machine));
        return out;
    }

    /** This computer's own entries, including deleted projects it still remembers. */
    public List<Entry> own() {
        Map<String, Entry> byId = new LinkedHashMap<>();
        if (enabled()) {
            Path own = dir().resolve(PREFIX + machineId + ".json");
            try {
                if (Files.exists(own)) {
                    for (Entry e : mapper.readValue(own.toFile(), MachineFile.class).posts) {
                        if (e.id == null) continue;
                        e.deleted = true;
                        byId.put(e.id, e);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        for (Project p : projects.list()) byId.put(p.id, entry(p));
        return new ArrayList<>(byId.values());
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
        return e;
    }

    static String state(Project p) {
        boolean posted = "POSTED".equals(p.queueState)
                || p.tiktokPosts.stream().anyMatch(t -> "POSTED_VIA_STUDIO".equals(t.status) || "DRAFT_VIA_STUDIO".equals(t.status));
        if (p.queueState != null) {
            if ("POSTED".equals(p.queueState) && p.queueDraft) return "DRAFTED";
            return p.queueState;
        }
        if (posted) {
            boolean draftOnly = p.tiktokPosts.stream().noneMatch(t -> "POSTED_VIA_STUDIO".equals(t.status));
            return draftOnly ? "DRAFTED" : "POSTED";
        }
        return "NONE";
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
     * Same topic? Equal after normalising, or (with at least 3 meaningful words on each side) 80 % of the shorter
     * one's words are in the other. Different years or numbers always mean different topics (Bạch Đằng 938 ≠ 1288).
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
        List<MachineFile> all = new ArrayList<>();
        MachineFile me = new MachineFile();
        me.machine = machineName();
        me.machineId = machineId;
        me.posts = own();
        all.add(me);
        all.addAll(others());
        Map<String, List<Match>> out = new LinkedHashMap<>();
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) continue;
            List<Match> found = new ArrayList<>();
            for (MachineFile f : all) {
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
