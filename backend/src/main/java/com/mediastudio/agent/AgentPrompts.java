package com.mediastudio.agent;

import com.mediastudio.project.CharacterRef;
import com.mediastudio.project.Project;
import com.mediastudio.project.Scene;

import java.nio.file.Path;
import java.util.List;

/** Prompt templates handed to Claude CLI for each kind of task. */
final class AgentPrompts {
    private AgentPrompts() {}

    /** Result file the Canva task writes so the backend can read ids back reliably. */
    static final String CANVA_RESULT = ".canva-result.json";

    static String system(Project p) {
        return """
                You are the automation engine of "Media Studio", a desktop app that assembles narrated videos.
                The current working directory is the project folder:
                  assets/     final media for scenes (images, video clips, audio)
                  downloads/  where the Playwright browser saves downloads and screenshots
                  output/     rendered videos (do not touch)
                Project: "%s", aspect ratio %s (%dx%d).
                Work autonomously: never ask the user questions, there is no one to answer.
                The user already approved every action this task describes (creating folders, generating images,
                exporting, moving items into the project folder), so do not stop to ask for confirmation.
                If something blocks you (not signed in, quota reached, captcha), stop and explain clearly in your final message.
                Keep your final message short.
                """.formatted(p.name, p.aspect, p.width(), p.height());
    }

    /**
     * Full prompt for an image generator: the project's style bible (fixed character looks + art style)
     * followed by the scene. Every scene shares the same first block, which keeps them consistent.
     */
    static String imagePrompt(Project p, Scene s) {
        String bible = p.styleBible == null ? "" : p.styleBible.trim();
        String scene = s.imagePrompt == null ? "" : s.imagePrompt.trim();
        String tail = "\n\n" + composition(p.aspect) + "\n" + QUALITY;
        if (bible.isEmpty()) return scene + tail;
        if ("facts".equals(p.niche)) {
            return "VISUAL STYLE (identical in every picture of this video):\n" + bible + "\n\nTHIS PICTURE:\n" + scene + tail;
        }
        return "STYLE AND CHARACTERS (keep exactly identical in every scene):\n" + bible
                + "\n\nTHIS SCENE (show ONLY the characters this scene names; any character described above but not"
                + " named here must NOT appear):\n" + scene + tail;
    }

    /** Generators sometimes put a landscape picture inside a blurred portrait frame for "wide shot": forbid it. */
    static String composition(String aspect) {
        String a = aspect == null ? "16:9" : aspect;
        String shape = "9:16".equals(a) ? "vertical portrait" : "1:1".equals(a) ? "square" : "horizontal landscape";
        return "COMPOSITION: ONE single full-bleed " + shape + " picture in " + a + " that fills the whole frame edge to edge."
                + " \"Wide shot\" means camera distance, not a wide image: compose the scene for the " + shape + " frame."
                + " No borders, letterboxing, blurred bands, frames, split panels or picture-in-picture."
                + " ABSOLUTELY NO TEXT anywhere in the image: no letters, words, captions, titles, speech bubbles, signs or"
                + " book-page text. Character names in this prompt are only labels for you: never write them in the picture."
                + " The picture is an illustration only, not a book page or poster.";
    }

    /** Added to every image prompt: the basic mistakes image generators make. */
    static final String QUALITY = "QUALITY RULES: correct anatomy for every person and animal: the right number of heads, arms, legs,"
            + " paws, wings and tails; hands with five fingers; no extra, missing, fused or twisted limbs; natural joints and proportions."
            + " Each character appears exactly once and looks exactly like their description (no swapped faces, outfits or colours,"
            + " no merged characters). Physically plausible: everything stands or rests on a surface (nothing floating unless the story"
            + " says so), consistent scale, perspective, light direction and shadows, objects do not pass through each other or melt"
            + " into the background, held objects are gripped by a hand or paw. No duplicated or cut-off characters at the frame edge.";

    /** How the agent checks a generated picture before keeping it. */
    static final String QUALITY_CHECK = """
            QUALITY CHECK before keeping an image: look at it closely (zoom into the result, or open the saved file with the
            Read tool) and check: the right characters, each exactly once and looking like their reference; correct number of
            heads, arms, legs, paws, fingers; no fused, twisted or floating body parts; nothing physically impossible (floating
            objects, wrong scale, objects passing through each other, broken perspective); nobody cut off at the edge.
            """;


    static final String ANALYSIS_FILE = "analysis.json";

    /**
     * Reads every source image and writes analysis.json: transcribed text, what kind of content it is,
     * the characters (with a pixel box to crop a reference image), visual style and a scene count.
     *
     * @param images file name → [width, height]
     */
    static String analyze(java.util.Map<String, int[]> images, List<CharacterRef> existing, String note) {
        StringBuilder list = new StringBuilder();
        images.forEach((f, wh) -> list.append("  - ./assets/").append(f)
                .append(wh == null ? "" : "  (" + wh[0] + "x" + wh[1] + " px)").append('\n'));
        String known = existing == null || existing.isEmpty() ? "none"
                : String.join(", ", existing.stream().map(c -> c.name).toList());
        boolean hasNote = note != null && !note.isBlank();
        String intro = images.isEmpty()
                ? """
                The SOURCE MATERIAL for a short narrated video is the TEXT FROM THE USER below (there are no images):
                it may be a story, a poem, a lesson, an outline or a list of scenes, possibly with instructions.
                Treat it as the content: "sourceText" is that work copied verbatim when it is a poem / story / lesson to
                be read as-is, and "" when it is an outline, a scene list or loose notes (then narrationMode "free").
                No character is drawn anywhere: every character has image null and box null, with an invented look.
                """
                : """
                These images are the SOURCE MATERIAL for a short narrated video (e.g. textbook pages, a poem, a story,
                illustrations, product photos, screenshots). Open EVERY image with the Read tool and study them together.
                """ + list + (hasNote ? """
                The user also typed text (below). It can be instructions (audience, tone, length) and / or content
                itself (a story, an outline, a list of scenes): use both the images and that text as the material.
                """ : "");
        return """
                %s
                TEXT FROM THE USER:
                %s

                Characters already defined in the project (do not repeat them): %s

                Write ./analysis.json (overwrite) as ONE JSON object:
                {
                  "title": "<short title in the narration language>",
                  "contentType": "poem | story | lesson | product | news | other",
                  "language": "<narration language, normally the language of the text in the images; default Vietnamese>",
                  "sourceText": "<the content text from the images transcribed VERBATIM, in reading order, keeping line and
                                  stanza breaks (\\n); include the title and author if shown; leave out page furniture such
                                  as textbook section labels (e.g. \\"Tập đọc\\", \\"Kể chuyện\\"), lesson numbers, page
                                  numbers, exercise questions and captions that are not part of the work; \\"\\" if none>",
                  "narrationMode": "verbatim | free",
                  "brief": "<3-6 sentences in the narration language: what the video tells, its message, tone and audience>",
                  "style": "<English description of the visual style of the illustrations (medium, palette, line work,
                            mood) so new images match them; if the images are photos, a fitting style>",
                  "scenes": <suggested number of scenes, 4-12, e.g. one per stanza / paragraph / key moment; when the
                             user's text lists scenes ("Cảnh 1", "Cảnh 2"…) or asks for a number, use exactly that
                             number (up to 40)>,
                  "characters": [
                    {
                      "name": "<name exactly as used in the text, e.g. \\"Em bé\\", \\"Bố\\", \\"Bà cụ\\">",
                      "description": "<in the narration language: role in the story + precise look (age, hair, face, outfit
                                      and colours, accessories). If not drawn, invent a look that fits the style.>",
                      "image": "<file name (without ./assets/) where this character is drawn most clearly, or null>",
                      "box": [x, y, width, height]
                    }
                  ]
                }
                Rules:
                - narrationMode "verbatim" when the material is a poem, song, story or lesson text whose wording should be
                  read as-is; "free" for products, news, loose notes or images without much text.
                - Include every recurring character: those drawn AND those only mentioned in the text (image null, box null).
                  Include important animals or objects that recur. Skip crowds and background extras.
                - "box" is the pixel rectangle of the WHOLE character (head to feet if visible) in that image, using the
                  image sizes listed above, origin top-left. Be generous rather than cutting the character off.
                  Use null when image is null.
                - Do not include characters listed as already defined.
                Write only that file.
                """.formatted(intro, hasNote ? note : "(none)", known);
    }

