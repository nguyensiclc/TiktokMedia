package com.mediastudio.render;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.util.List;

/**
 * One swipe-through photo with its text.
 * 9:16: the text in its own band inside {@link PhoneSafeArea}, the picture edge to edge below it (see {@code phone}).
 * Other ratios: the text has its own band at the top and the picture sits whole below it, on the same picture
 * blurred and darkened.
 */
final class SlideCard {
    private SlideCard() {}

    static BufferedImage render(BufferedImage src, String text, int w, int h) {
        System.setProperty("java.awt.headless", "true");
        text = Normalizer.normalize(text == null ? "" : text.trim(), Normalizer.Form.NFC);
        // Prose sentences on separate lines read better as one paragraph (no 1-word lines);
        // short lines are kept as written (poems / lục bát).
        String[] paras = text.split("\\R+");
        if (paras.length > 1 && text.replaceAll("\\R+", "").length() / (double) paras.length > 45) {
            text = String.join(" ", paras).replaceAll("\\s+", " ");
        }
        if (h >= w * 1.7) return phone(src, text, w, h, true);   // 9:16
        if (h >= w * 0.95) return phone(src, text, w, h, false); // 3:4, 4:5, 1:1
        boolean portrait = h > w * 1.2;
        int shortSide = Math.min(w, h);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        FontRenderContext frc = g.getFontRenderContext();

        // Background: the picture covering the frame, blurred and darkened.
        g.drawImage(blurCover(src, w, h), 0, 0, null);
        g.setColor(new Color(0, 0, 0, 115));
        g.fillRect(0, 0, w, h);

        // Text goes ABOVE the picture: TikTok's caption / account name grow up from the bottom (a long caption can cover
        // a third of the screen) while the top only has the thin Following / For You tabs (~9%).
        double top = h * (portrait ? 0.095 : 0.04), bottom = h * (portrait ? 0.05 : 0.04);
        double gap = shortSide * 0.035;
        double maxTextW = w * (portrait ? 0.84 : 0.80);

        // Text block: ~4% of the short side, shrinking until it takes at most 24% of the height.
        Font base = ScriptOverlay.pickFont(text.isEmpty() ? "a" : text);
        float size = Math.round(shortSide * 0.040);
        Font font;
        List<String> lines;
        double lineH;
        while (true) {
            font = base.deriveFont(Font.PLAIN, size);
            lines = text.isEmpty() ? List.of() : ScriptOverlay.wrap(text, font, frc, maxTextW);
            lineH = size * 1.35;
            if (lines.size() * lineH <= h * 0.24 || size <= shortSide * 0.028) break;
            size -= 1;
        }
        double blockH = lines.isEmpty() ? 0 : lines.size() * lineH;

        // Picture: as large as the space below the text allows; up to 12% of its height may be trimmed
        // (more from the bottom than the top, where heads usually are) to keep it from getting too narrow.
        double availH = h - top - bottom - (blockH > 0 ? blockH + gap : 0);
        double availW = w * (portrait ? 0.92 : 0.94);
        double keep = 0.88;
        double scale = Math.min(availW / src.getWidth(), availH / (src.getHeight() * keep));
        int dw = (int) Math.round(src.getWidth() * scale);
        int fullH = (int) Math.round(src.getHeight() * scale);
        int dh = (int) Math.min(fullH, Math.round(availH));
        int cropTop = (int) Math.round((fullH - dh) * 0.35);
        // Centre picture + text vertically inside the usable area.
        double used = dh + (blockH > 0 ? gap + blockH : 0);
        int dx = (w - dw) / 2;
        double groupTop = top + (h - top - bottom - used) / 2;
        int dy = (int) Math.round(groupTop + (blockH > 0 ? blockH + gap : 0));

        float radius = shortSide * 0.03f;
        Shape clip = new RoundRectangle2D.Float(dx, dy, dw, dh, radius, radius);
        // soft shadow under the picture
        for (int i = 6; i >= 1; i--) {
            float spread = shortSide * 0.004f * i;
            g.setColor(new Color(0, 0, 0, 14));
            g.fill(new RoundRectangle2D.Float(dx - spread, dy - spread * 0.5f, dw + 2 * spread, dh + 2 * spread, radius + spread, radius + spread));
        }
        Shape oldClip = g.getClip();
        g.setClip(clip);
        g.drawImage(src, dx, dy - cropTop, dw, fullH, null);
        g.setClip(oldClip);

        // Text: white with a thin dark outline, centred above the picture.
        double y = groupTop;
        float outline = size / 9f;
        for (String line : lines) {
            GlyphVector gv = font.createGlyphVector(frc, line);
            double lw = gv.getLogicalBounds().getWidth();
            Shape s = gv.getOutline((float) ((w - lw) / 2), (float) (y + size));
            g.setStroke(new BasicStroke(outline * 2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, 70));
            g.draw(s);
            g.setStroke(new BasicStroke(outline, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, 170));
            g.draw(s);
            g.setColor(Color.WHITE);
            g.fill(s);
            y += lineH;
        }
        g.dispose();
        return out;
    }

