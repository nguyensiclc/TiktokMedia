package com.mediastudio.render;

import com.mediastudio.job.JobContext;
import com.mediastudio.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class FfmpegService {
    private final SettingsService settings;

    public FfmpegService(SettingsService settings) {
        this.settings = settings;
    }

    /** Runs ffmpeg with the given args, streaming its log (minus progress spam) into the job. */
    public void run(List<String> args, Path workDir, JobContext ctx) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(settings.get().ffmpegPath);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-stats");
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).start();
        ctx.attach(p);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder buf = new StringBuilder();
            int c;
            String lastStats = null;
            // ffmpeg -stats uses \r between updates; only keep the final one.
            while ((c = r.read()) != -1) {
                if (c == '\r' || c == '\n') {
                    String line = buf.toString().trim();
                    buf.setLength(0);
                    if (line.isEmpty()) continue;
                    if (line.startsWith("frame=") || line.startsWith("size=")) lastStats = line;
                    else ctx.log("  " + line);
                } else {
                    buf.append((char) c);
                }
            }
            if (lastStats != null) ctx.log("  " + lastStats);
        }
        int code = p.waitFor();
        if (code != 0) throw new IOException("ffmpeg thoát với mã " + code);
    }

    /** Runs ffmpeg without a job (e.g. for the editor preview); throws if it fails. */
    public void runQuiet(List<String> args, Path workDir) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(settings.get().ffmpegPath);
        cmd.addAll(List.of("-hide_banner", "-loglevel", "error"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("ffmpeg lỗi: " + log.trim());
    }

    /** [width, height] of the first video/image stream, or null. */
    public int[] probeSize(Path file) {
        try {
            Process p = new ProcessBuilder(settings.get().ffprobePath, "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=width,height", "-of", "csv=p=0:s=x", file.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            String[] wh = out.lines().findFirst().orElse("").split("x");
            return new int[]{Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim())};
        } catch (Exception e) {
            return null;
        }
    }

    /** Duration in seconds, or null if ffprobe fails. */
    public Double probeDuration(Path file) {
        try {
            Process p = new ProcessBuilder(settings.get().ffprobePath, "-v", "error",
                    "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return Double.parseDouble(out.lines().findFirst().orElse(""));
        } catch (Exception e) {
            return null;
        }
    }
}
