package com.mediastudio.agent;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GeminiWatermarkTest {

    /** A 768x1376 picture with soft diagonal stripes (so it is not flat), optionally stamped with the sparkle. */
    private static BufferedImage picture(boolean stamped) throws Exception {
        int w = 768, h = 1376;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = 70 + (int) (40 * Math.sin((x + y) / 23.0));
                img.setRGB(x, y, (v << 16) | ((v + 20) << 8) | (v + 35));
            }
        }
        if (stamped) {
            BufferedImage m = ImageIO.read(GeminiWatermark.class.getResourceAsStream("/gemini-sparkle-alpha.png"));
            int x0 = w - GeminiWatermark.OFFSET - GeminiWatermark.SIZE / 2, y0 = h - GeminiWatermark.OFFSET - GeminiWatermark.SIZE / 2;
            for (int y = 0; y < GeminiWatermark.SIZE; y++) {
                for (int x = 0; x < GeminiWatermark.SIZE; x++) {
                    double a = m.getRaster().getSample(x, y, 0) / 255.0;
                    int p = img.getRGB(x0 + x, y0 + y);
                    int r = (int) Math.round(a * 255 + (1 - a) * ((p >> 16) & 255));
                    int g = (int) Math.round(a * 255 + (1 - a) * ((p >> 8) & 255));
                    int b = (int) Math.round(a * 255 + (1 - a) * (p & 255));
                    img.setRGB(x0 + x, y0 + y, (r << 16) | (g << 8) | b);
                }
            }
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void removesTheSparkleAndRestoresThePixels() throws Exception {
        BufferedImage clean = picture(false);
        byte[] result = GeminiWatermark.clean(png(picture(true)));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result));
        int cx = 768 - GeminiWatermark.OFFSET, cy = 1376 - GeminiWatermark.OFFSET;
        int maxDiff = 0;
        for (int y = cy - 30; y < cy + 30; y++) {
            for (int x = cx - 30; x < cx + 30; x++) {
                int a = clean.getRGB(x, y), b = out.getRGB(x, y);
                for (int s = 0; s <= 16; s += 8) maxDiff = Math.max(maxDiff, Math.abs(((a >> s) & 255) - ((b >> s) & 255)));
            }
        }
        assertTrue(maxDiff <= 3, "pixels restored within rounding, max diff " + maxDiff);
    }

    @Test
    void leavesPicturesWithoutTheMarkUntouched() throws Exception {
        byte[] in = png(picture(false));
        assertSame(in, GeminiWatermark.clean(in), "no mark → same bytes back");
    }

    @Test
    void unreadableDataIsReturnedAsIs() {
        byte[] junk = new byte[2000];
        assertSame(junk, GeminiWatermark.clean(junk));
    }
}
