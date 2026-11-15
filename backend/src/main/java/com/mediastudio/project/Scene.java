package com.mediastudio.project;

import java.util.UUID;

public class Scene {
    public String id = UUID.randomUUID().toString().substring(0, 8);
    /** Voice-over text, sent to Voicebox and used for subtitles. */
    public String narration = "";
    /** Prompt used to generate the visual on Google Flow / Canva. */
    public String imagePrompt = "";
    /** File name inside assets/ (image or video). */
    public String mediaFile;
    /** Where the media came from: flow, canva, upload. */
    public String mediaSource;
    /** File name inside assets/ (wav). */
    public String audioFile;
    /** Seconds, measured with ffprobe. */
    public Double audioDuration;
    /** Duration used when there is no audio. */
    public Double duration = 4.0;
    /** Length set by hand in the editor (seconds); null = automatic (audio length, or words / 3 in script mode). */
    public Double customDuration;
    /** Reading pace in script mode: 3 words per second. */
    public static final double WORDS_PER_SECOND = 3.0;
    /** Shortest scene in script mode, so the text can fade in and out. */
    public static final double MIN_SCRIPT_SECONDS = 2.0;

    /** Seconds a scene is shown in script mode: words / 3, at least 2 s. Empty text uses {@code fallback}. */
    public static double scriptSeconds(String narration, double fallback) {
        String t = narration == null ? "" : narration.trim();
        if (t.isEmpty()) return fallback;
        return Math.max(MIN_SCRIPT_SECONDS, t.split("\\s+").length / WORDS_PER_SECOND);
    }

    /** Canva ids of the generated image (media) and its design, when the visual came from Canva. */
    public String canvaMediaId;
    public String canvaDesignId;
    /** Page of the project's Canva storyboard design that holds this scene. */
    public String canvaPageId;
}
