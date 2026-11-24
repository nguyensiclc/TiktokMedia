package com.mediastudio.agent;

import com.mediastudio.project.CharacterRef;
import com.mediastudio.project.Project;
import com.mediastudio.project.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPromptsTest {

    private static CharacterRef c(String name) {
        CharacterRef r = new CharacterRef();
        r.name = name;
        r.file = "ref-" + r.id + ".png";
        return r;
    }

    private static List<String> names(Project p, String prompt) {
        Scene s = new Scene();
        s.imagePrompt = prompt;
        return AgentPrompts.sceneCharacters(p, s).stream().map(x -> x.name).toList();
    }

    @Test
    void everyImagePromptCarriesQualityRulesAndCheck() {
        Project p = new Project();
        p.name = "T";
        p.aspect = "9:16";
        p.styleBible = "STYLE: watercolour.";
        p.scenes = new java.util.ArrayList<>();
        Scene s = new Scene();
        s.imagePrompt = "Characters: Na. Setting: a field.";
        p.scenes.add(s);
        java.nio.file.Path dir = java.nio.file.Path.of("x");
        String flow = AgentPrompts.flow(p, s, "https://flow", "image", "assets/a.png", dir);
        String batch = AgentPrompts.flowBatch(p, List.of(s), java.util.Map.of(s.id, "assets/a.png"), "https://flow", dir);
        String canva = AgentPrompts.canva(p, s, "assets/a.png");
        for (String out : List.of(flow, batch, canva)) {
            org.junit.jupiter.api.Assertions.assertTrue(out.contains("QUALITY RULES"), out);
            org.junit.jupiter.api.Assertions.assertTrue(out.contains("QUALITY CHECK"), out);
        }
        org.junit.jupiter.api.Assertions.assertTrue(flow.contains("assets/a.png") && batch.contains(".flow-result.json"));
    }

    @Test
    void picksOnlyNamedCharactersAndHonoursNot() {
        Project p = new Project();
        p.characters.addAll(List.of(c("Bé Na"), c("Mẹ"), c("Ông Tư"), c("Miu")));

        assertEquals(List.of("Bé Na", "Mẹ"), names(p, "Characters: Bé Na, Mẹ. They walk in."));
        assertEquals(List.of("Bé Na"), names(p, "Characters: Bé Na. Mẹ is NOT in this scene. Close-up."));
        assertEquals(List.of("Ông Tư", "Miu"), names(p, "Characters: Ông Tư, Miu. bé na is NOT in this scene."));
        // explicit line naming nobody with a reference → no references
        assertEquals(List.of(), names(p, "Characters: none. Empty street at dawn."));
        // old prompt without a Characters line → first ones as fallback
        assertEquals(List.of("Bé Na", "Mẹ", "Ông Tư"), names(p, "A busy flower market at sunrise."));
    }

    @Test
    void wholeWordMatchOnly() {
        Project p = new Project();
        p.characters.addAll(List.of(c("An"), c("Lan")));
        // "An" must not match inside "Lan" or "Anh"
        assertEquals(List.of("Lan"), names(p, "Characters: Lan. Anh trai đứng xa."));
    }

    @Test
    void capsAtThreeReferences() {
        Project p = new Project();
        p.characters.addAll(List.of(c("A1"), c("B2"), c("C3"), c("D4")));
        assertEquals(3, names(p, "Characters: A1, B2, C3, D4.").size());
    }

    @Test
    void historyNicheHasSevenPartsRealFactsAndMax34() {
        // 34 scenes at most: TikTok takes 35 photos per post and the cover slide is the 35th.
        String h = AgentPrompts.storyboard("Bạch Đằng 938", 50, "Vietnamese", "", "9:16", List.of(), false, "history");
        org.junit.jupiter.api.Assertions.assertTrue(h.contains("EXACTLY 34") && h.contains("HISTORICAL ACCURACY")
                && h.contains("PART 7") && h.contains("giả sử") && h.contains("the heart of the video"));
        String a = AgentPrompts.storyboard("Bạch Đằng 938", 5, "Vietnamese", "", "9:16", List.of(), true, "history");
        org.junit.jupiter.api.Assertions.assertTrue(a.contains("HISTORICAL ACCURACY") && !a.contains("PART 1"));
        // every niche: digits + capitalised names, placeholders filled, "%" survives formatting
        for (String niche : List.of("story", "facts", "history")) {
            String s = AgentPrompts.storyboard("Bạch Đằng 938", 7, "Vietnamese", "", "9:16", List.of(), false, niche);
            org.junit.jupiter.api.Assertions.assertTrue(s.contains("WRITING RULES") && s.contains("\"40%\"")
                    && s.contains("Bạch Đằng 938") && !s.contains("%s") && !s.contains("%d"), niche);
        }
    }

    @Test
    void numbersAreSpelledOutForTheVoice() {
        org.junit.jupiter.api.Assertions.assertEquals("năm chín trăm ba mươi tám", com.mediastudio.voice.SpokenNumbers.vietnamese("năm 938"));
        org.junit.jupiter.api.Assertions.assertEquals("mười nghìn quân, một phẩy ba triệu, bốn mươi phần trăm",
                com.mediastudio.voice.SpokenNumbers.vietnamese("10.000 quân, 1,3 triệu, 40%"));
        org.junit.jupiter.api.Assertions.assertEquals("ngày mười hai tháng tư năm một nghìn không trăm lẻ năm",
                com.mediastudio.voice.SpokenNumbers.vietnamese("ngày 12/4/1005"));
        org.junit.jupiter.api.Assertions.assertEquals("lần thứ tư, hai mươi mốt, mười lăm, một trăm hai mươi tư nghìn",
                com.mediastudio.voice.SpokenNumbers.vietnamese("lần thứ 4, 21, 15, 124.000"));
        org.junit.jupiter.api.Assertions.assertEquals("Ngô Quyền", com.mediastudio.voice.SpokenNumbers.vietnamese("Ngô Quyền"));
    }

    @Test
    void factsNicheHasHookAccuracyAndNoCharacters() {
        String f = AgentPrompts.storyboard("vũ trụ", 6, "Vietnamese", "", "9:16", List.of(c("Bé Na")), false, "facts");
        org.junit.jupiter.api.Assertions.assertTrue(f.contains("HOOK") && f.contains("ACCURACY") && f.contains("99% mọi người"));
        org.junit.jupiter.api.Assertions.assertFalse(f.contains("Bé Na"));
        // appending keeps the accuracy rules but drops hook / closing
        String a = AgentPrompts.storyboard("vũ trụ", 3, "Vietnamese", "", "9:16", List.of(), true, "facts");
        org.junit.jupiter.api.Assertions.assertTrue(a.contains("ACCURACY") && !a.contains("HOOK"));
        // story stays the default
        org.junit.jupiter.api.Assertions.assertTrue(AgentPrompts.storyboard("t", 6, "Vietnamese", "", "9:16", List.of(), false).contains("moral"));

        Project p = new Project();
        p.niche = "facts";
        p.styleBible = "cinematic space art";
        Scene s = new Scene();
        s.imagePrompt = "Subject: Venus.";
        String img = AgentPrompts.textOnlyImagePrompt(p, s);
        org.junit.jupiter.api.Assertions.assertTrue(img.startsWith("VISUAL STYLE") && !img.contains("Draw every character"));
    }
}