    /** Topic text for the storyboard step, built from the analysis. */
    static String storyboardTopicFromAnalysis(String brief, String sourceText, boolean verbatim, String userText) {
        if (!verbatim || sourceText == null || sourceText.isBlank()) {
            if (userText == null || userText.isBlank()) return brief;
            return brief + """


                TEXT FROM THE USER (follow it; when it lists scenes, keep exactly those scenes, their order and their
                facts, names and quotes, one video scene per listed scene):
                """ + userText;
        }
        return brief + """


                IMPORTANT: the narration of the MIDDLE scenes (between the introduction scene and the closing lesson
                scene) must use the SOURCE TEXT below EXACTLY, word for word, in order, without adding or changing
                words. Split it across those scenes (e.g. one stanza or a few lines per scene). Every line of the body
                must be used once; the title and author are spoken only in the first (introduction) scene, and the last
                scene is the only one whose narration is your own words (the lesson).
                Keep each verse / line of the source on its own line inside the narration ("\\n" between lines) so the
                voice pauses and the subtitles break at the same places.""" + """

                SOURCE TEXT:
                """ + sourceText;
    }

    /** Max reference images sent per scene (characters), besides the style anchor. */
    static final int MAX_CHARACTER_REFS = 3;

    /**
     * Characters whose reference images should be sent for this scene: the ones the scene prompt names,
     * minus any marked "&lt;name&gt; is NOT in this scene". A prompt with an explicit "Characters:" line that
     * names none of them gets none; an old-style prompt without that line falls back to the first ones.
     */
    static List<CharacterRef> sceneCharacters(Project p, Scene s) {
        if (p.characters == null || p.characters.isEmpty()) return List.of();
        String prompt = s.imagePrompt == null ? "" : s.imagePrompt;
        List<CharacterRef> hits = new java.util.ArrayList<>(namedCharacters(p, s));
        // Only characters with an actual reference image can be sent as image references.
        hits.removeIf(c -> c.file == null);
        if (hits.isEmpty() && !prompt.stripLeading().toLowerCase().startsWith("characters:")) {
            return p.characters.stream().filter(c -> c.file != null).limit(MAX_CHARACTER_REFS).toList();
        }
        return hits.stream().limit(MAX_CHARACTER_REFS).toList();
    }

    /**
     * Prompt that works with NO reference images: the shared block (style + every character's fixed look) comes
     * first and is word-for-word the same in every scene, so the generator draws the same people each time.
     * When the project has no style bible, the looks of the characters this scene names are written in instead.
     */
    static String textOnlyImagePrompt(Project p, Scene s) {
        String bible = p.styleBible == null ? "" : p.styleBible.trim();
        if (!bible.isEmpty()) {
            if ("facts".equals(p.niche)) return imagePrompt(p, s); // no recurring characters to keep
            return imagePrompt(p, s) + "\nDraw every character exactly as described in STYLE AND CHARACTERS: same face, hair,"
                    + " age, body, outfit and colours as in every other scene of this story.";
        }
        StringBuilder looks = new StringBuilder();
        for (CharacterRef c : namedCharacters(p, s)) {
            if (c.description == null || c.description.isBlank()) continue;
            looks.append("- ").append(c.name.trim()).append(": ").append(c.description.trim()).append('\n');
        }
        String scene = s.imagePrompt == null ? "" : s.imagePrompt.trim();
        String head = looks.isEmpty() ? "" : "CHARACTERS (keep exactly identical in every scene):\n" + looks + "\nTHIS SCENE:\n";
        return head + scene + "\n\n" + composition(p.aspect) + "\n" + QUALITY;
    }

    /** Characters the scene prompt names (whether or not they have a reference image), minus "X is NOT in this scene". */
    static List<CharacterRef> namedCharacters(Project p, Scene s) {
        if (p.characters == null || p.characters.isEmpty()) return List.of();
        String prompt = s.imagePrompt == null ? "" : s.imagePrompt;
        List<CharacterRef> hits = new java.util.ArrayList<>();
        for (CharacterRef c : p.characters) {
            if (c.name == null || c.name.isBlank()) continue;
            String name = java.util.regex.Pattern.quote(c.name.trim());
            int flags = java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE;
            String edge = "(?<![\\p{L}\\p{N}])" + name + "(?![\\p{L}\\p{N}])";
            boolean absent = java.util.regex.Pattern.compile(edge + "\\s+is\\s+NOT\\b", flags).matcher(prompt).find();
            boolean named = java.util.regex.Pattern.compile(edge, flags).matcher(prompt).find();
            if (named && !absent) hits.add(c);
        }
        return hits;
    }

