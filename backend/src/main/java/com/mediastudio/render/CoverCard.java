package com.mediastudio.render;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The cover slide: the picture full-bleed, a dark fade at the top, and over it a small spaced-out label
 * ("TRUYỆN CỔ TÍCH"), the title in big bold gold (or ice-blue for facts) letters, a small ornament, the teaser line
 * and a "swipe" hint. Everything sits in the upper half: TikTok's caption covers the bottom of the screen.
 */
final class CoverCard {
    private CoverCard() {}

    private static final String[] TITLE_FONTS = {"Segoe UI Black", "Arial Black", "Segoe UI", "Arial", "SansSerif"};

    static BufferedImage render(BufferedImage src, String title, String teaser, String kicker, boolean facts, int w, int h) {
        System.setProperty("java.awt.headless", "true");
        title = nfc(title).toUpperCase(new Locale("vi"));
        teaser = nfc(teaser);
        kicker = nfc(kicker);
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

        g.drawImage(src, 0, 0, w, h, null);
        // Dark fade from the top (text area) and a lighter one at the bottom; a soft vignette on the sides.
        double fadeEnd = portrait ? 0.62 : 0.75;
        g.setPaint(new LinearGradientPaint(0, 0, 0, (float) (h * fadeEnd), new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(0, 0, 0, 215), new Color(0, 0, 0, 150), new Color(0, 0, 0, 0)}));
        g.fillRect(0, 0, w, (int) Math.ceil(h * fadeEnd));
        g.setPaint(new LinearGradientPaint(0, (float) (h * 0.78), 0, h, new float[]{0f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 120)}));
        g.fillRect(0, (int) (h * 0.78), w, h - (int) (h * 0.78));
        g.setPaint(new RadialGradientPaint(w / 2f, h * 0.45f, Math.max(w, h) * 0.75f, new float[]{0.55f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 110)}));
        g.fillRect(0, 0, w, h);

        Color accentTop = facts ? new Color(0xE8, 0xFB, 0xFF) : new Color(0xFF, 0xF1, 0xB8);
        Color accentBottom = facts ? new Color(0x5C, 0xD2, 0xFF) : new Color(0xF2, 0xA9, 0x2E);
        Color accent = facts ? new Color(0x7F, 0xDB, 0xFF) : new Color(0xF7, 0xC5, 0x4A);

        double maxW = w * (portrait ? 0.86 : 0.80);
        double y = h * (portrait ? 0.115 : 0.08);

        // 1. kicker: small, letter-spaced, accent colour, between two short lines
        Font kFont = font(new String[]{"Segoe UI Semibold", "Segoe UI", "Arial"}, Font.PLAIN, shortSide * 0.030f, kicker)
                .deriveFont(Map.of(TextAttribute.TRACKING, 0.28f));
        GlyphVector kgv = kFont.createGlyphVector(frc, kicker);
        double kw = kgv.getLogicalBounds().getWidth();
        float kSize = kFont.getSize2D();
        g.setColor(accent);
        g.fill(kgv.getOutline((float) ((w - kw) / 2), (float) (y + kSize)));
        float lineLen = shortSide * 0.07f, lineGap = shortSide * 0.025f;
        float ly = (float) (y + kSize * 0.62);
        g.setStroke(new BasicStroke(Math.max(2f, shortSide * 0.0028f)));
        g.drawLine((int) ((w - kw) / 2 - lineGap - lineLen), (int) ly, (int) ((w - kw) / 2 - lineGap), (int) ly);
        g.drawLine((int) ((w + kw) / 2 + lineGap), (int) ly, (int) ((w + kw) / 2 + lineGap + lineLen), (int) ly);
        y += kSize * 1.9;

        // 2. title: as big as fits in at most 3 lines
        float tSize = shortSide * 0.125f;
        Font tFont;
        List<String> tLines;
        while (true) {
            tFont = font(TITLE_FONTS, Font.BOLD, tSize, title);
            tLines = balancedWrap(title, tFont, frc, maxW);
            boolean fits = tLines.size() <= 3;
            for (String l : tLines) if (width(tFont, l) > maxW) fits = false;
            if (fits || tSize <= shortSide * 0.06f) break;
            tSize -= shortSide * 0.004f;
        }
        double tLineH = tSize * 1.32; // room for Vietnamese stacked marks (Ự above Ầ)
        float outline = tSize / 11f;
        for (String line : tLines) {
            GlyphVector gv = tFont.createGlyphVector(frc, line);
            double lw = gv.getLogicalBounds().getWidth();
            float x = (float) ((w - lw) / 2), base = (float) (y + tSize * 0.98);
            Shape s = gv.getOutline(x, base);
            // glow / drop shadow
            for (int k = 3; k >= 1; k--) {
                g.setStroke(new BasicStroke(outline * (1.4f + k * 1.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0, 0, 0, 40 + 25 * (3 - k)));
                g.draw(gv.getOutline(x, base + tSize * 0.03f));
            }
            g.setStroke(new BasicStroke(outline * 1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(20, 12, 4, 235));
            g.draw(s);
            Rectangle2D b = s.getBounds2D();
            g.setPaint(new LinearGradientPaint((float) b.getX(), (float) b.getY(), (float) b.getX(), (float) b.getMaxY(),
                    new float[]{0f, 1f}, new Color[]{accentTop, accentBottom}));
            g.fill(s);
            y += tLineH;
        }

        // 3. ornament: line — diamond — line
        y += tSize * 0.22;
        float d = shortSide * 0.014f, half = shortSide * 0.16f;
        float cy = (float) y, cx = w / 2f;
        g.setColor(withAlpha(accent, 230));
        g.setStroke(new BasicStroke(Math.max(2f, shortSide * 0.0026f)));
        g.drawLine((int) (cx - half), (int) cy, (int) (cx - d * 2), (int) cy);
        g.drawLine((int) (cx + d * 2), (int) cy, (int) (cx + half), (int) cy);
        Path2D dia = new Path2D.Float();
        dia.moveTo(cx, cy - d);
        dia.lineTo(cx + d, cy);
        dia.lineTo(cx, cy + d);
        dia.lineTo(cx - d, cy);
        dia.closePath();
        g.fill(dia);
        y += d + shortSide * 0.035;

        // 4. teaser: white italic, up to 3 lines
        if (!teaser.isEmpty()) {
            float teSize = shortSide * 0.046f;
            Font teFont = font(new String[]{"Segoe UI Semibold", "Segoe UI", "Arial"}, Font.ITALIC, teSize, teaser);
            List<String> teLines = balancedWrap(teaser, teFont, frc, w * (portrait ? 0.80 : 0.70));
            for (String line : teLines) {
                GlyphVector gv = teFont.createGlyphVector(frc, line);
                double lw = gv.getLogicalBounds().getWidth();
                Shape s = gv.getOutline((float) ((w - lw) / 2), (float) (y + teSize));
                g.setStroke(new BasicStroke(teSize / 5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0, 0, 0, 150));
                g.draw(s);
                g.setColor(new Color(255, 255, 255, 245));
                g.fill(s);
                y += teSize * 1.4;
            }
        }

        // 5. swipe hint in a pill
        y += shortSide * 0.04;
        String hint = "Vuốt để xem  ›";
        Font hFont = font(new String[]{"Segoe UI Semibold", "Segoe UI", "Arial"}, Font.PLAIN, shortSide * 0.030f, hint);
        GlyphVector hgv = hFont.createGlyphVector(frc, hint);
        double hw = hgv.getLogicalBounds().getWidth();
        float hs = hFont.getSize2D(), padX = hs * 1.0f, padY = hs * 0.5f;
        float px = (float) ((w - hw) / 2 - padX), pw = (float) (hw + 2 * padX), ph = hs * 1.25f + 2 * padY;
        g.setColor(new Color(0, 0, 0, 110));
        g.fillRoundRect((int) px, (int) y, (int) pw, (int) ph, (int) ph, (int) ph);
        g.setColor(withAlpha(accent, 200));
        g.setStroke(new BasicStroke(Math.max(1.5f, shortSide * 0.0018f)));
        g.drawRoundRect((int) px, (int) y, (int) pw, (int) ph, (int) ph, (int) ph);
        g.setColor(Color.WHITE);
        g.fill(hgv.getOutline((float) ((w - hw) / 2), (float) (y + padY + hs * 1.0f)));

        g.dispose();
        return out;
    }

    private static String nfc(String s) {
        return s == null ? "" : Normalizer.normalize(s.trim(), Normalizer.Form.NFC);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static Font font(String[] names, int style, float size, String text) {
        for (String n : names) {
            Font f = new Font(n, style, 40);
            if ((f.getFamily().equalsIgnoreCase(n) || "SansSerif".equals(n)) && f.canDisplayUpTo(text) == -1) {
                return f.deriveFont(style, size);
            }
        }
        return new Font(Font.SANS_SERIF, style, Math.round(size)).deriveFont(size);
    }

    private static double width(Font f, String s) {
        return f.createGlyphVector(new FontRenderContext(null, true, true), s).getLogicalBounds().getWidth();
    }

    /** Word wrap that evens out line lengths (no short last line), for titles and teasers. */
    private static List<String> balancedWrap(String text, Font font, FontRenderContext frc, double maxW) {
        String[] words = text.split("\\s+");
        List<String> greedy = ScriptOverlay.wrap(text, font, frc, maxW);
        int n = greedy.size();
        if (n <= 1) return greedy;
        // shrink the width until the line count would grow: gives the most even lines
        double lo = 0, hi = maxW;
        List<String> best = greedy;
        for (int it = 0; it < 14; it++) {
            double mid = (lo + hi) / 2;
            List<String> tryL = ScriptOverlay.wrap(text, font, frc, mid);
            if (tryL.size() <= n) {
                best = tryL;
                hi = mid;
            } else {
                lo = mid;
            }
        }
        // a single word wider than maxW still forms its own line
        List<String> out = new ArrayList<>(best);
        return out.isEmpty() ? List.of(String.join(" ", words)) : out;
    }
}
