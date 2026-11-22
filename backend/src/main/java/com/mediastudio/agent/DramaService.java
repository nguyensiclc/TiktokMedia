package com.mediastudio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediastudio.job.Job;
import com.mediastudio.job.JobContext;
import com.mediastudio.job.JobService;
import com.mediastudio.project.DramaSeries;
import com.mediastudio.project.Project;
import com.mediastudio.project.ProjectService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a drama series for a user who has no script yet: genre → 3 ideas → series outline + episode 1 →
 * one screenplay per later episode. Each screenplay becomes the project's script topic, ready for "Chia shot".
 */
@Service
public class DramaService {
    private final ClaudeCli claude;
    private final ProjectService projects;
    private final JobService jobs;

    public DramaService(ClaudeCli claude, ProjectService projects, JobService jobs) {
        this.claude = claude;
        this.projects = projects;
        this.jobs = jobs;
    }

    /** Proposes 3 ideas for the genre; ideas proposed before for this project are not repeated. */
    public Job ideas(String projectId, String genre, String note) {
        return jobs.start("drama-ideas", "Gợi ý ý tưởng drama", projectId, null, ctx -> {
            Project p = projects.get(projectId);
            List<String> avoid = new ArrayList<>();
            if (p.drama != null) p.drama.ideas.forEach(i -> avoid.add(i.title + ": " + i.logline));
            ctx.log("▶ Claude nghĩ 3 ý tưởng" + (blank(genre) ? "" : " · " + genre.trim()));
            JsonNode root = write(projectId, DramaPrompts.ideas(genre, note, avoid), DramaPrompts.IDEAS_FILE, ctx);
            List<DramaSeries.Idea> list = new ArrayList<>();
            for (JsonNode n : root.path("ideas")) {
                DramaSeries.Idea i = new DramaSeries.Idea();
                i.title = text(n, "title");
                i.logline = text(n, "logline");
                i.hook = text(n, "hook");
                i.why = text(n, "why");
                if (!i.logline.isEmpty()) list.add(i);
            }
            if (list.isEmpty()) throw new IOException("AI không trả về ý tưởng nào");
            projects.mutate(projectId, pr -> {
                if (pr.drama == null) pr.drama = new DramaSeries();
                pr.drama.genre = genre == null ? "" : genre.trim();
                pr.drama.note = note == null ? "" : note.trim();
                pr.drama.ideas = list;
                pr.niche = "drama";
            });
            for (DramaSeries.Idea i : list) ctx.log("  • " + i.title + ": " + i.logline);
            ctx.log("✔ Chọn một ý tưởng để Claude viết dàn ý và tập 1");
        });
    }

    /**
     * Turns idea {@code index} (or a logline the user typed) into the series outline, then writes episode 1.
     * Replaces any series this project had.
     */
    public Job series(String projectId, Integer index, String customLogline) {
        Project p0 = projects.get(projectId);
        DramaSeries.Idea idea;
        if (!blank(customLogline)) {
            idea = new DramaSeries.Idea();
            idea.logline = customLogline.trim();
        } else {
            if (p0.drama == null || index == null || index < 0 || index >= p0.drama.ideas.size()) {
                throw new IllegalArgumentException("Hãy chọn một ý tưởng");
            }
            idea = p0.drama.ideas.get(index);
        }
        String label = blank(idea.title) ? idea.logline : idea.title;
        return jobs.start("drama-series", "Dàn ý drama: " + cut(label, 50), projectId, null, ctx -> {
            Project p = projects.get(projectId);
            String genre = p.drama == null ? "" : p.drama.genre;
            String note = p.drama == null ? "" : p.drama.note;
            ctx.log("▶ Claude viết hồ sơ nhân vật và dàn ý " + DramaPrompts.EPISODES + " tập");
            JsonNode root = write(projectId, DramaPrompts.series(genre, note, idea), DramaPrompts.SERIES_FILE, ctx);
            DramaSeries s = new DramaSeries();
            s.genre = genre;
            s.note = note;
            s.ideas = p.drama == null ? new ArrayList<>() : p.drama.ideas;
            s.title = text(root, "title").isEmpty() ? idea.title : text(root, "title");
            s.logline = text(root, "logline").isEmpty() ? idea.logline : text(root, "logline");
            s.tone = text(root, "tone");
            s.relationships = text(root, "relationships");
            for (JsonNode n : root.path("characters")) {
                DramaSeries.Person c = new DramaSeries.Person();
                c.name = text(n, "name");
                c.age = text(n, "age");
                c.role = text(n, "role");
                c.goal = text(n, "goal");
                c.secret = text(n, "secret");
                if (!c.name.isEmpty()) s.characters.add(c);
            }
            int k = 0;
            for (JsonNode n : root.path("episodes")) {
                DramaSeries.Episode e = new DramaSeries.Episode();
                e.n = n.path("n").asInt(++k);
                k = e.n;
                e.title = text(n, "title");
                e.events = text(n, "events");
                e.hook = text(n, "hook");
                s.episodes.add(e);
            }
            if (s.episodes.isEmpty()) throw new IOException("AI không viết được dàn ý");
            projects.mutate(projectId, pr -> {
                pr.drama = s;
                pr.niche = "drama";
                if (pr.name == null || pr.name.isBlank() || "Dự án mới".equals(pr.name)) pr.name = s.title;
            });
            ctx.log("  ✔ " + s.title + " · " + s.characters.size() + " nhân vật · " + s.episodes.size() + " tập");
            writeEpisode(projectId, s.episodes.get(0).n, ctx);
        });
    }