    /** Extra storyboard instructions when the user supplied character reference images. */
    static String characterBrief(List<CharacterRef> chars) {
        if (chars == null || chars.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("""

                The user supplied REFERENCE IMAGES for these characters. They are the main characters of the story.
                Open every image with the Read tool BEFORE writing anything, study it, and in styleBible describe each
                character exactly as the image shows (face, skin tone, hair, eyes, body, outfit and colours, accessories),
                using the given name. If the images are illustrations, match their art style in STYLE unless the
                Visual style above says otherwise. Do not invent a different look for them. Use the user's notes
                (role, age, personality, relationships) in the story. Write each name EXACTLY as given below (same
                spelling and accents) in styleBible and in every scene's "Characters:" line: the app uses those names
                to decide which reference images to send for each scene.
                """);
        for (CharacterRef c : chars) {
            sb.append("  - ").append(c.name).append(": ")
                    .append(c.file == null ? "(no image — use the notes)" : "./assets/" + c.file);
            if (c.description != null && !c.description.isBlank()) sb.append("  (notes: ").append(c.description).append(")");
            sb.append('\n');
        }
        return sb.toString();
    }

    static String storyboard(String topic, int scenes, String language, String style, String aspect,
                             List<CharacterRef> chars) {
        return storyboard(topic, scenes, language, style, aspect, chars, false);
    }

    /** @param append true when the new scenes are added after existing ones: no intro / closing-lesson scene. */
    static String storyboard(String topic, int scenes, String language, String style, String aspect,
                             List<CharacterRef> chars, boolean append) {
        return storyboard(topic, scenes, language, style, aspect, chars, append, "story");
    }

    /** @param niche "story" (tale with characters and a moral), "facts" ("Bạn có biết?") or "history" (wars). */
    static String storyboard(String topic, int scenes, String language, String style, String aspect,
                             List<CharacterRef> chars, boolean append, String niche) {
        String full = "facts".equals(niche) ? storyboardFacts(topic, scenes, language, style, aspect)
                : "history".equals(niche) ? storyboardHistory(topic, scenes, language, style, aspect, chars)
                : storyboardFull(topic, scenes, language, style, aspect, chars);
        if (!append) return full;
        int a = full.indexOf("FIXED STRUCTURE");
        int b = full.indexOf("Write ./storyboard.json");
        return full.substring(0, a) + "These scenes are appended to an existing video: do NOT add an introduction or a closing"
                + " lesson scene.\n\n" + full.substring(b);
    }

    /**
     * "Bạn có biết?" niche: a hook question, one surprising but TRUE fact per scene, a closing question for comments.
     * No recurring characters: the style bible holds only the look, so every picture shares one style.
     */
    static String storyboardFacts(String topic, int scenes, String language, String style, String aspect) {
        return ("""
                Create a storyboard for a short "Did you know?" video (TikTok / Reels / Shorts) about science, space and
                nature facts. Its goal is that viewers watch to the end, then comment and share.

                Topic / brief: %s
                Number of scenes: %d (including the hook and the closing scene). If the topic names a number of facts
                (e.g. "5 sự thật…"), write exactly that many fact scenes plus the hook and the closing scene instead.
                Narration language: %s
                Visual style: %s
                Aspect ratio: %s

                ACCURACY (very important): only facts that are true and well established by science (NASA, ESA, textbooks,
                encyclopedias). No myths, no rumours, no made-up numbers. Use rounded numbers with "khoảng" / "hơn" when exact
                values vary. If a fact is often misunderstood, state the correct version. Never present a theory as certain.

                FIXED STRUCTURE (always):
                  - The FIRST scene is the HOOK (the first 3 seconds decide everything): one short, punchy sentence that
                    creates curiosity, e.g. "Bạn có biết trên sao Kim, một ngày dài hơn một năm?" or "99%% mọi người không
                    biết điều này về Mặt Trời…". Never start with a greeting or "Hôm nay chúng ta sẽ…". Its picture is the
                    most striking, dramatic image of the whole video.
                  - Each middle scene is ONE fact, ordered from interesting to most mind-blowing (keep the best for last).
                    Narration: state the fact, then one short "why / how" or a comparison people can picture
                    (e.g. "đủ để chứa 1,3 triệu Trái Đất"). Use quick transitions between facts ("Nhưng còn lạ hơn…",
                    "Và điều số 3 mới thật sự đáng sợ…") to keep people watching.
                  - The LAST scene is the closing: a question that invites comments (e.g. "Bạn thấy sự thật nào khó tin nhất?
                    Bình luận cho mình biết nhé!") and a short invitation to follow for more. Its picture is a calm, beautiful
                    wide shot fitting the topic.

                Write ./storyboard.json (overwrite it) as one JSON object:
                {
                  "styleBible": "...",
                  "scenes": [{"narration": "...", "imagePrompt": "..."}]
                }

                styleBible (English, 50-120 words) is prepended to every image prompt so all pictures look like one video:
                  - STYLE: one medium / art style, colour palette, lighting, mood (e.g. "cinematic photorealistic space art,
                    deep blues and glowing oranges, dramatic rim light, high detail"). Describe no characters.
                  - Always end with: "No text, no captions, no numbers, no labels, no watermarks in the image."

                For each scene:
                  - narration: 1-3 short, energetic sentences in the narration language (about 5-10 seconds read aloud),
                    written to be spoken: simple words, no lists, no parentheses.
                """ + WRITING_RULES + """
                  - imagePrompt (English): a vivid, scientifically plausible picture that SHOWS the fact, written as
                    "Subject: <main thing>. Setting: <where, background, light>. Details: <what makes the fact visible, e.g.
                    a scale comparison>. Framing: <shot type, angle>." Vary framing between scenes (extreme wide, close-up
                    macro, over-the-shoulder of a tiny astronaut for scale…). Never ask for text, diagrams with labels,
                    charts or numbers in the picture: the narration carries the information.
                Use only your own knowledge: do not search the web, do not run shell commands or scripts. Create the file directly
                with your file-writing tool. Write only that file, nothing else.
                """).formatted(topic, scenes, language,
                blankTo(style, "cinematic photorealistic, dramatic lighting, rich colours, high detail"), aspect);
    }

    /**
     * How narration is written in every niche. Digits are shown as-is in subtitles and slides; the voice step spells
     * them out (SpokenNumbers). Goes inside a .formatted() text, so "%" is written "%%".
     */
    static final String WRITING_RULES = """
                    WRITING RULES for the narration (except text that must stay word for word from a source):
                      - Write EVERY number with digits, never with words: years and dates ("năm 938", "ngày 12/3/938",
                        "mùa xuân năm 939", "thế kỷ 10"), quantities ("10.000 quân", "hơn 1.000 chiến thuyền",
                        "1,3 triệu", "40%%"), ages, distances, ordinals ("lần thứ 3"). Use "." between thousands and ","
                        for decimals, the Vietnamese way.
                      - Capitalise every proper name as a proper noun: people (Ngô Quyền, Lưu Hoằng Tháo), places, rivers,
                        mountains, countries, dynasties and states (sông Bạch Đằng, Cổ Loa, Đại Việt, nhà Nam Hán), battles,
                        titles used as names. Capitalise each syllable of Vietnamese names; keep common nouns before them
                        (sông, núi, nhà, trận) lowercase.
                """;

    /**
     * Maximum scenes of any storyboard: TikTok allows 35 photos per post and the cover slide is added on top,
     * so the story itself gets at most 34.
     */
    public static final int MAX_SCENES = com.mediastudio.project.Project.MAX_SCENES;

    /** Default and maximum scene count of a history video (the 7-part structure below is laid out for 34). */
    static final int HISTORY_SCENES = MAX_SCENES;

    /**
     * History niche (resistance wars, invasions, civil wars): always the same 7 parts so viewers understand WHY it
     * happened, HOW it unfolded and WHAT came of it. Only documented history: no invented events, numbers or quotes.
     */
    static String storyboardHistory(String topic, int scenes, String language, String style, String aspect,
                                    List<CharacterRef> chars) {
        int n = Math.max(7, Math.min(scenes, HISTORY_SCENES));
        return characterBrief(chars) + ("""
                Create a storyboard for a short narrated HISTORY video (TikTok / Reels / Shorts) about a war: a resistance
                against invaders, an uprising or a civil war. Viewers must come away understanding three things: WHY the
                war happened, HOW it unfolded, and WHAT came of it.

                Topic / brief: %s
                Number of scenes: EXACTLY %d (never more, never fewer).
                Narration language: %s
                Visual style: %s
                Aspect ratio: %s

                HISTORICAL ACCURACY (most important rule, it overrides everything else):
                  - Tell ONLY what really happened according to recognised history (official chronicles such as Đại Việt
                    sử ký toàn thư, Khâm định Việt sử thông giám cương mục, school history textbooks, encyclopedias, the
                    consensus of historians). Real people, real places, real dates, real outcomes.
                  - NEVER invent: no made-up events, battles, characters, dialogue, numbers, dates or quotes. No "giả sử",
                    "nếu như", "có lẽ là", no alternate history, no speculation presented as fact, no dramatised scenes that
                    did not happen. Only quote words that history actually records (e.g. a famous proclamation or oath),
                    and say who said them.
                  - When sources disagree or a number is uncertain, say so honestly with "theo sử cũ", "sử chép",
                    "khoảng", "ước tính", or leave the number out. Legends (e.g. a magic sword) are only told as legend,
                    with "tương truyền", never as fact.
                  - If the topic contains a mistake, follow the real history, not the mistake.
                  - Be fair and factual about every side: no insults, no hate, no glorifying violence or gore.

                FIXED STRUCTURE (always, for every history video, in this order). Laid out for 34 scenes; with a different
                scene count keep all 7 parts in the same order and scale each part's share (Part 4 always the longest):
                  PART 1 - HOOK (scenes 1-3, ~9%%):
                    1. A striking opening line: the most dramatic fact or number of the war, or a paradox ("Một đội quân
                       từng chinh phục nửa thế giới… lại thất bại ở đây."). Never a greeting or "Hôm nay chúng ta sẽ…".
                       Its picture is the most dramatic moment of the decisive battle.
                    2. When, where and who against whom (year, place, the two sides).
                    3. The question the video answers ("Vì sao…?" / "Bằng cách nào…?"), to keep viewers watching.
                  PART 2 - BACKGROUND AND CAUSES (scenes 4-8, ~15%%):
                    4. The country before the war (dynasty, rulers, people's life).
                    5. The enemy (or the opposing faction in a civil war): who they were and how strong.
                    6. Their ambition or plan, or the internal conflict behind a civil war.
                    7. The spark: the event that started the war.
                    8. How the court and the people reacted, and the decision to fight: a recorded proclamation,
                       oath, council or call to arms.
                  PART 3 - THE TWO SIDES (scenes 9-12, ~12%%):
                    9. Our commander(s): who they were.
                    10. The enemy commander(s).
                    11. Comparing the forces (troops, weapons, terrain) with the real recorded figures.
                    12. Each side's strategy.
                  PART 4 - HOW IT UNFOLDED (scenes 13-26, ~41%%), in chronological order, with dates when known:
                    13-16. Early phase: the attack, the first battles, hardships or a strategic retreat.
                    17-20. Turning point: preparation and counter-attack; scene 20 shows the real clever tactic, ambush,
                           terrain or weather that changed the war (a true "twist" that keeps people watching).
                    21-26. The decisive battle(s): the main steps, the climax, the moment of victory or defeat.
                  PART 5 - RESULT (scenes 27-30, ~12%%):
                    27. The outcome of the decisive battle: losses, commanders captured, killed or fleeing.
                    28. How the war ended: treaty, withdrawal, unification.
                    29. What happened to the main figures afterwards.
                    30. The country after the war.
                  PART 6 - MEANING (scenes 31-33, ~9%%):
                    31. Why one side won (or lost): 2-3 core reasons.
                    32. Historical significance and lasting influence.
                    33. What remains today: relics, temples, festivals, street names.
                  PART 7 - CLOSING (scene 34):
                    34. One moving closing line and a call to follow for the next episode of the history series.
                       Its picture is a calm, majestic wide shot (a monument, a river, a sunrise over the battlefield).

                Write ./storyboard.json (overwrite it) as one JSON object:
                {
                  "styleBible": "...",
                  "scenes": [{"narration": "...", "imagePrompt": "..."}]
                }

                styleBible (English, 80-200 words) is prepended to every image prompt, so it must be self-contained:
                  - STYLE: one art style for the whole video, colour palette, lighting, mood (e.g. "epic cinematic
                    historical painting, muted earthy tones with fiery accents, dramatic volumetric light").
                  - ERA: the period's real clothing, armour, weapons, ships, architecture of each side (historically accurate
                    for that century and people; no anachronisms, no modern objects).
                  - CHARACTERS: each historical figure who appears in several scenes, with a fixed name (as written in the
                    narration, with correct diacritics) and a fixed look: age, face, hair / beard, body, armour or robe with
                    colours, one distinctive item. Never change these later.
                  - LOOK OF THE TWO SIDES (see VIETNAMESE LOOK below): write it into styleBible so every picture keeps it.
                  - Always end with: "No text, no captions, no numbers, no maps with labels, no watermarks in the image."

                VIETNAMESE LOOK (this channel tells Vietnamese history; every picture must feel unmistakably feudal Vietnam):
                  - People of Đại Việt (or Văn Lang / Âu Lạc / Giao Chỉ for earlier eras) have Vietnamese faces and wear the
                    real Vietnamese dress of that century: áo giao lĩnh / áo tứ thân / áo the, khăn vấn or khăn chít đầu,
                    nón lá, nón chóp for soldiers, áo bào and mũ cánh chuồn for mandarins, simple lacquered leather or scale
                    armour, red and yellow banners WITHOUT any writing; spears, crossbows (nỏ), long knives, wooden war boats
                    and stakes (cọc gỗ) as history records.
                  - Places look Vietnamese: rice paddies, bamboo hedges around villages, banyan trees, communal houses (đình)
                    and pagodas with curved roof corners (đầu đao), ironwood pillars, tam quan gates, citadel walls of
                    brick and laterite, wide rivers and estuaries, limestone karst mountains, misty jungle.
                  - Never draw Vietnamese people, buildings or armour in Chinese, Japanese or Korean style (no samurai armour,
                    no Forbidden-City palaces, no Qing queues on Vietnamese people). The enemy keeps its own real dress.
                  - OUR SIDE (Đại Việt leaders, soldiers, villagers): bright clear eyes, kind, gentle and honest faces,
                    warm calm resolve, brave and disciplined posture; heroes look noble and trustworthy.
                  - THE INVADERS: fierce scowling faces, heavy frowning brows, glaring narrowed eyes, harsh cruel sneers,
                    arrogant and menacing posture. Show this ONLY through expression, posture and lighting (darker,
                    colder light), never by exaggerating ethnic or racial features: no caricature.
                  - In a civil war between Vietnamese factions, both sides are Vietnamese and both look serious and
                    dignified, unless the topic / brief says otherwise.

                For each scene:
                  - narration: 1-3 clear sentences in the narration language (about 5-10 seconds read aloud), written to be
                    spoken: simple words, no lists, no parentheses.
                """ + WRITING_RULES + """
                    Link scenes so the story flows (cause → effect, "Nhưng…", "Ngay sau đó…").
                  - imagePrompt (English): "Characters: <historical figures from styleBible who appear, or none>. Setting:
                    <real place, Vietnamese era details, weather, time of day>. Action: <what happens, expressions: our side bright-eyed and
                    kind, invaders scowling and menacing>. Framing: <shot
                    type, angle>." Add "<name> is NOT in this scene." when a recurring figure is absent. Vary framing (epic
                    wide battle, medium shot of a council, close-up of a commander's face). Show the event the narration
                    describes; no gore. Never ask for text, labelled maps, charts or numbers in the picture.
                Use only your own knowledge: do not search the web, do not run shell commands or scripts. Create the file
                directly with your file-writing tool. Write only that file, nothing else.
                """).formatted(topic, n, language,
                blankTo(style, chars == null || chars.isEmpty()
                        ? "epic cinematic historical painting of feudal Vietnam, dramatic lighting, rich detail"
                        : "exactly the art style of the character reference images (same medium, rendering, palette)"),
                aspect);
    }

    private static String storyboardFull(String topic, int scenes, String language, String style, String aspect,
                                         List<CharacterRef> chars) {
        return characterBrief(chars) + ("""
                Create a storyboard for a short narrated video whose scenes must look like ONE consistent film:
                same characters (same face, hair, age, body, outfit) and the same art style in every scene.

                Topic / brief: %s
                Target number of scenes: %d (a minimum; see COMPLETE SCENES below, you may add more)
                Narration language: %s
                Visual style: %s
                Aspect ratio: %s

                FIXED STRUCTURE (always, whatever the topic):
                  - The FIRST scene is the introduction: the narration states the title of the story / poem / lesson
                    (plus the author if known) in one short sentence, e.g. "Câu chuyện: <title>". Its image is a fitting
                    title-style establishing shot of the main character(s) or setting, with no text in it.
                  - The LAST scene is the meaning: the narration gives the moral, lesson or message of the work in 1-2
                    warm, simple sentences (written by you, in the narration language, even when the middle scenes use
                    the source text word for word). Its image is a calm, emotional closing shot.
                  - The story itself fills the scenes in between. The scene count above INCLUDES these two scenes.

                Write ./storyboard.json (overwrite it) as one JSON object:
                {
                  "styleBible": "...",
                  "scenes": [{"narration": "...", "imagePrompt": "..."}]
                }

                styleBible (English, 80-200 words) is prepended to every image prompt, so it must be self-contained:
                  - STYLE: medium/art style, colour palette, lighting, camera/lens look, mood. One style only.
                  - CHARACTERS: for each recurring character a fixed name and a precise visual description:
                    gender, age, ethnicity, face shape, hair (colour, length, style), eye colour, body type,
                    a signature outfit with colours, and one distinctive accessory. Never change these later.
                  - SETTING: recurring locations described once if they appear in several scenes.
                  - Always end with: "No text, no captions, no watermarks in the image."

                For each scene:
                  - narration: the voice-over, 1-3 natural sentences in the narration language (about 6-12 seconds read aloud).
                """ + WRITING_RULES + """
                  - imagePrompt (English): ONLY what is specific to this scene. Start with
                    "Characters: <names from the styleBible that appear>." and, when a styleBible character is
                    absent for story reasons (lost, not yet met, elsewhere), add "<name> is NOT in this scene."
                    Then their action and expression, the location, time of day, framing (wide / medium / close-up)
                    and camera angle. Do not re-describe the characters' looks or the art style and do not
                    introduce new looks; vary framing between scenes. Make the picture match the story beat
                    (e.g. a lost pet must not be visible before it is found).

                COMPLETE SCENES (very important): every scene's picture must show ALL the characters that its narration
                mentions or that take part in that moment, together in the same frame, AND the surroundings that the
                narration implies (place, background, objects, weather, time of day).
                  - Before writing a scene, list who is in it and what the surroundings are. Then write the imagePrompt as:
                    "Characters: <every one of them>. Setting: <the place and the main background elements, objects, weather,
                    light>. Action: <what each character does, expression>. Framing: <shot type, angle>." Name EVERY character
                    in the "Characters:" line and say where each stands in the frame (left / centre / right, foreground /
                    background) so nobody is cropped out or forgotten.
                  - Pick a framing that can hold everyone (wide or medium-wide shot). Never use a close-up that would cut a
                    character out.
                  - If one scene cannot show all its characters and its surroundings clearly (too many characters, or the
                    narration moves between places or in time), SPLIT it into two or more scenes instead of cramming.
                    Each new scene gets its own narration and imagePrompt. The Target number of scenes above is a minimum:
                    the final count may be larger. Keep the introduction first and the closing lesson last.
                  - When the narration must stay word for word (source text), split it at a natural line break and keep the
                    original order and wording; do not rewrite it to fit.
                Write only that file, nothing else.
                """).formatted(topic, scenes, language,
                blankTo(style, chars == null || chars.isEmpty() ? "cinematic, photorealistic"
                        : "exactly the art style of the character reference images (same medium, rendering, palette)"),
                aspect);
    }

    static String flow(Project p, Scene s, String flowUrl, String mediaType, String target, Path projectDir) {
        boolean video = "video".equals(mediaType);
        // One Media Studio project = one Flow project: reuse the stored address, otherwise create it once.
        String navStep = p.flowProjectUrl != null && !p.flowProjectUrl.isBlank()
                ? ("1. Navigate to " + p.flowProjectUrl + " . This is the Flow project that belongs to this video: work ONLY inside it"
                   + " and do NOT create a new project. The browser uses a persistent profile; if you see a Google sign-in page or a"
                   + " landing page asking to sign in, STOP and report \"NEED_LOGIN\". If the page says the project does not exist or"
                   + " cannot be opened, go to " + flowUrl + ", create a project named \"Media Studio - " + p.name + "\" and use that.\n"
                   + "2. Make sure you are inside that project's workspace (not the project list).\n")
                : ("1. Navigate to " + flowUrl + " . The browser uses a persistent profile; if you see a Google sign-in page"
                   + " or a landing page asking to sign in, STOP and report \"NEED_LOGIN\".\n"
                   + "2. Open the Flow project named \"Media Studio - " + p.name + "\" if it already exists, otherwise create it"
                   + " (keep all scenes of this video in that one project). Stay inside the project's workspace.\n");
        StringBuilder refs = new StringBuilder();
        // 1) STYLE ANCHOR: an already approved scene of this video, so the new image matches the existing ones.
        Path styleImg = styleAnchor(p, s, projectDir);
        boolean hasStyle = styleImg != null;
        if (hasStyle) {
            refs.append("     - STYLE ANCHOR (existing scene of this video; copy its art style): ")
                    .append(styleImg.toAbsolutePath()).append('\n');
        }
        // 2) Characters that appear in this scene.
        for (CharacterRef c : sceneCharacters(p, s)) {
            refs.append("     - CHARACTER ").append(c.name).append(": ")
                    .append(projectDir.resolve("assets").resolve(c.file).toAbsolutePath()).append('\n');
        }
        String refStep = refs.isEmpty() ? "" : """
                2b. REFERENCE IMAGES. Before generating, attach ALL of these images, in this order, so the result matches
                   the rest of the video (in video mode use "Ingredients to Video"; in image mode use the add-image /
                   reference button next to the prompt box). When the file chooser opens, call browser_file_upload with the
                   absolute path(s) below. If the tool accepts only a few images, keep the STYLE ANCHOR first, then the
                   characters. If the current mode really has no way to attach images, continue with text only and
                   mention it in your final message.
                %s""".formatted(refs);
        String matchLine = refs.isEmpty() ? "" : (hasStyle
                ? "\nSTYLE MATCH (important): the new image must look like it was drawn by the same artist as the style"
                  + " anchor image: same medium, same colour palette (do not introduce new colours), same line work,"
                  + " same paper/background and lighting. Keep each character's face, hair and outfit exactly as in their"
                  + " reference images, but compose a completely new scene as described.\n"
                : "\nKeep each character's face, hair and outfit exactly as in their reference images; compose a"
                  + " completely new scene as described.\n");
        return """
                Use the Playwright browser tools (mcp__playwright__*) to generate %s in Google Flow.

                %s%s3. Choose the %s mode, set the aspect ratio closest to %s, and generate with this exact prompt:
                   ---
                   %s%s
                   ---
                4. Wait for generation to finish (take snapshots periodically; %s).
                4b. %s
                   Flow usually returns several variants: pick the one with NO defect. If every variant has a clear defect,
                   generate ONE more time with the same prompt plus a short sentence naming the defect to avoid (e.g. "Exactly
                   two arms per character."). Never more than one extra generation; then keep the best one available.
                5. Save the chosen result to the file %s (relative to the current directory):
                   - Preferred: use the result's download button; downloaded files land in ./downloads/.
                     Then move/rename the downloaded file to %s with Bash (mv).
                   - Fallback for images only: take an element screenshot of the generated image with
                     browser_take_screenshot (filename "scene-%s-flow.png"), it is saved in ./downloads/,
                     then move it to %s.
                6. Confirm the file exists with ls. Then write the file ./%s (JSON) with exactly
                   {"projectUrl":"<the full address of the Flow project page you worked in, as shown by the browser>"}
                   and close the browser (browser_close).
                Finish with one line: SAVED <path> or FAILED <reason>.
                """.formatted(
                video ? "a short video clip" : "an image",
                navStep,
                refStep,
                video ? (refs.isEmpty() ? "video (text-to-video)" : "video (Ingredients to Video)") : "image",
                p.aspect,
                imagePrompt(p, s), matchLine,
                video ? "video can take a few minutes" : "usually under a minute",
                QUALITY_CHECK.strip().replace("\n", "\n                   "),
                target, target, s.id, target, FLOW_RESULT);
    }

    /**
     * One Claude run that makes SEVERAL scenes in ONE browser session: Flow is opened once, the reference images are
     * uploaded once (they stay in the Flow project's library) and each scene only picks the ones it needs.
     *
     * @param targets scene id → file to save (relative to the project folder), in scene order
     */
    static String flowBatch(Project p, List<Scene> scenes, java.util.Map<String, String> targets, String flowUrl,
                            Path projectDir) {
        // Style anchor: the ⭐ scene, else the first scene that already has a picture; never a scene made in this batch.
        java.util.Set<String> batch = new java.util.HashSet<>();
        for (Scene s : scenes) batch.add(s.id);
        Path anchor = null;
        if (p.styleRefSceneId != null && !batch.contains(p.styleRefSceneId)) {
            anchor = p.scenes.stream().filter(x -> x.id.equals(p.styleRefSceneId)).findFirst()
                    .map(x -> stillImage(projectDir, x)).orElse(null);
        }
        if (anchor == null) {
            for (Scene x : p.scenes) {
                if (batch.contains(x.id)) continue;
                anchor = stillImage(projectDir, x);
                if (anchor != null) break;
            }
        }
        // Characters used by any scene of the batch, uploaded once.
        java.util.LinkedHashMap<String, CharacterRef> chars = new java.util.LinkedHashMap<>();
        for (Scene s : scenes) for (CharacterRef c : sceneCharacters(p, s)) chars.putIfAbsent(c.id, c);

        StringBuilder uploads = new StringBuilder();
        if (anchor != null) {
            uploads.append("   - STYLE ANCHOR (existing scene of this video; copy its art style): ")
                    .append(anchor.toAbsolutePath()).append('\n');
        }
        for (CharacterRef c : chars.values()) {
            uploads.append("   - CHARACTER ").append(c.name).append(": ")
                    .append(projectDir.resolve("assets").resolve(c.file).toAbsolutePath()).append('\n');
        }

        StringBuilder list = new StringBuilder();
        int n = 0;
        for (Scene s : scenes) {
            n++;
            StringBuilder names = new StringBuilder();
            for (CharacterRef c : sceneCharacters(p, s)) names.append(names.isEmpty() ? "" : ", ").append(c.name);
            list.append("SCENE ").append(n).append("/").append(scenes.size()).append("  (save as ").append(targets.get(s.id)).append(")\n")
                    .append("  Reference images to attach: ")
                    .append(anchor != null ? "STYLE ANCHOR" : n == 1 ? "(none yet)" : "the first image generated in this session (as style anchor)")
                    .append(names.isEmpty() ? "" : " + characters: " + names).append('\n')
                    .append("  Prompt (use exactly the text between the lines):\n  ---\n")
                    .append(imagePrompt(p, s)).append('\n')
                    .append(anchor != null || n > 1
                            ? "STYLE MATCH (important): same artist as the style anchor: same medium, same colour palette (no new colours), "
                              + "same line work, same paper/background and lighting. Keep each character's face, hair and outfit exactly as in "
                              + "their reference images, but compose a completely new scene as described.\n"
                            : "")
                    .append("  ---\n\n");
        }

        String nav = p.flowProjectUrl != null && !p.flowProjectUrl.isBlank()
                ? "Navigate to " + p.flowProjectUrl + " . This is the Flow project that belongs to this video: work ONLY inside it and do NOT create"
                  + " a new project. If it cannot be opened, go to " + flowUrl + ", create a project named \"Media Studio - " + p.name + "\" and use that."
                : "Navigate to " + flowUrl + " . Open the Flow project named \"Media Studio - " + p.name + "\" if it already exists, otherwise create it"
                  + " (all scenes of this video go in that one project).";

        return """
                Use the Playwright browser tools (mcp__playwright__*) to generate %d images in Google Flow, ONE AFTER ANOTHER,
                in a SINGLE browser session. Do NOT close the browser between scenes: close it only once, at the very end.

                SETUP (once):
                1. %s The browser uses a persistent profile; if you see a Google sign-in page or a landing page asking to sign in,
                   STOP and report "NEED_LOGIN".
                2. Stay inside the project's workspace. Set the image mode and the aspect ratio closest to %s once.
                3. REFERENCE IMAGES: upload each of these files ONCE (file chooser -> browser_file_upload with the absolute path).
                   They stay in the project's media library. For every scene afterwards, add the references that scene lists by
                   SELECTING them from the library / already-uploaded items: never upload the same file again, and never
                   re-upload images you generated earlier. If a scene lists no reference, attach none.
                %s
                SCENES (in this order, each saved before starting the next):
                %s
                FOR EACH SCENE:
                a. Select that scene's references, enter its prompt exactly, generate.
                b. Wait for the result (poll every few seconds; usually under a minute).
                b2. %s
                   Flow usually returns several variants: pick the one with NO defect. If every variant has a clear defect,
                   generate that scene ONE more time with the same prompt plus a short sentence naming the defect to avoid.
                   Never more than one extra generation per scene; then keep the best one available.
                c. Save the chosen result to the given file (relative to the current directory): use the result's download button
                   (downloads land in ./downloads/) then move/rename it with Bash (mv); fallback: browser_take_screenshot of the
                   image element, then move it. Confirm with ls.
                d. If a scene fails with an ordinary error, retry it once, then write it down as FAILED and continue with the next.
                e. If Flow shows "unusual activity", a CAPTCHA / verification, an account or quota restriction, or any message
                   saying automated use is detected: do NOT retry, do NOT try to get around it, do not open other pages to
                   work around it. Stop at once: write this scene and every remaining scene as FAILED with the reason
                   UNUSUAL_ACTIVITY, write the result file and close the browser.
                f. After a scene is saved, wait about 30 seconds (browser_wait_for) before starting the next one, so requests
                   are not sent back to back.
                Work efficiently: prefer targeted actions over full page snapshots, and do not re-read pages you already understand.

                AT THE END: write the file ./%s (JSON) with exactly
                {"projectUrl":"<full address of the Flow project page you worked in>","saved":["<scene numbers saved>"],"failed":["<scene numbers failed>"]}
                then close the browser (browser_close).
                Finish with one line per scene: "SCENE <n> SAVED <path>" or "SCENE <n> FAILED <reason>".
                """.formatted(scenes.size(), nav, p.aspect,
                uploads.isEmpty() ? "   (no reference images for this batch)\n" : uploads.toString(), list,
                QUALITY_CHECK.strip().replace("\n", "\n                   "), FLOW_RESULT);
    }

    /** The scene's picture when it is a still image file that exists, else null. */
    static Path stillImage(Path projectDir, Scene x) {
        if (x == null || x.mediaFile == null) return null;
        String f = x.mediaFile.toLowerCase();
        if (!(f.endsWith(".png") || f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".webp"))) return null;
        Path path = projectDir.resolve("assets").resolve(x.mediaFile);
        return java.nio.file.Files.exists(path) ? path : null;
    }