    /** "card" (whole picture, smaller), "strip" (full width, cropped) or "fill" (full width down to the bottom). */
    static String phoneMode = "strip";

    /**
     * Text band on top, picture below; the text and the picture never overlap.
     * tall = 9:16, where TikTok covers the top and the bottom (see {@link PhoneSafeArea} and {@link #phoneMode}).
     * Otherwise (3:4, 1:1) TikTok shows the photo whole in the middle of the screen: thin top margin, picture to the
     * bottom edge. 9:16 details:
     * <ul>
     *   <li>the text has its own band at the top of {@link PhoneSafeArea} (below TikTok's search bar, and above the
     *       middle of the screen, where TikTok's like / share buttons start, so it can use the full width);</li>
     *   <li>the picture fills everything below the text, edge to edge, fading softly into the band. Its bottom part
     *       sits under TikTok's caption like any photo post, but no text is ever drawn on it.</li>
     * </ul>
     * Behind the text: the same picture blurred and darkened, so band and picture read as one image.
     */
    private static BufferedImage phone(BufferedImage src, String text, int w, int h, boolean tall) {
        String mode = tall ? phoneMode : "fill";
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        FontRenderContext frc = g.getFontRenderContext();

        // 1. Text layout first: it decides where the picture starts.
        double textL = w * 0.075, textW = w * 0.85;
        double textTop = tall ? h * (PhoneSafeArea.TOP + 0.005) : w * 0.075;
        Font font = null;
        List<String> lines = List.of();
        double lineH = 0;
        float size = Math.round(w * 0.046f);
        if (!text.isEmpty()) {
            Font base = ScriptOverlay.pickFont(text);
            while (true) {
                font = base.deriveFont(Font.PLAIN, size);
                lines = ScriptOverlay.wrap(text, font, frc, textW);
                lineH = size * 1.42; // room for stacked Vietnamese marks
                if (lines.size() * lineH <= (tall ? h * 0.17 : w * 0.30) || size <= w * 0.032f) break;
                size -= 1;
            }
        }
        double blockH = lines.size() * lineH;
        double fade = w * 0.06;
        int picTop = lines.isEmpty() ? 0 : (int) Math.round(textTop + blockH + w * 0.05);

        // 2. Background (band): the picture blurred and darkened.
        if (picTop > 0) {
            g.drawImage(blurCover(src, w, h), 0, 0, null);
            g.setColor(new Color(8, 10, 14, 165));
            g.fillRect(0, 0, w, h);
        }

        // 3. Picture.
        int safeBottom = (int) Math.round(h * PhoneSafeArea.BOTTOM);
        if (picTop > 0 && "card".equals(mode)) {
            // whole picture (up to 15% trimmed) as a rounded card inside [picTop, safeBottom]: nothing under the caption
            int availH = safeBottom - picTop;
            double keep = 0.85;
            double sc = Math.min(w * 0.92 / src.getWidth(), availH / (src.getHeight() * keep));
            int dw = (int) Math.round(src.getWidth() * sc), fullH = (int) Math.round(src.getHeight() * sc);
            int dh = Math.min(fullH, availH);
            int off = bestCrop(src, sc, fullH - dh, dh);
            int dx = (w - dw) / 2, dy = picTop + (availH - dh) / 2;
            float rr = w * 0.035f;
            for (int i = 6; i >= 1; i--) {
                float sp = w * 0.004f * i;
                g.setColor(new Color(0, 0, 0, 16));
                g.fill(new RoundRectangle2D.Float(dx - sp, dy - sp * 0.4f, dw + 2 * sp, dh + 2 * sp, rr + sp, rr + sp));
            }
            Shape oc = g.getClip();
            g.setClip(new RoundRectangle2D.Float(dx, dy, dw, dh, rr, rr));
            g.drawImage(src, dx, dy - off, dw, fullH, null);
            g.setClip(oc);
        } else if (picTop > 0 && "strip".equals(mode)) {
            // edge-to-edge strip inside [picTop, safeBottom], cropped to its most detailed rows
            int regionH = safeBottom - picTop;
            double sc = Math.max((double) w / src.getWidth(), (double) regionH / src.getHeight());
            int cw = (int) Math.ceil(src.getWidth() * sc), ch = (int) Math.ceil(src.getHeight() * sc);
            int off = bestCrop(src, sc, ch - regionH, regionH);
            BufferedImage pic = new BufferedImage(w, regionH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = pic.createGraphics();
            pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            pg.drawImage(src, (w - cw) / 2, -off, cw, ch, null);
            pg.setComposite(AlphaComposite.DstIn);
            pg.setPaint(new LinearGradientPaint(0, 0, 0, regionH, new float[]{0f, (float) (fade / regionH), 1f - (float) (fade / regionH), 1f},
                    new Color[]{new Color(0, 0, 0, 0), Color.BLACK, Color.BLACK, new Color(0, 0, 0, 0)}));
            pg.fillRect(0, 0, w, regionH);
            pg.dispose();
            g.drawImage(pic, 0, picTop, null);
        } else {
        // picture covers [picTop, h] edge to edge, cropped to its most detailed rows (9:16: in the uncovered part).
        int regionH = h - picTop;
        double s = Math.max((double) w / src.getWidth(), (double) regionH / src.getHeight());
        int cw = (int) Math.ceil(src.getWidth() * s), ch = (int) Math.ceil(src.getHeight() * s);
        BufferedImage pic = new BufferedImage(w, regionH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = pic.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        pg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int cleanH = tall ? (int) Math.max(0, h * PhoneSafeArea.BOTTOM - picTop) : (int) (regionH * 0.9);
        int offset = bestCrop(src, s, ch - regionH, cleanH);
        pg.drawImage(src, (w - cw) / 2, -offset, cw, ch, null);
        if (picTop > 0) {
            // soft top edge: the picture melts into the band instead of a hard line
            pg.setComposite(AlphaComposite.DstIn);
            pg.setPaint(new LinearGradientPaint(0, 0, 0, (float) fade, new float[]{0f, 1f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 255)}));
            pg.fillRect(0, 0, w, (int) Math.ceil(fade));
        }
        pg.dispose();
        g.drawImage(pic, 0, picTop, null);
        }

        // 4. Shades for TikTok's own overlays (9:16): search bar at the top, caption at the bottom.
        if (tall) {
        g.setPaint(new LinearGradientPaint(0, 0, 0, (float) (h * 0.20), new float[]{0f, 1f},
                new Color[]{new Color(0, 0, 0, 90), new Color(0, 0, 0, 0)}));
        g.fillRect(0, 0, w, (int) Math.ceil(h * 0.20));
        float shadeTop = (float) (h * 0.66);
        g.setPaint(new LinearGradientPaint(0, shadeTop, 0, h, new float[]{0f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 150)}));
        g.fillRect(0, (int) shadeTop, w, h - (int) shadeTop);
        }

        // 5. Text: white, centred in the band, a small gold rule above it.
        if (!lines.isEmpty()) {
            float ruleW = (float) (w * 0.08), ruleH = Math.max(3f, (float) (w * 0.004));
            g.setColor(new Color(0xF7, 0xC5, 0x4A));
            g.fill(new RoundRectangle2D.Float((w - ruleW) / 2f, (float) (textTop - w * 0.022), ruleW, ruleH, ruleH, ruleH));
            double y = textTop;
            for (String line : lines) {
                GlyphVector gv = font.createGlyphVector(frc, line);
                double lw = gv.getLogicalBounds().getWidth();
                float x = (float) (textL + (textW - lw) / 2);
                float base0 = (float) (y + (lineH + size * 0.72) / 2);
                g.setColor(new Color(0, 0, 0, 130));
                g.fill(gv.getOutline(x, base0 + size * 0.05f));
                g.setColor(Color.WHITE);
                g.fill(gv.getOutline(x, base0));
                y += lineH;
            }
        }
        g.dispose();
        return out;
    }

