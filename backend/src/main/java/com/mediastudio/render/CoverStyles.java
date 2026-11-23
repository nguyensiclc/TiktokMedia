package com.mediastudio.render;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cover layouts, so posts in a profile grid do not all look the same. "classic" is {@link CoverCard}; the others:
 * <ul>
 *   <li>year: the year from the title (1786, 248…) huge in gold, the title under it;</li>
 *   <li>banner: left-aligned, each title line on its own yellow strip, like a news label;</li>
 *   <li>cinema: a dark film-poster band at the top, serif title, thin gold rules;</li>
 *   <li>impact: left-aligned heavy title, last line in gold, an accent bar beside it.</li>
 * </ul>
 * Like the classic one, text stays in the upper part (inside {@link PhoneSafeArea} on 9:16), where TikTok's
 * caption never covers it.
 */
final class CoverStyles {
    private CoverStyles() {}

    /** Ids in the order shown in the app; "" (or anything else) = auto. */
    static final List<String> IDS = List.of("classic", "year", "banner", "cinema", "impact");

    private static final Pattern YEAR = Pattern.compile("(?<![\\d.,])(\\d{3,4})(?![\\d.,]?\\d)");
    private static final String[] SEMI = {"Segoe UI Semibold", "Segoe UI", "Arial"};
    private static final String[] SERIF = {"Times New Roman", "Georgia", "Cambria", "Serif"};

    /** The style to use: the chosen one, or for auto one picked from the seed (the project id) so projects differ. */
    static String pick(String style, String seed, String title, String teaser) {
        String s = style == null ? "" : style.trim().toLowerCase(Locale.ROOT);
        if (!IDS.contains(s)) s = IDS.get(Math.floorMod((seed == null ? "" : seed).hashCode() * 31 + 7, IDS.size()));
        if ("year".equals(s) && year(title, teaser) == null) s = "impact";
        return s;
    }

