package com.mediastudio.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Project {
    public String id;
    public String name;
    /** 16:9, 9:16 or 1:1 */
    public String aspect = "16:9";
    /** Slow zoom on still images. */
    public boolean kenBurns = true;
    public String topic = "";
    /**
     * Content niche: "story" (tales, fables: characters + moral), "facts" ("Bạn có biết?" science / space facts) or
     * "history" (wars, resistance, civil wars: fixed cause → forces → events → result → meaning structure, real facts only)
     * or "drama" (a pasted screenplay episode cut into shots, each line spoken in its own character's voice).
     */
    public static final java.util.Set<String> NICHES = java.util.Set.of("story", "facts", "history", "drama");

    /** Shots per drama episode: a 2–3 minute episode runs about 35–50 shots of 2–4 s. */
    public static final int MAX_DRAMA_SHOTS = 80;

    /** Most scenes the script step may write for this niche. */
    public static int maxScenes(String niche) {
        return "drama".equals(niche) ? MAX_DRAMA_SHOTS : MAX_SCENES;
    }

    /** Story scenes per post: TikTok allows 35 photos and the cover slide takes one. */
    public static final int MAX_SCENES = 34;

    /** Art style for cute chibi characters (the "chibi" option of the script step). */
    public static final String CHIBI_STYLE = "cute chibi 3D cartoon style, super-deformed characters about 2-3 heads tall, "
            + "big round head, large sparkling expressive eyes, small rounded body, soft pastel-rich colours, "
            + "smooth clean shading, lighting and palette following each scene mood, toy-like charm, detailed but simple backgrounds";
    public String niche = "story";
    /** Size of exported swipe photos: "video" (same as the video), "3:4" or "1:1". */
    public String slideRatio = "3:4";
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    public List<Scene> scenes = new ArrayList<>();
    /** Latest rendered file name inside output/. */
    public String lastRender;
    /** Latest swipe-through image set ("slides"): JPG file names and the zip, all inside output/. */
    public List<String> slideFiles = new ArrayList<>();
    public String slidesZip;
    /** The project's music cut from its start point, exported with the slides (null = no music). */
    public String slidesMusic;

    /**
     * "Style bible": fixed description of every character (face, hair, clothes, age) and the
     * art style / palette / lighting. Prepended to every image prompt so scenes stay consistent.
     */
    public String styleBible = "";

    /** Narration voice for this project (VieNeu voice name / Voicebox profile id); null = Settings default. */
    public String voice;
    /** Reading tempo for this project (0.5–2.0); null = Settings default. */
    public Double voiceSpeed;

    /** Drama series written in the app (ideas → outline → episode screenplays); null = the user pastes their own. */
    public DramaSeries drama;

    /** Drama: everyone who speaks, each with their own voice. Lines of a speaker not listed use the project voice. */
    public List<CastMember> cast = new ArrayList<>();

    public static class CastMember {
        /** As written in the shots' speaker field, e.g. "Khải". */
        public String name = "";
        /** "male" / "female" / "" — used to pick a fitting voice automatically. */
        public String gender = "";
        /** Age and manner of speaking, e.g. "42, deep, slow, cold". */
        public String note = "";
        /** VieNeu voice id / Voicebox profile id; null = picked automatically on the first voice run. */
        public String voice;
    }

    public CastMember castMember(String speaker) {
        if (speaker == null || speaker.isBlank()) return null;
        String k = speaker.trim();
        return cast.stream().filter(c -> c.name != null && c.name.trim().equalsIgnoreCase(k)).findFirst().orElse(null);
    }

    /**
     * true = "script" mode: no audio is made or used; each scene's narration is drawn on the picture
     * (in its calmest area, colours taken from the image) for a person to read aloud.
     */
    public boolean scriptMode = false;
    /** Background music (no vocals) file inside assets/, looped under the whole video; null = none. */
    public String musicFile;
    /** Music level 0.0–1.0 relative to the voice. */
    public Double musicVolume = 0.25;
    /** Seconds into the music file where it starts; the part before is cut off when rendering. */
    public Double musicStart = 0.0;

    /** Cover slide (first picture of the swipe-through set): story title, a one-line teaser and the scene whose
     *  picture is the background (1-based). Written by Claude when blank; editable. */
    public String coverTitle = "";
    public String coverTeaser = "";
    public Integer coverScene;
    /** TikTok caption (description + hashtags), generated by Claude and editable. Max 2200 chars. */
    public String tiktokCaption = "";
    /** Title of the TikTok photo post (TikTok's separate "title" field, max 90 chars), generated with the caption. */
    public String tiktokTitle = "";
    /** Uploads to TikTok made from this project. */
    public List<TiktokPost> tiktokPosts = new ArrayList<>();

    public static class TiktokPost {
        public String publishId;
        /** draft (inbox) or direct */
        public String mode;
        public String file;
        public String privacy;
        public String status;
        public String failReason;
        public List<String> postIds = new ArrayList<>();
        public Instant createdAt = Instant.now();
    }

    // --- Publishing queue (owned by the server: PublishQueue writes these, the project save does not) ---
    /** When the post should go out; null = not scheduled (posted only with "Đăng ngay"). */
    public Instant scheduledAt;
    /** null = not in the queue; QUEUED, PREPARING, READY, POSTING, POSTED, FAILED. */
    public String queueState;
    public String queueError;
    /** Step being worked on, e.g. "Tạo hình Flow". */
    public String queueStep;
    public String queuePrivacy = "SELF_ONLY";
    public boolean queueAiLabel = true;
    /** Scene count for the script step (null = niche default). */
    public Integer queueScenes;
    /** Posts list: write the script with cute chibi characters. */
    public boolean queueChibi;
    /** true = when ready, save the post as a TikTok Studio draft right away (no posting time, nothing published). */
    public boolean queueDraft;
    public Instant postedAt;

    /** Source images (book pages, illustrations, photos…) the video is built from; files in assets/. */
    public List<String> sources = new ArrayList<>();
    /** Text transcribed from the sources by the analysis (used verbatim for poems / stories). */
    public String sourceText = "";

    /** Reference images uploaded by the user; scenes must keep these characters' looks. */
    public List<CharacterRef> characters = new ArrayList<>();

    public CharacterRef character(String id) {
        return characters.stream().filter(c -> c.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + id));
    }

    /** URL of this project's own Google Flow project (one Media Studio project = one Flow project). */
    public String flowProjectUrl;

    // --- Canva ---
    /** Canva folder holding every design of this project ("Media Studio – <name>"). */
    public String canvaFolderId;
    /**
     * One Canva design per project, one page per scene at the video size. Generated images are placed on
     * their scene's page and exported from there (the image tool alone only returns a small thumbnail).
     */
    public String canvaDesignId;
    /** Canva media id used as imageReferences for every later scene (characters + style anchor). */
    public String styleRefMediaId;
    /** Scene whose image is the current style reference. */
    public String styleRefSceneId;

    public int width() {
        return switch (aspect) {
            case "9:16" -> 1080;
            case "1:1" -> 1080;
            default -> 1920;
        };
    }

    public int height() {
        return switch (aspect) {
            case "9:16" -> 1920;
            case "1:1" -> 1080;
            default -> 1080;
        };
    }

    /**
     * How long a scene lasts in the video. Automatic: voice length + 0.3 s (voice mode), words / 3 (script mode),
     * else the scene's own duration. A length set by hand wins, but never cuts a voice-over short.
     */
    public double sceneDuration(Scene s, boolean audioPresent) {
        double fixed = s.duration == null || s.duration <= 0 ? 4.0 : s.duration;
        boolean voice = !scriptMode && audioPresent && s.audioDuration != null;
        double auto = voice ? s.audioDuration + 0.3 : scriptMode ? Scene.scriptSeconds(s.narration, fixed) : fixed;
        if (s.customDuration != null && s.customDuration > 0.2) {
            return voice ? Math.max(s.customDuration, s.audioDuration + 0.3) : s.customDuration;
        }
        return auto;
    }

    public Scene scene(String sceneId) {
        return scenes.stream().filter(s -> s.id.equals(sceneId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Scene not found: " + sceneId));
    }
}
