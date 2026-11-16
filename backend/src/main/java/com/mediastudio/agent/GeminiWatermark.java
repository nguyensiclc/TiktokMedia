package com.mediastudio.agent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * Removes the small "✦" sparkle that Google Flow / Gemini stamps in the bottom-right corner of generated pictures.
 * The mark is white blended with a fixed alpha mask at a fixed place (centre 97 px from the right and bottom edges,
 * ~48 px wide), so the original pixels are recovered exactly by reversing the blend:
 * original = (seen - alpha * 255) / (1 - alpha). Pictures without the mark are left untouched.
 */
public final class GeminiWatermark {
    private GeminiWatermark() {}

    static final int SIZE = 72;   // mask box, centred on the sparkle
    static final int OFFSET = 97; // centre distance from the right / bottom edge
    private static final String RESOURCE = "/gemini-sparkle-alpha.png";
    private static volatile float[][] mask;

    /** For pictures downloaded from Google Flow: the cleaned image bytes in the same format, or the input. */
    public static byte[] clean(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null || !remove(img, true)) return data;
            String fmt = data.length > 3 && (data[0] & 0xff) == 0x89 && data[1] == 'P' ? "png" : "jpg";
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            if ("jpg".equals(fmt)) {
                var writer = ImageIO.getImageWritersByFormatName("jpg").next();
                var param = writer.getDefaultWriteParam();
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.95f);
                try (var ios = ImageIO.createImageOutputStream(out)) {
                    writer.setOutput(ios);
                    writer.write(null, new javax.imageio.IIOImage(rgb(img), null, null), param);
                } finally {
                    writer.dispose();
                }
            } else {
                ImageIO.write(img, "png", out);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return data; // never lose a picture over this
        }
    }

    /** Reverses the blend in place. @return false when the picture does not carry the mark */
    static boolean remove(BufferedImage img, boolean fromFlow) throws IOException {
        float[][] a = mask();
        int bx = img.getWidth() - OFFSET - SIZE / 2, by = img.getHeight() - OFFSET - SIZE / 2;
        // the mark can sit a pixel or two off: use the placement where reversing it works best
        double best = Double.MAX_VALUE;
        int x0 = bx, y0 = by;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                int x = bx + dx, y = by + dy;
                if (x < 0 || y < 0 || x + SIZE > img.getWidth() || y + SIZE > img.getHeight()) continue;
                double r = edgeRatio(img, a, x, y);
                if (r < best) { best = r; x0 = x; y0 = y; }
            }
        }
        // Flow pictures always carry the mark, so a lenient limit is used (on light, busy corners the outline
        // test is weak: 0.84-1.17 for real marks). Pictures from elsewhere measured 1.16-5.8 with no mark.
        if (!(best < (fromFlow ? 1.3 : 0.8))) return false;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float al = a[y][x];
                if (al <= 0) continue;
                int p = img.getRGB(x0 + x, y0 + y);
                float k = Math.max(1 - al, 0.05f);
                int r = fix((p >> 16) & 255, al, k), g = fix((p >> 8) & 255, al, k), b = fix(p & 255, al, k);
                img.setRGB(x0 + x, y0 + y, (p & 0xff000000) | (r << 16) | (g << 8) | b);
            }
        }
        return true;
    }

    private static int fix(int seen, float al, float k) {
        return Math.max(0, Math.min(255, Math.round((seen - al * 255f) / k)));
    }

    /**
     * The mark is there when reversing it makes the sparkle's outline disappear: brightness steps measured across
     * the mask's edges must drop clearly (ratio after / before well under 1). Without a mark, reversing would draw a
     * dark sparkle, so the steps grow (ratio over 1).
     */
    private static double edgeRatio(BufferedImage img, float[][] a, int x0, int y0) {
        double[][] seen = new double[SIZE][SIZE], fixed = new double[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int p = img.getRGB(x0 + x, y0 + y);
                double l = 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
                double al = a[y][x];
                seen[y][x] = l;
                fixed[y][x] = Math.max(0, Math.min(255, (l - al * 255) / Math.max(1 - al, 0.05)));
            }
        }
        double before = 0, after = 0;
        for (int y = 0; y < SIZE - 1; y++) {
            for (int x = 0; x < SIZE - 1; x++) {
                double ga = Math.abs(a[y][x + 1] - a[y][x]) + Math.abs(a[y + 1][x] - a[y][x]);
                if (ga < 0.02) continue;
                before += ga * (Math.abs(seen[y][x + 1] - seen[y][x]) + Math.abs(seen[y + 1][x] - seen[y][x]));
                after += ga * (Math.abs(fixed[y][x + 1] - fixed[y][x]) + Math.abs(fixed[y + 1][x] - fixed[y][x]));
            }
        }
        return before > 1e-6 ? after / before : Double.MAX_VALUE;
    }

    private static float[][] mask() throws IOException {
        float[][] m = mask;
        if (m != null) return m;
        try (InputStream in = GeminiWatermark.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("thiếu " + RESOURCE);
            BufferedImage im = ImageIO.read(in);
            m = new float[SIZE][SIZE];
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) m[y][x] = im.getRaster().getSample(x, y, 0) / 255f;
        }
        return mask = m;
    }

    private static BufferedImage rgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
        BufferedImage r = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = r.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return r;
    }

    /**
     * Builds the alpha mask from a picture whose corner behind the sparkle is plain and dark (run once):
     * java GeminiWatermark.java build &lt;dark.jpg&gt; &lt;mask.png&gt;  |  java GeminiWatermark.java test &lt;mask.png&gt; &lt;in&gt; &lt;out.png&gt;
     */
    public static void main(String[] args) throws Exception {
        if ("build".equals(args[0])) {
            // several dark samples: per-pixel median of their alpha estimates drops stars and JPEG noise
            int nImg = args.length - 2;
            double[][][] all = new double[nImg][SIZE][SIZE];
            double bg = 0;
            for (int k = 0; k < nImg; k++) {
                BufferedImage im = ImageIO.read(Path.of(args[1 + k]).toFile());
                int x0 = im.getWidth() - OFFSET - SIZE / 2, y0 = im.getHeight() - OFFSET - SIZE / 2;
                double[][] lum = new double[SIZE][SIZE];
                java.util.List<Double> ring = new java.util.ArrayList<>();
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    int p = im.getRGB(x0 + x, y0 + y);
                    lum[y][x] = (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255)) / 3.0;
                    if (x < 4 || y < 4 || x >= SIZE - 4 || y >= SIZE - 4) ring.add(lum[y][x]);
                }
                ring.sort(null);
                double b = ring.get(ring.size() / 2);
                bg += b / nImg;
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) all[k][y][x] = (lum[y][x] - b) / (255 - b);
            }
            double[][] al = new double[SIZE][SIZE];
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                double[] v = new double[nImg];
                for (int k = 0; k < nImg; k++) v[k] = all[k][y][x];
                java.util.Arrays.sort(v);
                double med = nImg % 2 == 1 ? v[nImg / 2] : (v[nImg / 2 - 1] + v[nImg / 2]) / 2;
                al[y][x] = Math.max(0, Math.min(1, med));
            }
            // keep the sparkle only: region connected to the centre (> 0.08) plus a 1 px soft edge
            boolean[][] keep = new boolean[SIZE][SIZE];
            ArrayDeque<int[]> q = new ArrayDeque<>();
            q.add(new int[]{SIZE / 2, SIZE / 2});
            while (!q.isEmpty()) {
                int[] c = q.poll();
                int y = c[0], x = c[1];
                if (y < 0 || x < 0 || y >= SIZE || x >= SIZE || keep[y][x] || al[y][x] <= 0.08) continue;
                keep[y][x] = true;
                q.add(new int[]{y + 1, x}); q.add(new int[]{y - 1, x}); q.add(new int[]{y, x + 1}); q.add(new int[]{y, x - 1});
            }
            BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
            int count = 0;
            double max = 0;
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                boolean near = keep[y][x];
                for (int dy = -1; dy <= 1 && !near; dy++) for (int dx = -1; dx <= 1 && !near; dx++) {
                    int yy = y + dy, xx = x + dx;
                    near = yy >= 0 && xx >= 0 && yy < SIZE && xx < SIZE && keep[yy][xx];
                }
                double v = near ? al[y][x] : 0;
                if (v > 0) count++;
                max = Math.max(max, v);
                int g = (int) Math.round(v * 255);
                out.getRaster().setSample(x, y, 0, g);
            }
            ImageIO.write(out, "png", Path.of(args[args.length - 1]).toFile());
            System.out.printf("bg=%.1f maxAlpha=%.3f pixels=%d%n", bg, max, count);
        } else {
            BufferedImage m = ImageIO.read(Path.of(args[1]).toFile());
            float[][] a = new float[SIZE][SIZE];
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) a[y][x] = m.getRaster().getSample(x, y, 0) / 255f;
            mask = a;
            BufferedImage img = ImageIO.read(Path.of(args[2]).toFile());
            boolean done = remove(img, true);
            int x0 = img.getWidth() - OFFSET - SIZE / 2 - 64, y0 = img.getHeight() - OFFSET - SIZE / 2 - 64;
            BufferedImage crop = img.getSubimage(x0, y0, img.getWidth() - x0, img.getHeight() - y0);
            BufferedImage big = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
            var g = big.createGraphics();
            g.drawImage(crop, 0, 0, 600, 600, null);
            g.dispose();
            ImageIO.write(big, "png", Path.of(args[3]).toFile());
            System.out.println("removed=" + done);
            Files.size(Path.of(args[3]));
        }
    }
}
