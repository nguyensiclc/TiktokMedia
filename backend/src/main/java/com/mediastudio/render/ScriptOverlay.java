package com.mediastudio.render;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a scene's script text on a transparent PNG the size of the video frame.
 * The text goes where the picture is calmest (least detail) so it hides as little as possible, has no
 * background box, and its colours are taken from the picture: a light tint of the dominant hue on dark
 * areas, a deep shade of it on light areas, each with an outline in the opposite tone for legibility.
 */
final class ScriptOverlay {
    private ScriptOverlay() {}

    /** Bump when the look/placement of the text changes, so cached overlays and segments are rebuilt. */
    static final int VERSION = 4;

    private static final String[] FONTS = {"Segoe UI Semibold", "Segoe UI", "Arial", "Tahoma", "Verdana", "SansSerif"};

    /**
     * @param frame the picture as it appears in the video (any size, e.g. a 1/10 scale thumbnail), or null
     *              for a plain frame
     */
    static void render(String text, int w, int h, BufferedImage frame, Path out) throws IOException {
        System.setProperty("java.awt.headless", "true");
        text = Normalizer.normalize(text.trim(), Normalizer.Form.NFC);

        Font base = pickFont(text);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        FontRenderContext frc = g.getFontRenderContext();

        // Phone feeds (9:16) cover the top (search / tabs) and the bottom + right edge (caption, bio, buttons),
        // so portrait text stays inside a safe zone: 16%..70% of the height, 72% of the width.
        boolean portrait = h > w * 1.2;
        double zoneTop = portrait ? 0.16 : 0.06, zoneBottom = portrait ? 0.70 : 0.90;
        double maxWidth = w * (portrait ? 0.72 : 0.80);

        // Largest size (modest, ~4.2% of the short side) whose wrapped block fits in 20% of the height.
        float size = Math.round(Math.min(w, h) * 0.042);
        List<String> lines;
        Font font;
        double lineH;
        while (true) {
            font = base.deriveFont(Font.PLAIN, size);
            lines = wrap(text, font, frc, maxWidth);
            lineH = font.getSize2D() * 1.3;
            if (lines.size() * lineH <= h * 0.20 || size <= Math.min(w, h) * 0.026) break;
            size -= 2;
        }
        double blockH = lines.size() * lineH;

        // Where: calmest strip inside the safe zone.
        double yTop = h * zoneBottom - blockH; // default: bottom of the safe zone
        boolean darkBg = true;
        double lumUnder = 0.2;
        float hue = 0, sat = 0;
        if (frame != null) {
            Placement pl = place(frame, blockH / h, maxWidth / w, zoneTop, zoneBottom);
            yTop = pl.y0 * h;
            darkBg = pl.lum < 0.5;
            lumUnder = pl.lum;
            hue = pl.hue;
            sat = pl.sat;
        }

        // Maximum contrast with the picture under the text: near-white (lightly tinted by the picture's hue)
        // on dark areas, near-black on light areas, each with a thick outline and a soft halo in the opposite tone.
        // Guaranteed legibility: pure white text on dark areas / near-black on light areas, an outline in the
        // opposite tone, and a soft feathered scrim behind the block that is strongest on mid-tones and busy areas
        // (where the picture alone gives no contrast). The scrim is blurred, so it reads as a gentle shade, not a box.
        Color fill = darkBg ? new Color(255, 255, 255) : new Color(14, 14, 14);
        Color stroke = darkBg ? new Color(0, 0, 0) : new Color(255, 255, 255);
        float outline = (float) (size / 6.5);

        double maxLine = 0;
        for (String line : lines) maxLine = Math.max(maxLine, font.createGlyphVector(frc, line).getLogicalBounds().getWidth());
        double midTone = 1 - Math.min(1, Math.abs(lumUnder - 0.5) * 2); // 1 = grey mid-tone, 0 = pure black/white
        int scrimAlpha = (int) Math.round(255 * (0.38 + 0.32 * midTone));
        drawScrim(g, w, h, (w - maxLine) / 2, yTop, maxLine, blockH, size, withAlpha(darkBg ? Color.BLACK : Color.WHITE, scrimAlpha));

        double y = yTop;
        for (String line : lines) {
            GlyphVector gv = font.createGlyphVector(frc, line);
            double lw = gv.getLogicalBounds().getWidth();
            double x = (w - lw) / 2;
            double baseline = y + font.getSize2D() * 1.0;
            Shape s = gv.getOutline((float) x, (float) baseline);
            // soft halo
            for (float k : new float[]{2.8f, 2.0f, 1.45f}) {
                g.setStroke(new BasicStroke(outline * k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(withAlpha(stroke, k > 2.5 ? 45 : k > 1.8 ? 85 : 140));
                g.draw(s);
            }
            g.setStroke(new BasicStroke(outline, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(withAlpha(stroke, 255));
            g.draw(s);
            g.setColor(fill);
            g.fill(s);
            y += lineH;
        }
        g.dispose();
        ImageIO.write(img, "png", out.toFile());
    }

    /** Paints a blurred rounded rectangle (soft shade) around the text block onto g. */
    private static void drawScrim(Graphics2D g, int w, int h, double x, double y, double bw, double bh, float size, Color c) {
        int pad = Math.round(size * 0.7f);
        int blur = Math.max(3, Math.round(size * 0.9f)) | 1;
        int margin = blur * 2;
        int rx = (int) Math.max(0, Math.floor(x) - pad - margin), ry = (int) Math.max(0, Math.floor(y) - pad - margin);
        int rw = (int) Math.min(w - rx, Math.ceil(bw) + 2 * (pad + margin)), rh = (int) Math.min(h - ry, Math.ceil(bh) + 2 * (pad + margin));
        if (rw <= 0 || rh <= 0) return;
        BufferedImage s = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = s.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Transparent pixels carry the same RGB so blurring does not darken/grey the edges.
        sg.setComposite(AlphaComposite.Src);
        sg.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 0));
        sg.fillRect(0, 0, rw, rh);
        sg.setColor(c);
        sg.fillRoundRect((int) (x - rx - pad), (int) (y - ry - pad), (int) (bw + 2 * pad), (int) (bh + 2 * pad), pad * 2, pad * 2);
        sg.dispose();
        float[] k = new float[blur];
        java.util.Arrays.fill(k, 1f / blur);
        java.awt.image.ConvolveOp horiz = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(blur, 1, k), java.awt.image.ConvolveOp.EDGE_NO_OP, null);
        java.awt.image.ConvolveOp vert = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(1, blur, k), java.awt.image.ConvolveOp.EDGE_NO_OP, null);
        for (int i = 0; i < 2; i++) {
            s = horiz.filter(s, null);
            s = vert.filter(s, null);
        }
        g.drawImage(s, rx, ry, null);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    static Font pickFont(String text) {
        for (String name : FONTS) {
            Font f = new Font(name, Font.PLAIN, 40);
            if (f.getFamily().equalsIgnoreCase(name) || "SansSerif".equals(name)) {
                if (f.canDisplayUpTo(text) == -1) return f;
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 40);
    }

    /** Wraps by words, keeping the author's line breaks (poems). */
    static List<String> wrap(String text, Font font, FontRenderContext frc, double maxWidth) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\\R")) {
            para = para.trim();
            if (para.isEmpty()) continue;
            StringBuilder cur = new StringBuilder();
            for (String word : para.split("\\s+")) {
                String tryLine = cur.isEmpty() ? word : cur + " " + word;
                if (!cur.isEmpty() && width(font, frc, tryLine) > maxWidth) {
                    out.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(tryLine);
                }
            }
            if (!cur.isEmpty()) out.add(cur.toString());
        }
        return out;
    }

