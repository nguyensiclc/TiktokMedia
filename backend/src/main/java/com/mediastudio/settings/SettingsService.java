package com.mediastudio.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class SettingsService {
    private final Path file = Path.of(System.getProperty("user.home"), ".mediastudio", "settings.json");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private volatile AppSettings current;

    public SettingsService() {
        current = load();
    }

    public AppSettings get() {
        return current;
    }

    public synchronized AppSettings update(AppSettings next) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), next);
            Files.createDirectories(next.workspace());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        current = next;
        return current;
    }

    private AppSettings load() {
        try {
            if (Files.exists(file)) {
                return mapper.readValue(file.toFile(), AppSettings.class);
            }
        } catch (IOException ignored) {
            // fall back to defaults on a corrupt file
        }
        return new AppSettings();
    }
}