    /** Written by the Flow task so the backend can remember which Flow project belongs to this video. */
    static final String FLOW_RESULT = ".flow-result.json";

    /**
     * The image Flow should copy the look from: the project's ⭐ style-reference scene, else the closest earlier
     * scene that has an image, else the first scene that has one. Never the scene being generated.
     */
    static Path styleAnchor(Project p, Scene current, Path projectDir) {
        Path assets = projectDir.resolve("assets");
        java.util.function.Function<Scene, Path> img = sc -> {
            if (sc == null || sc.id.equals(current.id) || sc.mediaFile == null) return null;
            String f = sc.mediaFile.toLowerCase();
            if (!(f.endsWith(".png") || f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".webp"))) return null;
            Path path = assets.resolve(sc.mediaFile);
            return java.nio.file.Files.exists(path) ? path : null;
        };
        if (p.styleRefSceneId != null) {
            Scene ref = p.scenes.stream().filter(x -> x.id.equals(p.styleRefSceneId)).findFirst().orElse(null);
            Path r = img.apply(ref);
            if (r != null) return r;
        }
        int idx = -1;
        for (int i = 0; i < p.scenes.size(); i++) if (p.scenes.get(i).id.equals(current.id)) idx = i;
        for (int i = idx - 1; i >= 0; i--) {
            Path r = img.apply(p.scenes.get(i));
            if (r != null) return r;
        }
        for (Scene sc : p.scenes) {
            Path r = img.apply(sc);
            if (r != null) return r;
        }
        return null;
    }

    static String flowCheck(String flowUrl) {
        return """
                Use the Playwright browser tools (mcp__playwright__*) only.
                1. Navigate to %s and wait a few seconds for it to load.
                2. Take a snapshot. Decide whether the user is signed in to Google Flow:
                   signed in = you can see the Flow workspace / project list / "New project" or similar, or an account avatar;
                   not signed in = a Google sign-in page, a "Sign in" / "Create with Flow" landing button, or an access error.
                3. Close the browser (browser_close).
                Your final message must be exactly one line: LOGGED_IN <short evidence> or NEED_LOGIN <short evidence>.
                """.formatted(flowUrl);
    }

    static String canva(Project p, Scene s, String target) {
        String aspectEnum = switch (p.aspect) {
            case "9:16" -> "PORTRAIT_9_16";
            case "1:1" -> "SQUARE_1_1";
            default -> "LANDSCAPE_16_9";
        };
        String folderStep = p.canvaFolderId != null && !p.canvaFolderId.isBlank()
                ? "Use the existing Canva folder id " + p.canvaFolderId + " (do not create another)."
                : "Create a Canva folder with mcp__canva__create-folder: name \"Media Studio - " + p.name
                  + "\", parent_folder_id \"root\". Remember its folder_id.";
        // Character images first (identity), then the approved scene (art style), capped to keep the request sane.
        List<CharacterRef> chars = sceneCharacters(p, s);
        StringBuilder refs = new StringBuilder();
        StringBuilder refNames = new StringBuilder();
        for (CharacterRef c : chars) {
            refs.append(c.canvaMediaId != null ? "{\"type\":\"MEDIA\",\"id\":\"" + c.canvaMediaId + "\"}"
                    : "{\"type\":\"MEDIA\",\"id\":\"<media id of " + c.name + " from step 0>\"}").append(", ");
            refNames.append(refNames.isEmpty() ? "" : ", ").append(c.name);
        }
        boolean styleRef = p.styleRefMediaId != null && !p.styleRefMediaId.isBlank();
        if (styleRef) refs.append("{\"type\":\"MEDIA\",\"id\":\"").append(p.styleRefMediaId).append("\"}, ");
        String refStep;
        if (refs.isEmpty()) {
            refStep = "No reference image yet (this image becomes the style reference for the next scenes); omit imageReferences.";
        } else {
            String list = refs.substring(0, refs.length() - 2);
            refStep = """
                    Pass imageReferences: [%s].
                    %s%sAdd this sentence at the end of the prompt: "Keep every character's face, hair, skin tone, outfit
                    and colours exactly as in the reference images and keep the same art style; compose a completely new
                    scene as described above, do not copy a reference composition, and include only the characters this
                    scene names."
                    """.formatted(list,
                    chars.isEmpty() ? "" : "The first " + chars.size() + " reference image(s) show the characters " + refNames
                            + " (in that order). ",
                    styleRef ? "The last one is an approved earlier scene of this video (art style anchor). " : "");
        }
        StringBuilder upload = new StringBuilder();
        for (CharacterRef c : chars) {
            if (c.canvaMediaId != null) continue;
            upload.append("  - ").append(c.name).append(" (id ").append(c.id).append("): ./assets/").append(c.file).append('\n');
        }
        String uploadStep = upload.isEmpty() ? "Character reference images are already in Canva; skip this step."
                : """
                  Upload these character reference images to Canva, one at a time:
                  %s  For each: call mcp__canva__create-upload-url, then run with Bash
                    curl -s -X POST -H "Content-Type: application/octet-stream" --data-binary @"<file>" "<upload_url>"
                  and take the media id from the JSON response (a new upload URL is needed for every file).
                  Move each uploaded image into the project folder (after step 1) with mcp__canva__move-item-to-folder.
                  """.formatted(upload);

        String format = switch (p.aspect) {
            case "9:16" -> "Instagram Story";
            case "1:1" -> "Instagram Post (Square)";
            default -> "Presentation (16:9)";
        };
        int w = p.width(), h = p.height();
        String designStep = p.canvaDesignId != null && !p.canvaDesignId.isBlank()
                ? "Use the project's existing storyboard design " + p.canvaDesignId + " (do not create another)."
                : """
                  Create the project's storyboard design: mcp__canva__create-design with
                    brief "A plain blank canvas for video frames: solid black background, no text, no images, no decorations."
                    format "%s"
                  Poll mcp__canva__get-create-design-async-job (wait the polling_policy seconds with `sleep`) until completed.
                  If its page size is not %dx%d, use mcp__canva__resize-design (custom %dx%d) and use the resized design instead.
                  Move the design into the project folder with mcp__canva__move-item-to-folder.
                  Its first page is free to hold this scene.
                  """.formatted(format, w, h, w, h);
        String pageStep = s.canvaPageId != null && !s.canvaPageId.isBlank()
                ? "This scene already has page id " + s.canvaPageId + ": reuse it — delete the elements currently on it"
                  + " (delete_element on each element locator), then insert the new image. If that page no longer exists,"
                  + " add a new page as below."
                : "Use the first page if the design was just created and that page has no elements; otherwise append a"
                  + " new page with operation {\"type\":\"add_page\",\"width\":" + w + ",\"height\":" + h + "}, then"
                  + " re-read page_metadata to get the new page's id and page_number.";

        return """
                Use the Canva MCP tools (mcp__canva__*) to create the image for one scene of a video.
                All scenes of this video must look like the same film: same characters, same art style.
                Everything for this project lives in ONE Canva folder and ONE storyboard design (one page per scene).
                The user has pre-approved every step below, including committing edits to the storyboard design;
                do not wait for approval.

                Step 0 - character references. %s

                Step 1 - folder. %s

                Step 2 - storyboard design. %s

                Step 3 - generate. Call mcp__canva__generate-image with:
                  aspectRatio: "%s"
                  prompt: exactly the text between the lines below
                  ---
                  %s
                  ---
                  %s
                  generate-image only returns a jobId: poll mcp__canva__get-generate-image-job with that jobId
                  (run `sleep 5` with Bash between polls) until SUCCESS or FAILURE.
                  Keep the reusable media id from the result (e.g. "Its reusable Canva media id is MA...").
                  The image shown in that result is only a small preview; never save it.

                Step 4 - place on the scene's page. mcp__canva__read-design on the storyboard design with
                  open_transaction true and filter.fields ["page_metadata","design_content"].
                  %s
                  Then mcp__canva__edit-design (finalize "keep_open", page_index = that page's number) with
                    {"type":"insert_fill","page_id":"<page id>","asset_type":"image","asset_id":"<media id>",
                     "alt_text":"Scene %s","left":0,"top":0,"width":%d,"height":%d}
                  Check the returned thumbnail shows the image filling the page, then call edit-design again with
                  finalize "commit" and no operations.

                Step 5 - export full resolution. mcp__canva__export-design on the storyboard design:
                  format {"type":"png","pages":[<that page_number>],"width":%d,"height":%d}
                  then: curl -L -o "%s" "<the returned url>"   and confirm with ls.

                Step 5b - quality. Open the saved file with the Read tool.
                  %s
                  If it has a clear defect, go back to Step 3 ONCE: generate again with the same prompt plus one short sentence
                  naming the defect to avoid, then redo Steps 4-5 on the SAME page (replace the old image). Never more than one
                  extra generation; keep the better of the two.

                Step 6 - organise. Move the generated image (media id) into the project folder with
                  mcp__canva__move-item-to-folder.

                Step 7 - report. Write the file ./%s (this exact name; JSON, not the image) with exactly:
                  {"folderId":"<folder id>","designId":"<storyboard design id>","pageId":"<page id>","mediaId":"<media id>",
                   "characterMedia":{"<character id>":"<uploaded media id>", ...}}
                  (characterMedia lists only images uploaded in step 0; use {} if none.)

                Finish with one line: SAVED <path> or FAILED <reason>.
                Report "NEED_LOGIN" only if a Canva tool call itself says you are not authenticated.
                """.formatted(uploadStep, folderStep, designStep, aspectEnum, imagePrompt(p, s), refStep, pageStep,
                s.id, w, h, w, h, target, QUALITY_CHECK.strip().replace("\n", "\n                  "), CANVA_RESULT);
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
