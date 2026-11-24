package com.mediastudio.tiktok;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StudioPosterTest {

    @Test
    void postPicksTheSoundAndHandlesStuckUploads() {
        String p = StudioPoster.prompt(List.of("a.png", "b.png"), "Tiêu đề", "Mô tả #lichsu", "Chỉ mình tôi", true, "Bài ca hy vọng");
        assertTrue(p.contains("\"Bài ca hy vọng\"") && p.contains("Thêm âm thanh"), p);
        assertTrue(p.contains("STUCK UPLOAD") && p.contains("at most 3 times"), p);
        assertFalse(p.contains("%s") || p.contains("%d"), p);
        // the draft version keeps the upload / sound steps and replaces only the Post click
        String d = StudioPoster.draftPrompt(p);
        assertTrue(d.contains("Save draft") && d.contains("STUCK UPLOAD"), d);
    }

    @Test
    void noSoundKeepsTikToksDefaultAndNichesHaveADefault() {
        String p = StudioPoster.prompt(List.of("a.png"), "", "", "Chỉ mình tôi", false, "");
        assertTrue(p.contains("do not change it unless the page refuses"), p);
        assertEquals("epic cinematic", StudioPoster.defaultSound("history"));
        assertEquals("nhạc nhẹ nhàng", StudioPoster.defaultSound(null));
    }
}
