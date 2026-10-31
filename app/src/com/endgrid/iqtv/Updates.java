package com.endgrid.iqtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * New versions from GitHub Releases (endgridlabs/iQTV): EndGrid iQTV+ isn't in the Play Store, so the TV would never
 * hear of them. Once a day, while the home screen shows, the latest release is asked for (no account, no key); a newer
 * one is offered in Settings > Personalize > About and said once on the home screen. Installing downloads the release's
 * APK and installs it through the TV's own ADB (as EndGrid iQTV+ mode does its switches), so there's no prompt;
 * Android itself refuses an APK not signed with this app's key. Keys built into this copy (config.properties) are kept
 * as typed ones first: the release APK has none.
 */
final class Updates {
    private Updates() {}

    private static final String LATEST = "https://api.github.com/repos/endgridlabs/iQTV/releases/latest";
    /** Only assets of the official repository are downloaded. */
    private static final String ASSETS = "https://github.com/endgridlabs/iQTV/releases/download/";
    private static final long EVERY = 24 * 3600_000L;

    interface Done { void done(String error); }

    private static volatile boolean checking, installing;
    /** What's happening while installing ("Downloading 40%"), for the About row; "" otherwise. */
    static volatile String status = "";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    /** This app's version name ("2.0.4"). */
    static String current(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** The newer version on GitHub ("2.0.5"), or "" when this one is the latest (or nothing was checked yet). */
    static String available(Context c) {
        String v = sp(c).getString("version", "");
        return newer(v, current(c)) ? v : "";
    }

    /** A newer one the home screen hasn't said yet: said once per version. */
    static String announce(Context c) {
        String v = available(c);
        if (v.isEmpty() || v.equals(sp(c).getString("announced", ""))) return "";
        sp(c).edit().putString("announced", v).apply();
        return v;
    }

    /** "2.0.10" > "2.0.9": numbers compared one by one. */
    static boolean newer(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return false;
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Asks GitHub if the last check is a day old (or `now`); `done` runs on the main thread. */
    static void check(Context context, boolean now, Runnable done) {
        Context c = context.getApplicationContext();
        long at = sp(c).getLong("checkedAt", 0), t = System.currentTimeMillis();
        if (checking || (!now && t - at < EVERY && t >= at)) return;
        checking = true;
        new Thread(() -> {
            try {
                JSONObject r = new JSONObject(get(LATEST));
                String version = r.optString("tag_name").replaceFirst("^v", "");
                String url = "";
                JSONArray assets = r.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    String u = assets.getJSONObject(i).optString("browser_download_url");
                    if (u.startsWith(ASSETS) && u.endsWith(".apk")) url = u;
                }
                sp(c).edit().putString("version", url.isEmpty() ? "" : version).putString("url", url).putLong("checkedAt", t).apply();
            } catch (Exception e) {
                sp(c).edit().putLong("checkedAt", t - EVERY + 3600_000L).apply();   // no network: again in an hour
                AppLog.w("update check: " + e.getMessage());
            } finally {
                checking = false;
                if (done != null) new Handler(Looper.getMainLooper()).post(done);
            }
        }, "update-check").start();
    }

    /** Downloads and installs the newer version; `done` (main thread) gets an error, or "" if this app wasn't replaced. */
    static void install(Context context, Runnable progress, Done done) {
        Context c = context.getApplicationContext();
        String url = sp(c).getString("url", ""), version = available(c);
        Handler main = new Handler(Looper.getMainLooper());
        if (installing || version.isEmpty() || !url.startsWith(ASSETS)) return;
        installing = true;
        new Thread(() -> {
            String error = "";
            try {
                File dir = c.getExternalFilesDir("updates");   // the TV's shell can read here
                if (dir == null) throw new IOException(Ui.tr("No storage for the update."));
                File apk = new File(dir, "iQTV-" + version + ".apk");
                download(url, apk, p -> {
                    status = Ui.trf("Downloading {n}%", "n", p);
                    main.post(progress);
                });
                PackageInfo info = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                if (info == null || !c.getPackageName().equals(info.packageName) || !newer(info.versionName, current(c))) {
                    throw new IOException(Ui.tr("The download isn't a newer EndGrid iQTV+."));
                }
                keepBuiltInKeys();
                status = Ui.tr("Installing…");
                main.post(progress);
                try (Adb adb = Adb.connect(c, 10_000, null)) {
                    AppLog.w("update: installing " + version);
                    // One command: it goes on in the TV's shell after Android stops this app to replace it, then
                    // opens the home screen again.
                    String out = adb.shell("pm install -r '" + apk.getPath() + "' && am start -a android.intent.action.MAIN"
                            + " -c android.intent.category.HOME");
                    if (!out.contains("Success")) throw new IOException(out.trim());
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("update: " + error);
            } finally {
                installing = false;
                status = "";
                String result = error;
                main.post(() -> done.done(result));
            }
        }, "update-install").start();
    }

    /** The release APK has no keys: the ones this copy was built with become typed ones, which an update keeps. */
    private static void keepBuiltInKeys() {
        if (!Prefs.tmdbKeyTyped() && !Config.TMDB_KEY.trim().isEmpty()) Prefs.setTmdbKey(Config.TMDB_KEY);
        if (!Prefs.watchmodeKeyTyped() && !Config.WATCHMODE_KEY.trim().isEmpty()) Prefs.setWatchmodeKey(Config.WATCHMODE_KEY);
        if (!Prefs.geminiKeyTyped() && !Config.GEMINI_KEY.trim().isEmpty()) Prefs.setGeminiKey(Config.GEMINI_KEY);
        if (!Prefs.omdbKeyTyped() && !Config.OMDB_KEY.trim().isEmpty()) Prefs.setOmdbKey(Config.OMDB_KEY);
    }

    interface Progress { void percent(int p); }

    private static void download(String url, File to, Progress progress) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();   // GitHub redirects to its file host
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + c.getResponseCode());
            long size = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int last = -1;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    got += n;
                    int p = size > 0 ? (int) (got * 100 / size) : 0;
                    if (p / 10 != last / 10) progress.percent(last = p);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