    /**
     * How many (scaled) pixels to trim from the top so the most detailed rows (people, faces, action: lots of edges;
     * sky, ceilings and floors have few) land in the part of the picture that TikTok never covers.
     * @param maxOffset scaled pixels that must be trimmed in total; @param cleanH height of the uncovered part
     */
    static int bestCrop(BufferedImage src, double scale, int maxOffset, int cleanH) {
        if (maxOffset <= 0) return 0;
        int sh = Math.max(16, src.getHeight() / 8), sw = Math.max(8, src.getWidth() / 8);
        BufferedImage small = resize(src, sw, sh);
        double[] row = new double[sh];
        for (int y = 1; y < sh - 1; y++) {
            double e = 0;
            for (int x = 1; x < sw - 1; x++) {
                int c = luma(small.getRGB(x, y));
                e += Math.abs(c - luma(small.getRGB(x + 1, y))) + Math.abs(c - luma(small.getRGB(x, y + 1)));
            }
            row[y] = e;
        }
        double toSmall = (double) sh / (src.getHeight() * scale);
        int best = (int) Math.round(maxOffset * 0.4);
        double bestScore = -1;
        int steps = 40;
        for (int i = 0; i <= steps; i++) {
            int off = (int) Math.round(maxOffset * (double) i / steps);
            int a = (int) (off * toSmall), b = (int) Math.min(sh, (off + cleanH) * toSmall);
            double e = 0;
            for (int y = a; y < b; y++) e += row[y];
            // mild preference for the middle, so flat pictures are not pushed to an extreme
            double score = e * (1 - 0.15 * Math.abs((double) i / steps - 0.5) * 2);
            if (score > bestScore) { bestScore = score; best = off; }
        }
        return best;
    }

    private static int luma(int rgb) {
        return (((rgb >> 16) & 255) * 3 + ((rgb >> 8) & 255) * 6 + (rgb & 255)) / 10;
    }

    /** The picture scaled to cover w x h, blurred by shrinking and enlarging it twice. */
    private static BufferedImage blurCover(BufferedImage src, int w, int h) {
        double s = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
        int cw = (int) Math.ceil(src.getWidth() * s), ch = (int) Math.ceil(src.getHeight() * s);
        BufferedImage cur = resize(src, Math.max(8, cw / 24), Math.max(8, ch / 24));
        cur = resize(cur, Math.max(4, cw / 48), Math.max(4, ch / 48));
        cur = resize(cur, Math.max(8, cw / 12), Math.max(8, ch / 12));
        BufferedImage big = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cur, (w - cw) / 2, (h - ch) / 2, cw, ch, null);
        g.dispose();
        return big;
    }

    private static BufferedImage resize(BufferedImage im, int w, int h) {
        BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = r.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(im, 0, 0, w, h, null);
        g.dispose();
        return r;
    }
}
