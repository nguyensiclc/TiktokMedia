package com.mediastudio.voice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Narration keeps numbers as digits (938, 10.000, 12/3/938, 1,3, 40%) so subtitles and slides show them that way;
 * before the text goes to a Vietnamese voice they are spelled out, so the voice reads them correctly
 * ("chín trăm ba mươi tám", "mười nghìn", "mười hai tháng ba năm chín trăm ba mươi tám", "một phẩy ba", "bốn mươi phần trăm").
 */
public final class SpokenNumbers {
    private SpokenNumbers() { }

    private static final String[] DIGIT = {"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};
    private static final String[] GROUP = {"", " nghìn", " triệu", " tỷ"};

    // d/m/y or d/m (dates), then numbers with "." thousand groups and an optional ",decimals", then plain digits.
    private static final Pattern DATE = Pattern.compile("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?:/(\\d{1,4}))?(?![\\d/])");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,(\\d+))?(?![\\d])(\\s*%)?");
    private static final Pattern ORDINAL = Pattern.compile("(?i)(?<![\\p{L}])(thứ)\\s+([14])(?!\\d)");

    /** Spells out the numbers of Vietnamese narration. Text without digits is returned unchanged. */
    public static String vietnamese(String text) {
        if (text == null || !text.matches("(?s).*\\d.*")) return text;
        String s = ORDINAL.matcher(text).replaceAll(m -> m.group(1) + ("1".equals(m.group(2)) ? " nhất" : " tư"));
        s = replace(DATE, s, m -> {
            int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2));
            if (d < 1 || d > 31 || mo < 1 || mo > 12) return m.group();
            String out = words(d) + " tháng " + (mo == 4 ? "tư" : words(mo));
            return m.group(3) == null ? out : out + " năm " + words(Long.parseLong(m.group(3)));
        });
        s = replace(NUMBER, s, m -> {
            String intPart = m.group(1).replace(".", "");
            if (intPart.length() > 15) return m.group(); // a code / id, not a quantity
            String out = words(Long.parseLong(intPart));
            if (m.group(2) != null) out += " phẩy " + decimals(m.group(2));
            if (m.group(3) != null) out += " phần trăm";
            return out;
        });
        return s;
    }

    private interface Fn { String apply(Matcher m); }

    private static String replace(Pattern p, String s, Fn f) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }

    /** "05" → "không năm", "3" → "ba", "25" → "hai mươi lăm" (short decimals read as a number, long ones digit by digit). */
    private static String decimals(String d) {
        if (d.length() <= 2 && !d.startsWith("0")) return words(Long.parseLong(d));
        StringBuilder sb = new StringBuilder();
        for (char c : d.toCharArray()) sb.append(sb.isEmpty() ? "" : " ").append(DIGIT[c - '0']);
        return sb.toString();
    }

    /** Vietnamese words for 0 … 999 999 999 999 999 ("nghìn tỷ" above a billion). */
    static String words(long n) {
        if (n == 0) return "không";
        if (n >= 1_000_000_000_000L) {
            long hi = n / 1_000_000_000L, lo = n % 1_000_000_000L;
            return words(hi) + " tỷ" + (lo == 0 ? "" : " " + groups(lo, true));
        }
        return groups(n, false);
    }

    private static String groups(long n, boolean padFirst) {
        int[] g = new int[4];
        int count = 0;
        for (long x = n; x > 0 || count == 0; x /= 1000) g[count++] = (int) (x % 1000);
        StringBuilder sb = new StringBuilder();
        for (int i = count - 1; i >= 0; i--) {
            if (g[i] == 0) continue;
            boolean full = i < count - 1 || padFirst; // an inner group says "không trăm" / "lẻ"
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(hundreds(g[i], full)).append(GROUP[i]);
        }
        return sb.toString();
    }

    private static String hundreds(int n, boolean full) {
        int h = n / 100, t = (n / 10) % 10, u = n % 10;
        StringBuilder sb = new StringBuilder();
        if (h > 0 || full) sb.append(DIGIT[h]).append(" trăm");
        if (t == 0) {
            if (u > 0) sb.append(sb.isEmpty() ? "" : " lẻ ").append(DIGIT[u]);
        } else {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(t == 1 ? "mười" : DIGIT[t] + " mươi");
            if (u == 1) sb.append(t == 1 ? " một" : " mốt");
            else if (u == 4) sb.append(t == 1 ? " bốn" : " tư");
            else if (u == 5) sb.append(" lăm");
            else if (u > 0) sb.append(' ').append(DIGIT[u]);
        }
        return sb.toString();
    }
}
