package com.mediastudio.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeminiImageServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String OUT = "O".repeat(200);
    private static final String REF = "R".repeat(200);

    @Test
    void readsInteractionsOutputAndIgnoresEchoedInput() throws Exception {
        String body = "{\"input\":[{\"type\":\"image\",\"data\":\"" + REF + "\"}],"
                + "\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"image\",\"data\":\"" + OUT + "\"}]}]}";
        assertEquals(OUT, GeminiImageService.findImage(json.readTree(body)));
    }

    @Test
    void prefersOutputImageShortcut() throws Exception {
        String body = "{\"steps\":[],\"output_image\":{\"data\":\"" + OUT + "\"}}";
        assertEquals(OUT, GeminiImageService.findImage(json.readTree(body)));
    }

    @Test
    void readsGenerateContentShape() throws Exception {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"},{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + OUT + "\"}}]}}]}";
        assertEquals(OUT, GeminiImageService.findImage(json.readTree(body)));
    }

    @Test
    void skipsDraftThoughtImagesAndTakesTheLast() throws Exception {
        String draft = "D".repeat(200);
        String gc = "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"inlineData\":{\"mimeType\":\"image/jpeg\",\"data\":\"" + draft + "\"}},"
                + "{\"inlineData\":{\"mimeType\":\"image/jpeg\",\"data\":\"" + OUT + "\"},\"thoughtSignature\":\"" + "S".repeat(300) + "\"}]}}]}";
        assertEquals(OUT, GeminiImageService.findImage(json.readTree(gc)));
        String ia = "{\"steps\":[{\"type\":\"thought\",\"content\":[{\"type\":\"image\",\"data\":\"" + draft + "\"}]},"
                + "{\"type\":\"model_output\",\"content\":[{\"type\":\"image\",\"mime_type\":\"image/jpeg\",\"data\":\"" + OUT + "\"}]}]}";
        assertEquals(OUT, GeminiImageService.findImage(json.readTree(ia)));
    }

    @Test
    void noImage() throws Exception {
        assertNull(GeminiImageService.findImage(json.readTree("{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}")));
    }
}