    static String year(String title, String teaser) {
        for (String t : new String[]{title, teaser}) {
            if (t == null) continue;
            Matcher m = YEAR.matcher(t);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    static BufferedImage render(String style, BufferedImage src, String title, String teaser, String kicker,
                                boolean facts, int w, int h) {
        if ("classic".equals(style)) return CoverCard.render(src, title, teaser, kicker, facts, w, h);
        Painter p = new Painter(src, title, teaser, kicker, facts, w, h);
        switch (style) {
            case "year" -> p.year();
            case "banner" -> p.banner();
            case "cinema" -> p.cinema();
            default -> p.impact();
        }
        return p.done();
    }

    /** One block of the layout: its height and how to draw it from a top y. */
    private record Item(double height, DoubleConsumer draw) {}

    private record Fit(Font font, List<String> lines) {
        float size() {
            return font.getSize2D();
        }
    }

    private static final class Painter {
        final BufferedImage out;
        final Graphics2D g;
        final FontRenderContext frc;
        final int w, h;
        final float ss;
        final boolean tall;
        final double bandTop, bandBottom;
        final String title, teaser, kicker;
        final boolean facts;
        final Color accent, accentTop, accentBottom;

        Painter(BufferedImage src, String title, String teaser, String kicker, boolean facts, int w, int h) {
            System.setProperty("java.awt.headless", "true");
            this.w = w;
            this.h = h;
            this.ss = Math.min(w, h);
            this.tall = h >= w * 1.7;
            this.bandTop = tall ? h * PhoneSafeArea.TOP : h * 0.065;
            this.bandBottom = tall ? h * PhoneSafeArea.BOTTOM : h * 0.80;
            this.title = CoverCard.nfc(title).toUpperCase(new Locale("vi"));
            this.teaser = CoverCard.nfc(teaser);
            this.kicker = CoverCard.nfc(kicker);
            this.facts = facts;
            accent = facts ? new Color(0x7F, 0xDB, 0xFF) : new Color(0xF7, 0xC5, 0x4A);
            accentTop = facts ? new Color(0xE8, 0xFB, 0xFF) : new Color(0xFF, 0xF1, 0xB8);
            accentBottom = facts ? new Color(0x5C, 0xD2, 0xFF) : new Color(0xF2, 0xA9, 0x2E);
            out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            frc = g.getFontRenderContext();
            g.drawImage(src, 0, 0, w, h, null);
        }

        BufferedImage done() {
            g.dispose();
            return out;
        }

        // ------------------------------------------------------------------ styles

        void year() {
            String yr = CoverStyles.year(title, teaser);
            // the year is already huge above: drop it (and a "năm" before it) from the title line
            String cut = title.replaceFirst("(?iu)\\s*(NĂM\\s+)?" + yr + "\\b", "").replaceAll("\\s{2,}", " ")
                    .replaceAll("^[\\s,:\\-–]+|[\\s,:\\-–]+$", "");
            String rest = cut.isBlank() ? title : cut;
            Layout l = fitLayout(k -> {
                List<Item> it = new ArrayList<>();
                it.add(pill(kicker, k, new Color(0xC8, 0x1E, 0x1E), Color.WHITE, true));
                it.add(gap(ss * 0.012));
                Fit yf = fit(yr, CoverCard.TITLE_FONTS, Font.BOLD, ss * 0.36f * k, w * 0.80, 1, null);
                float ys = yf.size();
                it.add(new Item(ys * 0.86, y -> text(yr, yf.font(), centerX(yf.font(), yr), y + ys * 0.80,
                        new Color[]{accentTop, accentBottom}, new Color(25, 14, 4, 235), ys / 16f, 3)));
                it.add(gap(ss * 0.02));
                it.addAll(lines(fit(rest, CoverCard.TITLE_FONTS, Font.BOLD, ss * 0.078f * k, w * 0.86, 3, null),
                        1.30, true, 0, new Color[]{Color.WHITE, new Color(0xF2, 0xF2, 0xF2)}, new Color(10, 10, 10, 230), 9f));
                it.add(gap(ss * 0.018));
                it.add(rule(ss * 0.10, accent, k));
                it.add(gap(ss * 0.026));
                if (!teaser.isEmpty()) {
                    it.addAll(lines(fit(teaser, SEMI, Font.ITALIC, ss * 0.044f * k, w * 0.80, 3, null), 1.38, true, 0,
                            new Color[]{accentTop, accentTop}, new Color(0, 0, 0, 160), 5f));
                }
                it.add(gap(ss * 0.03));
                it.add(hint("Vuốt để xem  ›", k, true, 0, new Color(255, 255, 255, 225)));
                return it;
            });
            topFade(l.top + l.height, 190);
            vignette();
            l.draw();
        }

        void banner() {
            double x0 = w * 0.07;
            Color box = facts ? new Color(0x7F, 0xDB, 0xFF) : new Color(0xFF, 0xD6, 0x0A);
            Layout l = fitLayout(k -> {
                List<Item> it = new ArrayList<>();
                it.add(pill(kicker, k, new Color(0xE5, 0x38, 0x3B), Color.WHITE, false, x0, false));
                it.add(gap(ss * 0.022));
                Fit tf = fit(title, CoverCard.TITLE_FONTS, Font.BOLD, ss * 0.092f * k, w * 0.72, 4, null);
                float s = tf.size(), pad = s * 0.30f;
                for (String line : tf.lines()) {
                    it.add(new Item(s * 1.42, y -> {
                        double tw = CoverCard.width(tf.font(), line);
                        g.setColor(new Color(0, 0, 0, 90));
                        g.fill(new Rectangle2D.Double(x0 + s * 0.07, y + s * 0.07, tw + 2 * pad, s * 1.32));
                        g.setColor(box);
                        g.fill(new Rectangle2D.Double(x0, y, tw + 2 * pad, s * 1.32));
                        text(line, tf.font(), x0 + pad, y + s * 1.06, new Color[]{new Color(0x14, 0x14, 0x14), new Color(0x14, 0x14, 0x14)}, null, 0, 0);
                    }));
                }
                it.add(gap(ss * 0.024));
                if (!teaser.isEmpty()) {
                    Fit te = fit(teaser, SEMI, Font.PLAIN, ss * 0.040f * k, w * 0.76, 3, null);
                    float ts = te.size(), tp = ts * 0.32f;
                    for (String line : te.lines()) {
                        it.add(new Item(ts * 1.50, y -> {
                            double tw = CoverCard.width(te.font(), line);
                            g.setColor(new Color(0, 0, 0, 185));
                            g.fill(new Rectangle2D.Double(x0, y, tw + 2 * tp, ts * 1.42));
                            text(line, te.font(), x0 + tp, y + ts * 1.06, new Color[]{Color.WHITE, Color.WHITE}, null, 0, 0);
                        }));
                    }
                }
                it.add(gap(ss * 0.03));
                it.add(hint("VUỐT ĐỂ XEM  ›", k, false, x0, box));
                return it;
            });
            g.setColor(new Color(0, 0, 0, 45));
            g.fillRect(0, 0, w, h);
            topFade(l.top + l.height, 130);
            l.draw();
        }

        void cinema() {
            Layout l = fitLayout(k -> {
                List<Item> it = new ArrayList<>();
                it.add(kickerLines(k));
                it.add(gap(ss * 0.022));
                Map<TextAttribute, Object> track = Map.of(TextAttribute.TRACKING, 0.04f);
                it.addAll(lines(fit(title, SERIF, Font.BOLD, ss * 0.085f * k, w * 0.86, 3, track), 1.26, true, 0,
                        new Color[]{Color.WHITE, accentTop}, null, 0f));
                it.add(gap(ss * 0.02));
                it.add(rule(w * 0.30, accent, k));
                it.add(gap(ss * 0.028));
                if (!teaser.isEmpty()) {
                    it.addAll(lines(fit(teaser, SERIF, Font.ITALIC, ss * 0.045f * k, w * 0.80, 3, null), 1.36, true, 0,
                            new Color[]{new Color(0xE8, 0xE8, 0xE8), new Color(0xE8, 0xE8, 0xE8)}, null, 0f));
                }
                if (tall) {
                    it.add(gap(ss * 0.03));
                    it.add(hint("VUỐT ĐỂ XEM  ›", k, true, 0, accent));
                }
                return it;
            });
            // solid dark band behind the text, fading out below it
            double end = l.top + l.height + ss * 0.05;
            float fade = (float) (end + h * 0.14);
            g.setPaint(new LinearGradientPaint(0, 0, 0, fade, new float[]{0f, (float) (end / fade), 1f},
                    new Color[]{new Color(0, 0, 0, 235), new Color(0, 0, 0, 215), new Color(0, 0, 0, 0)}));
            g.fillRect(0, 0, w, (int) Math.ceil(fade));
            if (!tall) {
                // thin letterbox bar at the bottom with the swipe hint
                int bar = (int) (h * 0.075);
                g.setPaint(new LinearGradientPaint(0, h - bar - h * 0.06f, 0, h - bar, new float[]{0f, 1f},
                        new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 200)}));
                g.fillRect(0, (int) (h - bar - h * 0.06f), w, (int) (h * 0.06f) + 1);
                g.setColor(new Color(0, 0, 0, 230));
                g.fillRect(0, h - bar, w, bar);
                Item hi = hint("VUỐT ĐỂ XEM  ›", 1f, true, 0, accent);
                hi.draw().accept(h - bar + (bar - hi.height()) / 2);
            }
            l.draw();
        }

        void impact() {
            double x0 = w * 0.075, bar = ss * 0.014, tx = x0 + bar + ss * 0.035;
            Color red = facts ? accent : new Color(0xFF, 0x3B, 0x30);
            Layout l = fitLayout(k -> {
                List<Item> it = new ArrayList<>();
                Font kf = CoverCard.font(SEMI, Font.PLAIN, ss * 0.030f * k, kicker).deriveFont(Map.of(TextAttribute.TRACKING, 0.26f));
                it.add(new Item(kf.getSize2D() * 1.9, y -> text(kicker, kf, tx, y + kf.getSize2D(), new Color[]{accent, accent}, null, 0, 0)));
                Fit tf = fit(title, CoverCard.TITLE_FONTS, Font.BOLD, ss * 0.112f * k, w * 0.93 - tx, 4, null);
                float s = tf.size();
                double lh = s * 1.24;
                it.add(new Item(lh * tf.lines().size(), y -> {
                    g.setColor(red);
                    g.fill(new Rectangle2D.Double(x0, y + s * 0.18, bar, lh * tf.lines().size() - s * 0.20));
                    double yy = y;
                    for (int i = 0; i < tf.lines().size(); i++) {
                        boolean last = i == tf.lines().size() - 1 && tf.lines().size() > 1;
                        text(tf.lines().get(i), tf.font(), tx, yy + s * 0.98,
                                last ? new Color[]{accentTop, accentBottom} : new Color[]{Color.WHITE, Color.WHITE},
                                new Color(12, 8, 4, 235), s / 10f, 3);
                        yy += lh;
                    }
                }));
                it.add(gap(ss * 0.026));
                if (!teaser.isEmpty()) {
                    it.addAll(lines(fit(teaser, SEMI, Font.ITALIC, ss * 0.043f * k, w * 0.86 - tx, 3, null), 1.40, false, tx,
                            new Color[]{Color.WHITE, Color.WHITE}, new Color(0, 0, 0, 160), 5f));
                }
                it.add(gap(ss * 0.03));
                it.add(hint("VUỐT ĐỂ XEM  ›", k, false, tx, accent));
                return it;
            });
            g.setPaint(new LinearGradientPaint(0, 0, (float) (w * 0.95), 0, new float[]{0f, 0.55f, 1f},
                    new Color[]{new Color(0, 0, 0, 200), new Color(0, 0, 0, 110), new Color(0, 0, 0, 0)}));
            g.fillRect(0, 0, w, h);
            topFade(l.top + l.height, 120);
            l.draw();
        }

        // ------------------------------------------------------------------ layout

        private final class Layout {
            final List<Item> items;
            final double height, top;

            Layout(List<Item> items) {
                this.items = items;
                double t = 0;
                for (Item i : items) t += i.height();
                height = t;
                double band = bandBottom - bandTop;
                top = tall ? bandTop + Math.max(0, (band - t) / 2) : bandTop;
            }

            void draw() {
                double y = top;
                for (Item i : items) {
                    i.draw().accept(y);
                    y += i.height();
                }
            }
        }

        /** Builds the items at scale 1, then smaller until they fit the text band. */
        private Layout fitLayout(java.util.function.Function<Float, List<Item>> build) {
            float k = 1f;
            Layout l = new Layout(build.apply(k));
            while (l.height > bandBottom - bandTop && k > 0.55f) {
                k *= 0.93f;
                l = new Layout(build.apply(k));
            }
            return l;
        }

        private Fit fit(String text, String[] fonts, int style, float start, double maxW, int maxLines,
                        Map<TextAttribute, Object> attrs) {
            float s = start;
            while (true) {
                Font f = CoverCard.font(fonts, style, s, text);
                if (attrs != null) f = f.deriveFont(attrs);
                List<String> ls = CoverCard.balancedWrap(text, f, frc, maxW);
                boolean ok = ls.size() <= maxLines;
                for (String l : ls) if (CoverCard.width(f, l) > maxW) ok = false;
                if (ok || s <= start * 0.45f) return new Fit(f, ls);
                s -= start * 0.04f;
            }
        }

        private List<Item> lines(Fit f, double lineH, boolean center, double x, Color[] fill, Color stroke, float strokeDiv) {
            List<Item> it = new ArrayList<>();
            float s = f.size();
            for (String line : f.lines()) {
                it.add(new Item(s * lineH, y -> text(line, f.font(), center ? centerX(f.font(), line) : x, y + s * 0.98,
                        fill, stroke, strokeDiv > 0 ? s / strokeDiv : 0, stroke != null && strokeDiv > 6 ? 2 : 0)));
            }
            return it;
        }

        private Item gap(double px) {
            return new Item(px, y -> {});
        }

        private Item rule(double len, Color c, float k) {
            float t = Math.max(2f, ss * 0.0028f);
            return new Item(t, y -> {
                g.setColor(CoverCard.withAlpha(c, 230));
                g.fill(new Rectangle2D.Double((w - len) / 2, y, len, t));
            });
        }

        private Item pill(String s, float k, Color bg, Color fg, boolean center) {
            return pill(s, k, bg, fg, center, 0, true);
        }

        /** Small spaced-out label on a filled box (rounded or square). */
        private Item pill(String s, float k, Color bg, Color fg, boolean center, double x, boolean round) {
            Font f = CoverCard.font(SEMI, Font.BOLD, ss * 0.030f * k, s).deriveFont(Map.of(TextAttribute.TRACKING, 0.22f));
            float fs = f.getSize2D(), padX = fs * 0.9f, padY = fs * 0.45f;
            double tw = CoverCard.width(f, s);
            double bw = tw + 2 * padX, bh = fs * 1.2 + 2 * padY;
            return new Item(bh, y -> {
                double bx = center ? (w - bw) / 2 : x;
                g.setColor(bg);
                if (round) g.fillRoundRect((int) bx, (int) y, (int) bw, (int) bh, (int) bh, (int) bh);
                else g.fill(new Rectangle2D.Double(bx, y, bw, bh));
                text(s, f, bx + padX, y + padY + fs * 0.98, new Color[]{fg, fg}, null, 0, 0);
            });
        }

        /** Kicker between two short lines, as on the classic cover. */
        private Item kickerLines(float k) {
            Font f = CoverCard.font(SEMI, Font.PLAIN, ss * 0.028f * k, kicker).deriveFont(Map.of(TextAttribute.TRACKING, 0.32f));
            float fs = f.getSize2D();
            return new Item(fs * 1.4, y -> {
                double kw = CoverCard.width(f, kicker);
                text(kicker, f, (w - kw) / 2, y + fs, new Color[]{accent, accent}, null, 0, 0);
                float len = ss * 0.08f, gp = ss * 0.025f, ly = (float) (y + fs * 0.62);
                g.setColor(accent);
                g.setStroke(new BasicStroke(Math.max(2f, ss * 0.0026f)));
                g.drawLine((int) ((w - kw) / 2 - gp - len), (int) ly, (int) ((w - kw) / 2 - gp), (int) ly);
                g.drawLine((int) ((w + kw) / 2 + gp), (int) ly, (int) ((w + kw) / 2 + gp + len), (int) ly);
            });
        }

        private Item hint(String s, float k, boolean center, double x, Color c) {
            Font f = CoverCard.font(SEMI, Font.BOLD, ss * 0.027f * k, s).deriveFont(Map.of(TextAttribute.TRACKING, 0.16f));
            float fs = f.getSize2D();
            return new Item(fs * 1.4, y -> text(s, f, center ? centerX(f, s) : x, y + fs, new Color[]{c, c},
                    new Color(0, 0, 0, 140), fs / 5f, 0));
        }

        private double centerX(Font f, String s) {
            return (w - CoverCard.width(f, s)) / 2;
        }

        /** Text with an optional dark outline, a soft drop shadow (0 = none) and a top-to-bottom two-colour fill. */
        private void text(String line, Font f, double x, double baseline, Color[] fill, Color stroke, float sw, int shadow) {
            GlyphVector gv = f.createGlyphVector(frc, line);
            Shape s = gv.getOutline((float) x, (float) baseline);
            float size = f.getSize2D();
            for (int k = shadow; k >= 1; k--) {
                g.setStroke(new BasicStroke(Math.max(1f, sw) * (1.4f + k * 1.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0, 0, 0, 40 + 25 * (3 - Math.min(3, k))));
                g.draw(gv.getOutline((float) x, (float) baseline + size * 0.03f));
            }
            if (stroke != null && sw > 0) {
                g.setStroke(new BasicStroke(sw * 1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(stroke);
                g.draw(s);
            }
            Rectangle2D b = s.getBounds2D();
            if (fill[0].equals(fill[1]) || b.getHeight() < 1) g.setColor(fill[0]);
            else g.setPaint(new LinearGradientPaint((float) b.getX(), (float) b.getY(), (float) b.getX(), (float) b.getMaxY(),
                    new float[]{0f, 1f}, fill));
            g.fill(s);
        }

        /** Dark fade from the top down to a bit below the text block. */
        private void topFade(double textBottom, int alpha) {
            float end = (float) Math.min(h, textBottom + h * 0.16);
            g.setPaint(new LinearGradientPaint(0, 0, 0, end, new float[]{0f, (float) Math.min(0.85, textBottom / end), 1f},
                    new Color[]{new Color(0, 0, 0, alpha), new Color(0, 0, 0, (int) (alpha * 0.8)), new Color(0, 0, 0, 0)}));
            g.fillRect(0, 0, w, (int) Math.ceil(end));
        }

        private void vignette() {
            g.setPaint(new RadialGradientPaint(w / 2f, h * 0.45f, Math.max(w, h) * 0.75f, new float[]{0.55f, 1f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 110)}));
            g.fillRect(0, 0, w, h);
        }
    }
}
