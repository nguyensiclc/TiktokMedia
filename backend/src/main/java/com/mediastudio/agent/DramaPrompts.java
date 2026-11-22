package com.mediastudio.agent;

import com.mediastudio.project.DramaSeries;

import java.util.List;

/**
 * Prompts that write a vertical short drama from nothing: three ideas, then the series outline, then one screenplay
 * per episode in the exact format the "Chia shot" step reads. The craft rules borrow what the blind tests of
 * awesome-short-drama-skills rewarded: a hook in the first seconds, short lines with subtext, emotion carried by
 * actions and objects, rounds of 3–4 episodes with evidence and counter-moves, every win paid for, payoffs that were
 * planted earlier, and a strong cliffhanger at every episode end.
 */
final class DramaPrompts {
    private DramaPrompts() {
    }

    static final String IDEAS_FILE = "drama-ideas.json";
    static final String SERIES_FILE = "drama-series.json";
    static final String EPISODE_FILE = "drama-episode.txt";

    /** Episodes per series and minutes per episode (TikTok / Reels / Shorts vertical drama). */
    static final int EPISODES = 12;

    private static final String AUDIENCE = """
            FORMAT: vertical 9:16 short drama for TikTok / Reels / YouTube Shorts in Vietnam, %d episodes of 2-3 minutes,
            made as a motion comic (one still picture per shot, every line voiced by its character). Viewers decide in the
            first 3 seconds whether to stay, and at the last second whether to open the next episode.
            Everything is in Vietnamese, set in today's Vietnam unless the genre asks for another time or place.
            Original story only: do not retell an existing film, drama, novel or real case, do not reuse their titles or
            names, and do not target real people or companies.
            """.formatted(EPISODES);

    static String ideas(String genre, String note, List<String> avoid) {
        String seen = avoid == null || avoid.isEmpty() ? "" : "\nAlready proposed, do NOT repeat these premises: " + String.join("; ", avoid) + "\n";
        return AUDIENCE + """

                TASK: propose 3 DIFFERENT ideas for a new series.
                Genre / wish of the user: %s
                Extra notes from the user: %s
                %s
                Each idea must have:
                  - a protagonist the audience roots for, pushed down hard in episode 1 (betrayed, framed, humiliated),
                  - a clear opponent and one personal betrayal (someone close),
                  - a secret that changes everything when revealed, and a countdown or deadline that keeps pressure on,
                  - room for a satisfying comeback ("vả mặt") in almost every episode.
                Make the three ideas differ in world (office, family, school, village, wealthy clan…), engine
                (revenge, hidden identity, rivalry, mystery…) and tone. Concrete beats generic: name the job, the place,
                the amount of money, the object that holds the secret.

                Write ./%s (overwrite it) as JSON:
                {"ideas": [{"title": "<short, catchy Vietnamese title>",
                            "logline": "<2 sentences: who, what happens to them, what they must do, by when>",
                            "hook": "<the first 5 seconds of episode 1 as one vivid image + line>",
                            "why": "<one sentence: why viewers keep swiping to the next episode>"}]}
                Write only that file.
                """.formatted(blank(genre, "drama ngắn hấp dẫn, bạn tự chọn dòng phù hợp TikTok Việt Nam"),
                blank(note, "(none)"), seen, IDEAS_FILE);
    }

    static String series(String genre, String note, DramaSeries.Idea idea) {
        return AUDIENCE + """

                TASK: build the series bible and the outline of all %d episodes for this idea.
                Title: %s
                Logline: %s
                Opening hook: %s
                Genre / wish of the user: %s
                Extra notes from the user: %s

                RULES (they decide whether viewers watch to the end):
                  - Three acts: pushed down (episodes 1-4), fighting back (5-8), the cards revealed (9-12).
                  - Rounds of 3-4 episodes. Each round: the protagonist finds a piece of evidence, the opponent strikes back,
                    and the round ends with a bigger turn. Write the evidence ladder: which proof appears in which episode.
                  - Every win costs the protagonist something (a friend, a job, trust, money). No free victories.
                  - Every big reveal is planted earlier (an object, a habit, a line). No lucky coincidences in the last act.
                  - Track who knows what: what the audience knows before the protagonist (dramatic irony) is a tool.
                  - Each episode: one reversal and one satisfying comeback moment. Its hook is a NEW fact, a threat or a
                    choice, never a vague mood; vary the kinds of hooks.
                  - 5-7 main characters, each with a goal and a secret tied to the central conflict; a few minor ones.
                  - Episode 12 pays off every thread and leaves one small door open for season 2.

                Write ./%s (overwrite it) as JSON:
                {"title": "...", "logline": "...", "tone": "<pace, mood, what kind of satisfaction>",
                 "characters": [{"name": "<as written in dialogue, e.g. Lâm An>", "age": "29", "role": "...",
                                 "goal": "...", "secret": "..."}],
                 "relationships": "<the main relationship axes in 2-4 sentences>",
                 "episodes": [{"n": 1, "title": "<2-4 words>", "events": "<2-3 sentences>",
                               "hook": "<the last beat of the episode>"}]}
                with exactly %d episodes. Write only that file.
                """.formatted(EPISODES, idea.title, idea.logline, idea.hook, blank(genre, "(free)"), blank(note, "(none)"),
                SERIES_FILE, EPISODES);
    }

