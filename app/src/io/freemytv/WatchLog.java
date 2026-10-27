package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What was played in the streaming apps, as their media sessions report it (NowPlaying writes it in the main process;
 * the home screen reads it): the title, the series when the app names it, how far in and for how long. Only the
 * streaming apps the picks know (Recs.isService), only on this TV, the last 200 titles.
 */
final class WatchLog {
    static final class Entry {
        String pkg = "", title = "", show = "";
        long durationMs, positionMs, watchedMs, lastAt;
        private String nTitle, nShow;   // normalized once (find() runs for every title on screen)

        String nTitle() { return nTitle != null ? nTitle : (nTitle = norm(title)); }
        String nShow() { return nShow != null ? nShow : (nShow = norm(show)); }

        /** Watched to the end (the credits count as the end). */
        boolean done() {
            if (durationMs > 0) return positionMs >= durationMs * 0.92f || durationMs - positionMs < 4 * 60_000L && positionMs > durationMs / 2;
            return false;
        }

        /** Started and not finished: 0 to 1, or -1 when unknown. */
        float progress() {
            if (durationMs <= 0 || positionMs <= 0) return -1;
            return Math.min(1, positionMs / (float) durationMs);
        }
    }

    private static final int MAX = 200;
    private static final String FILE = "watch-log.json";
    private static List<Entry> cached;
    private static long cachedAt = -1;

    private WatchLog() {}

    /** The entries, newest first (read again only when the file changed). */
    static synchronized List<Entry> read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        long at = f.exists() ? f.lastModified() : 0;
        if (cached != null && at == cachedAt) return cached;
        List<Entry> out = new ArrayList<>();
        if (at != 0) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                JSONArray a = new JSONArray(new String(b, 0, n, StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Entry e = new Entry();
                    e.pkg = o.optString("pkg");
                    e.title = o.optString("title");
                    e.show = o.optString("show");
                    // Saved by 2.0, which read the cast as the series' name ("A • B • C"): not a name.
                    if (e.show.contains(" • ")) e.show = "";
                    e.durationMs = o.optLong("duration");
                    e.positionMs = o.optLong("position");
                    e.watchedMs = o.optLong("watched");
                    e.lastAt = o.optLong("at");
                    out.add(e);
                }
            } catch (Exception ignored) {
                // A broken file starts over.
            }
        }
        cached = out;
        cachedAt = at;
        return out;
    }

    /** One sample of what's playing: `watchedDelta` ms played since the last sample. */
    static synchronized void update(Context c, String pkg, String title, String show, long duration, long position, long watchedDelta) {
        if (title == null || title.trim().isEmpty()) return;
        List<Entry> list = new ArrayList<>(read(c));
        Entry e = null;
        for (int i = 0; i < list.size(); i++) {
            Entry x = list.get(i);
            if (x.pkg.equals(pkg) && x.title.equals(title)) {
                e = list.remove(i);
                break;
            }
        }
        if (e == null) {
            e = new Entry();
            e.pkg = pkg;
            e.title = title.trim();
        }
        e.show = show == null ? "" : show.trim();   // what the app says now (an older, wrong reading goes)
        if (duration > 0) e.durationMs = duration;
        if (position > 0) e.positionMs = position;
        e.watchedMs += Math.max(0, watchedDelta);
        e.lastAt = System.currentTimeMillis();
        list.add(0, e);
        while (list.size() > MAX) list.remove(list.size() - 1);
        write(c, list);
    }

    private static void write(Context c, List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) {
                a.put(new JSONObject().put("pkg", e.pkg).put("title", e.title).put("show", e.show).put("duration", e.durationMs)
                        .put("position", e.positionMs).put("watched", e.watchedMs).put("at", e.lastAt));
            }
            File f = new File(c.getFilesDir(), FILE), part = new File(c.getFilesDir(), FILE + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save the watch log");
            cached = list;
            cachedAt = f.lastModified();
        } catch (Exception e) {
            AppLog.w("watch log: " + e.getMessage());
        }
    }

    /** Where NowPlaying saves the artwork the app published with this title (it may not exist). */
    static File artFile(Context c, String pkg, String title) {
        return new File(new File(c.getFilesDir(), "watch-art"), "w" + Integer.toHexString((pkg + "|" + title).hashCode()) + ".jpg");
    }

    /** Lists.norm: "Ponniyin Selvan: Part II" → "ponniyin selvan part ii" (tested in tests/ListsTest). */
    static String norm(String s) {
        return Lists.norm(s);
    }

    /** The entry for this title (by the title or by the series' name), or null. */
    static Entry find(List<Entry> log, String title) {
        String t = norm(title);
        if (t.length() < 2) return null;
        for (Entry e : log) {
            if (t.equals(e.nTitle()) || t.equals(e.nShow())) return e;
            // "Suzhal - The Vortex" played as "Suzhal": the app's name is the start of ours (Lists.sameTitle's rule).
            if (Lists.sameNorm(t, e.show.isEmpty() ? e.nTitle() : e.nShow())) return e;
        }
        return null;
    }
}
