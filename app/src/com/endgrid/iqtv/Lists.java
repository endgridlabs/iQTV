package com.endgrid.iqtv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plain helpers shared by the preferences and the picks, with no Android in them (tests/ runs them on a computer):
 * comma lists, title matching, and moving an item in an ordered list.
 */
final class Lists {
    private Lists() {}

    /** "ta, en,," → ["ta", "en"] (trimmed, lowercase for codes and keys; empty parts dropped). */
    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split(",")) {
            String p = part.trim();
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) sb.append(sb.length() == 0 ? "" : ",").append(s);
        return sb.toString();
    }

    /** `item` moved one place (dir -1 earlier, +1 later) in a copy of `list`; the same order if it can't move. */
    static List<String> move(List<String> list, String item, int dir) {
        List<String> out = new ArrayList<>(list);
        int at = out.indexOf(item), to = at + dir;
        if (at < 0 || to < 0 || to >= out.size()) return out;
        out.remove(at);
        out.add(to, item);
        return out;
    }

    /** The lists taking turns, one item each ([a1 b1 a2 b2 a3…]); a list that runs out drops out, repeats stay. */
    static List<String> roundRobin(List<List<String>> lists) {
        List<String> out = new ArrayList<>();
        for (int i = 0, left = lists.size(); left > 0; i++) {
            left = 0;
            for (List<String> list : lists) {
                if (i < list.size()) {
                    out.add(list.get(i));
                    left++;
                }
            }
        }
        return out;
    }

    // Compiled once: String.replaceAll compiles its pattern on every call, and matching each pick against the watch log
    // normalizes thousands of titles (measured on a OnePlus TV: 8.5 s on the main thread, every minute).
    private static final java.util.regex.Pattern NOT_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");

    /** "Ponniyin Selvan: Part II" → "ponniyin selvan part ii": how a title an app reports is matched to a pick. */
    static String norm(String s) {
        if (s == null) return "";
        return NOT_WORD.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /**
     * The same title: equal once normalized, or one the start of the other at a word boundary when the shorter is at
     * least 5 characters ("Suzhal" played, "Suzhal - The Vortex" picked).
     */
    static boolean sameTitle(String a, String b) {
        return sameNorm(norm(a), norm(b));
    }

    /** sameTitle for titles already normalized (norm). */
    static boolean sameNorm(String x, String y) {
        if (x.length() < 2 || y.length() < 2) return false;
        if (x.equals(y)) return true;
        String shorter = x.length() < y.length() ? x : y, longer = shorter == x ? y : x;
        return shorter.length() >= 5 && longer.startsWith(shorter + " ");
    }

    /** Rank of `key` in a preference order (0 first); unknown keys after all known ones. */
    static int rank(List<String> order, String key) {
        int at = order.indexOf(key);
        return at < 0 ? order.size() : at;
    }
}