    /**
     * One episode's screenplay, in the format the shot step reads: scene headings, action lines, NAME: dialogue,
     * parenthetical directions, TIN NHẮN: / TEXT TRÊN MÀN HÌNH: for on-screen text.
     */
    static String episode(DramaSeries s, int n) {
        StringBuilder bible = new StringBuilder();
        bible.append("Title: ").append(s.title).append("\nLogline: ").append(s.logline)
                .append("\nTone: ").append(s.tone).append("\nCharacters:\n");
        for (DramaSeries.Person p : s.characters) {
            bible.append("  - ").append(p.name).append(" (").append(p.age).append("): ").append(p.role)
                    .append(". Goal: ").append(p.goal).append(". Secret: ").append(p.secret).append('\n');
        }
        bible.append("Relationships: ").append(s.relationships).append("\nOutline:\n");
        for (DramaSeries.Episode e : s.episodes) {
            bible.append("  ").append(e.n).append(". ").append(e.title).append(": ").append(e.events)
                    .append(" | Hook: ").append(e.hook).append('\n');
        }
        String previous = s.episodes.stream().filter(e -> e.n == n - 1 && !e.script.isBlank())
                .map(e -> "\nFULL SCREENPLAY OF EPISODE " + e.n + " (continue from where it ends; keep names, places, props and"
                        + " the way each character talks):\n<<<\n" + e.script + "\n>>>\n")
                .findFirst().orElse("");
        DramaSeries.Episode ep = s.episode(n);
        return AUDIENCE + """

                TASK: write the full screenplay of EPISODE %d.

                SERIES BIBLE:
                %s%s
                THIS EPISODE: %d. %s — %s
                It must end on this hook: %s

                CRAFT:
                  - Length 2-3 minutes: 4-6 scenes, about 18-30 lines of dialogue in total.
                  - The first beat (0:00) goes straight into conflict with a striking image; no slow introduction.
                    From episode 2 on, the opening can echo the previous hook in one beat, then move on.
                  - The middle presses the protagonist harder; include one reversal and one satisfying comeback moment.
                  - Lines are short (mostly under 15 words, never over 25), natural spoken Vietnamese with subtext; each
                    character has their own way of talking (formal, slangy "mày/tao" between close friends, cold…).
                  - Show emotion through actions and objects (a ring twisted, a cup knocked over), not inner thoughts.
                    No voice-over narrator.
                  - Every scene is easy to draw as still pictures: few locations, clear actions, close-ups for reactions.
                  - Plant what later episodes will pay off, as the outline says.
                  - Keep numbers natural; when you write a number in dialogue use digits for times, dates and amounts
                    ("23 giờ 14", "30 tỷ").

                EXACT FORMAT (plain text, no markdown, no bold, no bullet points), one item per line:
                  CẢNH 1 – NỘI. PHÒNG HỌP LỚN TẦNG 18 – SÁNG        (scene heading: CẢNH n – NỘI/NGOẠI. PLACE – TIME)
                  (0:00) Cận mặt một tập hồ sơ bị ném mạnh xuống bàn.   (action line; a timecode only on the first line of a scene)
                  KHẢI: Ngân sách quý bốn bị gửi thẳng cho đối thủ.     (NAME IN CAPITALS: line)
                  KHẢI: (cười nhẹ) Thì đã đủ lâu để biết mình nên nghỉ. (direction in brackets inside the line)
                  Insert: tay Diệp lặng lẽ xoay chiếc nhẫn.             (insert / close-up of an object)
                  TIN NHẮN: Xong rồi anh.                              (a text message on screen)
                  TEXT TRÊN MÀN HÌNH: Còn 30 ngày đến họp HĐQT.         (caption card)
                Leave one empty line between scenes. Start directly with "CẢNH 1"; no title, no notes before or after.
                End with the hook beat and "Cắt đen."

                Write the screenplay to ./%s (overwrite it). Write only that file.
                """.formatted(n, bible, previous, n, ep.title, ep.events, ep.hook, EPISODE_FILE);
    }

    private static String blank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }
}