    /** Writes (or rewrites) the screenplay of episode n from the outline and the previous episode. */
    public Job episode(String projectId, int n) {
        Project p = projects.get(projectId);
        if (p.drama == null || p.drama.episodes.isEmpty()) throw new IllegalArgumentException("Chưa có dàn ý drama");
        p.drama.episode(n); // fails early for a missing episode
        return jobs.start("drama-episode", "Viết kịch bản tập " + n, projectId, null, ctx -> writeEpisode(projectId, n, ctx));
    }

    private void writeEpisode(String projectId, int n, JobContext ctx) throws Exception {
        DramaSeries s = projects.get(projectId).drama;
        boolean hasPrev = s.episodes.stream().anyMatch(e -> e.n == n - 1 && !e.script.isBlank());
        ctx.log("▶ Claude viết kịch bản tập " + n + (n > 1 && !hasPrev ? " (tập " + (n - 1) + " chưa viết, chỉ dựa vào dàn ý)" : ""));
        Path dir = projects.dir(projectId);
        Path out = dir.resolve(DramaPrompts.EPISODE_FILE);
        Files.deleteIfExists(out);
        claude.runWriting(DramaPrompts.episode(s, n), AgentPrompts.system(projects.get(projectId)), dir,
                List.of("Write", "Read"), false, ctx);
        if (!Files.exists(out)) throw new IOException("AI không tạo " + DramaPrompts.EPISODE_FILE);
        String script = Files.readString(out, StandardCharsets.UTF_8).strip();
        if (script.isEmpty()) throw new IOException("Kịch bản tập " + n + " trống");
        projects.mutate(projectId, pr -> {
            pr.drama.episode(n).script = script;
            pr.topic = script;
        });
        long lines = script.lines().filter(l -> l.matches("^[\\p{Lu}][\\p{Lu}\\p{M} 0-9()]*:.*") && !l.startsWith("CẢNH")).count();
        long scenes = script.lines().filter(l -> l.startsWith("CẢNH")).count();
        ctx.log("✔ Tập " + n + ": " + scenes + " cảnh, khoảng " + lines + " câu thoại. Kịch bản đã điền vào ô, bấm \"Chia shot\".");
    }

    private JsonNode write(String projectId, String prompt, String file, JobContext ctx) throws Exception {
        Path dir = projects.dir(projectId);
        Path out = dir.resolve(file);
        Files.deleteIfExists(out);
        claude.runWriting(prompt, AgentPrompts.system(projects.get(projectId)), dir, List.of("Write", "Read"), false, ctx);
        if (!Files.exists(out)) throw new IOException("AI không tạo " + file);
        return projects.mapper().readTree(out.toFile());
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).asText("").trim();
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