    private static double width(Font f, FontRenderContext frc, String s) {
        return f.createGlyphVector(frc, s).getLogicalBounds().getWidth();
    }

    private record Placement(double y0, double lum, float hue, float sat) {}

    /**
     * Finds the vertical position (fraction of height) for a block of heightFrac x widthFrac (centred)
     * with the least detail, and reports that area's brightness and the picture's dominant hue/saturation.
     */
    private static Placement place(BufferedImage im, double heightFrac, double widthFrac, double zoneTop, double zoneBottom) {
        int sw = im.getWidth(), sh = im.getHeight();
        double[][] lum = new double[sh][sw];
        double hx = 0, hy = 0, wsum = 0, satSum = 0;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int rgb = im.getRGB(x, y);
                int r = (rgb >> 16) & 255, gC = (rgb >> 8) & 255, b = rgb & 255;
                lum[y][x] = (0.2126 * r + 0.7152 * gC + 0.0722 * b) / 255.0;
                float[] hsb = Color.RGBtoHSB(r, gC, b, null);
                double wgt = hsb[1] * hsb[2] * (0.4 + hsb[1]); // vivid, bright pixels define the palette
                hx += Math.cos(hsb[0] * 2 * Math.PI) * wgt;
                hy += Math.sin(hsb[0] * 2 * Math.PI) * wgt;
                wsum += wgt;
                satSum += hsb[1] * hsb[2];
            }
        }
        float hue = 0;
        float sat = 0;
        if (wsum > 1e-6) {
            double ang = Math.atan2(hy, hx);
            if (ang < 0) ang += 2 * Math.PI;
            hue = (float) (ang / (2 * Math.PI));
            // How colourful the picture is overall: grey pictures get neutral text.
            sat = (float) Math.min(1.0, satSum / (sw * sh) * 3.0);
        }

        int bh = Math.max(2, (int) Math.round(heightFrac * sh));
        int x0 = (int) Math.round(sw * (1 - widthFrac) / 2), x1 = sw - x0;
        double bestCost = Double.MAX_VALUE, bestY = 1 - heightFrac - 0.06, bestLum = 0;
        double yMin = zoneTop, yMax = zoneBottom - heightFrac;
        if (yMax < yMin) yMax = yMin;
        for (double yf = yMin; yf <= yMax + 1e-9; yf += 0.02) {
            int y0 = (int) Math.round(yf * sh);
            int y1 = Math.min(sh - 1, y0 + bh);
            double busy = 0, mean = 0;
            int n = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1 - 1; x++) {
                    busy += Math.abs(lum[y][x + 1] - lum[y][x]) + Math.abs(lum[y + 1 > sh - 1 ? y : y + 1][x] - lum[y][x]);
                    mean += lum[y][x];
                    n++;
                }
            }
            if (n == 0) continue;
            busy /= n;
            mean /= n;
            // Slight preference for the lower part of the zone, where subtitles are normally read.
            double centre = 1 - (yf - yMin) / Math.max(1e-6, yMax - yMin);
            double cost = busy * (1 + 0.25 * centre) + 0.002 * centre;
            if (cost < bestCost) {
                bestCost = cost;
                bestY = yf;
                bestLum = mean;
            }
        }
        return new Placement(bestY, bestLum, hue, sat);
    }
}
