package com.mediastudio.project;

import java.util.ArrayList;
import java.util.List;

/**
 * A drama series written inside the app, from a genre to ideas, an outline and one screenplay per episode.
 * The screenplay of an episode is what the "Chia shot" step cuts into shots.
 */
public class DramaSeries {
    /** What the user asked for, e.g. "Đấu đá công sở" plus free notes. */
    public String genre = "";
    public String note = "";
    /** The last three ideas proposed; one of them becomes the series. */
    public List<Idea> ideas = new ArrayList<>();

    // --- The chosen series (blank until an idea is picked) ---
    public String title = "";
    public String logline = "";
    public String tone = "";
    public List<Person> characters = new ArrayList<>();
    /** Who is tied to whom and how (betrayal, alliance, romance). */
    public String relationships = "";
    public List<Episode> episodes = new ArrayList<>();

    public static class Idea {
        public String title = "";
        public String logline = "";
        /** The first 5 seconds of episode 1. */
        public String hook = "";
        /** Why viewers keep swiping to the next episode. */
        public String why = "";
    }

    public static class Person {
        public String name = "";
        public String age = "";
        public String role = "";
        public String goal = "";
        public String secret = "";
    }

    public static class Episode {
        public int n;
        public String title = "";
        public String events = "";
        public String hook = "";
        /** Full screenplay, empty until written. */
        public String script = "";
    }

    public Episode episode(int n) {
        return episodes.stream().filter(e -> e.n == n).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không có tập " + n));
    }
}
