package com.mediastudio.project;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Stores each project as <workspace>/projects/<id>/project.json with assets/ and output/ folders. */
@Service
public class ProjectService {
    public static final Set<String> FOLDERS = Set.of("assets", "output", "downloads");

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ProjectService(SettingsService settings) {
        this.settings = settings;
    }

    public Path root() {
        return settings.get().workspace().resolve("projects");
    }

    public Path dir(String id) {
        if (!id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Bad project id");
        return root().resolve(id);
    }

    public Path folder(String id, String folder) {
        if (!FOLDERS.contains(folder)) throw new IllegalArgumentException("Bad folder");
        Path p = dir(id).resolve(folder);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /** Resolves a file inside a project folder, refusing path traversal. */
    public Path file(String id, String folder, String name) {
        Path base = folder(id, folder).toAbsolutePath().normalize();
        Path p = base.resolve(name).normalize();
        if (!p.startsWith(base)) throw new IllegalArgumentException("Bad file name");
        return p;
    }

    public List<Project> list() {
        if (!Files.isDirectory(root())) return List.of();
        try (Stream<Path> s = Files.list(root())) {
            return s.filter(p -> Files.exists(p.resolve("project.json")))
                    .map(p -> read(p.resolve("project.json")))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing((Project p) -> p.updatedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Project get(String id) {
        Path f = dir(id).resolve("project.json");
        if (!Files.exists(f)) throw new NoSuchElementException("Project not found: " + id);
        return read(f);
    }

    public Project create(String name, String aspect) {
        Project p = new Project();
        p.id = UUID.randomUUID().toString().substring(0, 12);
        p.name = name == null || name.isBlank() ? "Dự án mới" : name;
        if (aspect != null) p.aspect = aspect;
        p.scenes.add(new Scene());
        return save(p);
    }

    public synchronized Project save(Project p) {
        p.updatedAt = Instant.now();
        try {
            Files.createDirectories(dir(p.id));
            for (String f : FOLDERS) Files.createDirectories(dir(p.id).resolve(f));
            mapper.writeValue(dir(p.id).resolve("project.json").toFile(), p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /** Load-modify-save under a lock so background jobs don't clobber each other. */
    public synchronized Project mutate(String id, Consumer<Project> change) {
        Project p = get(id);
        change.accept(p);
        return save(p);
    }

    public void delete(String id) {
        Path d = dir(id);
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.walk(d)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    private Project read(Path f) {
        try {
            return mapper.readValue(f.toFile(), Project.class);
        } catch (IOException e) {
            return null;
        }
    }
}
