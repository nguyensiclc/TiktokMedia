package com.mediastudio.render;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.util.List;

/**
 * One swipe-through photo with its text: the text has its own band at the top and the picture sits whole below it,
 * so the text never covers the picture. The background is the same picture, blurred and darkened.
 * The text is at the top because TikTok draws the caption / account name over the bottom of the screen.
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
