package com.endgrid.iqtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        boolean detailed;
        java.io.File photo;     // one of your own photos (Photos): no title, no caption
        int[] genres = new int[0];   // TMDB genre ids (the taste that orders the row)
        float progress = -1;    // started in its app and not finished: how far (0 to 1); -1 otherwise
        boolean web;            // a series first shown on a streaming service (a web series), not a TV channel
        String overview = "";   // TMDB's short description, in the TV's language when TMDB has it ("" without)
        String year = "";       // release or first air year
        String lang = "";       // the language it was made in (TMDB's original_language)
        String logo = "", poster = "";   // TMDB image paths: the title's logo art (transparent) and its poster ("" without)
        String thumb;   // "file:…": the artwork the app itself showed while it played (WatchLog), when TMDB has none
    }

    /** A Top Shelf row: its title and its titles, every one on an app this TV has (TMDB's providers for region()). */
    static final class Section {
        final String id, title;
        final List<Pick> items;

        Section(String id, String title, List<Pick> items) {
            this.id = id;
            this.title = title;
            this.items = items;
        }
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 7;   // 7: logo and poster saved with each title
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Each provider's app packages as rows {TMDB name, service key, package} (Providers is the source). */
    private static final String[][] SERVICES;
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        List<String[]> rows = new ArrayList<>();
        for (Providers.Provider p : Providers.ALL) {
            SERVICE_NAME.put(p.key, p.name);
            for (String pkg : p.packages) rows.add(new String[] {p.tmdbNames.length > 0 ? p.tmdbNames[0] : "", p.key, pkg});
        }
        SERVICES = rows.toArray(new String[0][]);
    }

    /** Country for "what streams where" (Settings > Language & Region; config.properties "tmdb_region" at first). */
    static String region() {
        return Prefs.region();
    }

    /**
     * Only titles first made in the preferred content languages (Settings > Language & Region; TMDB's codes: ta, hi,
     * ko…), as TMDB's OR list "ta|ml"; empty = any. The worldwide trending list then gives way to what's popular in
     * those languages.
     */
    static String original() {
        return Lists.join(Prefs.contentLanguages()).toLowerCase(Locale.ROOT).replace(',', '|');
    }

    /** One of the streaming apps the picks come from (what NowPlaying may follow). */
    static boolean isService(String pkg) {
        return Providers.byPackage(pkg) != null;
    }

    /** The errors the settings say in words: TMDB lists none of the TV's apps in region(), or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    // Built and ready to draw; the least recently drawn goes first (access order): the rows on screen stay.
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile boolean shelf;     // Top Shelf design: thumbnails for the row; the full photo only for the focused one
    /** Top Shelf: the full-screen photos, the latest few (8 MB of GPU memory each). */
    private final Map<String, Bitmap> full = new LinkedHashMap<>(4, 0.75f, true);
    private final java.util.Set<String> fullPending = new java.util.HashSet<>();
    private final Map<String, Backdrop> fullGlass = new HashMap<>();
    private final ExecutorService art = Executors.newSingleThreadExecutor();     // decodes, never waits on the network
    private final ExecutorService lookup = Executors.newSingleThreadExecutor();  // voice search
    private final SharedPreferences taste;
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        taste = c.getSharedPreferences("taste", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            sectionKeys = parseSections(cache.getString("sections", "{}"));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Prefs.tmdbKey().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    // MARK: - Top Shelf row: personal order, what you finished goes, what you started shows how far

    /**
     * Up to `max` titles for the row, movies ("movie"), shows ("tv") or both (null): titles you finished in their app
     * or hid ("Not for me") left out, the ones you started first, then by how well they fit what you watch (genres you
     * watch more, apps you use more, genres you hid less); this hour's featured title stays first among the rest, so
     * the row still changes during the day.
     */
    List<Pick> row(String kind, int max) {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        Taste t = taste();
        Map<String, Boolean> have = new HashMap<>();
        List<Pick> list = new ArrayList<>();
        for (Pick p : picks) {
            if ((kind != null && !kind.equals(p.kind)) || t.hidden.contains(key(p))) continue;
            // Only what TMDB says streams on an app this TV has (details known): nothing shown as available on a guess.
            if (!p.detailed || p.pkg == null || !have.computeIfAbsent(p.pkg, this::installed)) continue;
            if (seenBefore(t, p)) continue;
            p.progress = -1;
            list.add(p);
        }
        if (list.isEmpty()) return out;
        long hour = System.currentTimeMillis() / 3600_000L;
        Pick featured = list.get((int) ((hour * 3) % list.size()));
        Map<Pick, Float> score = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            Pick p = list.get(i);
            float s = t.score(p) - 0.01f * i;    // TMDB's order (popularity) breaks ties
            if (p.progress > 0) s += 100;
            if (p == featured) s += 0.5f;
            score.put(p, s);
        }
        java.util.Collections.sort(list, (a, b) -> Float.compare(score.get(b), score.get(a)));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : list) if (out.size() < max && seen.add(franchise(p.title))) out.add(p);
        return out;
    }

    // MARK: - Top Shelf sections

    private static final String CONTINUE = "continue", RECENT = "recent", FEATURED = "featured", POPULAR = "popular",
            LATEST = "latest", SHOWS = "shows", WEB = "web", TRENDING = "trending", NEW = "new";
    /** Each fetched section's titles (Recs.key), in TMDB's order; the Pick objects are in `picks`. */
    private volatile Map<String, List<String>> sectionKeys = new HashMap<>();

    /**
     * The rows, in order, each with only what streams on an app this TV has: Continue Watching and Recently Watched
     * (only from what the apps reported playing: WatchLog), Featured for You (your taste), then the content_language
     * rows (popular and latest movies, TV shows, web series), Trending Now and New Releases. Empty rows are left out;
     * hidden titles ("Not for me") and ones you already watched never come back as suggestions.
     */
    List<Section> sections() {
        // Asked by every redraw of the list (each image that arrives): made again at most every 3 s, or when the
        // lists, your taste or the hidden titles changed.
        long now = android.os.SystemClock.uptimeMillis(), stamp = (listAt * 31 + tasteAt) * 31 + Prefs.version;
        List<Section> cached = sectionsNow;
        if (cached != null && stamp == sectionsStamp && now - sectionsAt < 3000) return cached;
        List<Section> out = makeSections(false);
        highlightsNow = makeSections(true);
        sectionsNow = out;
        sectionsStamp = (listAt * 31 + tasteAt) * 31 + Prefs.version;
        sectionsAt = now;
        return out;
    }

    /**
     * Every row, whether on or off in Settings > Home Rows: where the hero's featured titles come from, so turning the
     * rows off hides the rows, not the highlights.
     */
    List<Section> highlights() {
        sections();
        List<Section> h = highlightsNow;
        return h == null ? new ArrayList<>() : h;
    }

    /** Whether any row is on in Settings > Home Rows. */
    static boolean anyRowOn() {
        for (String id : rowOrder()) if (rowOn(id)) return true;
        return false;
    }

    private volatile List<Section> sectionsNow, highlightsNow;
    private volatile long sectionsAt, sectionsStamp;

    /** Watched or started in its app: in Continue Watching or Recently Watched, not suggested again (unless asked). */
    private static boolean seenBefore(Taste t, Pick p) {
        WatchLog.Entry e = t.watched(p.title);
        return e != null && (!e.done() || !Prefs.suggestWatched());
    }

    /** The rows' ids in their default order (Settings > Home Rows changes it): what you were watching, what's on now, your
     *  languages, then what fits you; Recently Watched and New Releases last. */
    static final List<String> DEFAULT_ROWS = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
            CONTINUE, TRENDING, POPULAR, LATEST, SHOWS, WEB, FEATURED, "genres", RECENT, NEW));

    /** The rows as Settings shows them, in your order: {id, name, "1" on / "0" off}. "genres" stands for the genre rows. */
    static List<String[]> rowChoices() {
        List<String[]> out = new ArrayList<>();
        for (String id : rowOrder()) {
            boolean off = Prefs.rows() != null && Prefs.rows().contains("-" + id);
            out.add(new String[] {id, rowName(id), off ? "0" : "1"});
        }
        return out;
    }

    /** Every row id in your order (new ones at their default place), whether on or off. */
    static List<String> rowOrder() {
        List<String> saved = Prefs.rows(), out = new ArrayList<>();
        if (saved != null) for (String s : saved) {
            String id = s.startsWith("-") ? s.substring(1) : s;
            if (DEFAULT_ROWS.contains(id) && !out.contains(id)) out.add(id);
        }
        for (int i = 0; i < DEFAULT_ROWS.size(); i++) {
            String id = DEFAULT_ROWS.get(i);
            if (!out.contains(id)) out.add(Math.min(i, out.size()), id);
        }
        return out;
    }

    static boolean rowOn(String id) {
        List<String> saved = Prefs.rows();
        return saved == null || !saved.contains("-" + id);
    }

    /** A row's name: "Popular Tamil Movies" with Tamil first among your content languages. */
    static String rowName(String id) {
        String lang = languageName();
        switch (id) {
            case CONTINUE: return Ui.tr("Continue Watching");
            case RECENT: return Ui.tr("Recently Watched");
            case FEATURED: return Ui.trf("Recommended for {name}", "name", Prefs.viewerName().isEmpty() ? Ui.tr("You") : Prefs.viewerName());
            case "genres": return Ui.tr("Your genres");
            case POPULAR: return lang.isEmpty() ? Ui.tr("Popular Movies") : Ui.trf("Popular {language} Movies", "language", lang);
            case LATEST: return lang.isEmpty() ? Ui.tr("Latest Movies") : Ui.trf("Latest {language} Movies", "language", lang);
            case SHOWS: return lang.isEmpty() ? Ui.tr("TV Shows") : Ui.trf("{language} TV Shows", "language", lang);
            case WEB: return lang.isEmpty() ? Ui.tr("Web Series") : Ui.trf("{language} Web Series", "language", lang);
            case TRENDING: return Ui.tr("Trending Now");
            case NEW: return Ui.tr("New Releases");
            default: return id;
        }
    }

    /** The rows in your order: the ones that are on, or with `all` every one (highlights). */
    private List<Section> makeSections(boolean all) {
        List<Section> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        Taste t = taste();
        List<Pick> started = new ArrayList<>(), seen = new ArrayList<>();
        watched(t, started, seen);
        for (String id : rowOrder()) {
            if (!all && !rowOn(id)) continue;
            switch (id) {
                case CONTINUE: add(out, id, rowName(id), started); break;
                case RECENT: add(out, id, rowName(id), seen); break;
                case FEATURED: add(out, id, rowName(id), row(null, ROW_MAX)); break;
                case "genres":
                    // One row per preferred genre (Settings > Recommendations), in your language and on your apps.
                    for (String g : Prefs.genres()) {
                        String name = genreName(g);
                        if (!name.isEmpty()) add(out, "genre:" + g, Ui.trf("{genre} for You", "genre", name), keyed(t, "genre:" + g));
                    }
                    break;
                default: add(out, id, rowName(id), keyed(t, id)); break;
            }
        }
        return out;
    }

    private static void add(List<Section> out, String id, String title, List<Pick> items) {
        if (!items.isEmpty()) out.add(new Section(id, title, items));
    }

    // MARK: - Catalogs for Settings: languages, regions, genres (TMDB's lists, saved for a week)

    interface Loaded { void onLoaded(List<String[]> items); }

    private static final long CATALOG_DAYS = 7 * 86_400_000L;

    /**
     * {code, name} lists from TMDB: "languages" (every content language TMDB knows), "regions" (countries with
     * streaming data), "genres" (movie and TV genres, by id). From the saved copy when fresh; `loaded` runs on the main
     * thread, also with the saved copy when TMDB can't be reached (empty without one).
     */
    void catalog(String which, Loaded loaded) {
        String saved = cache.getString("catalog:" + which, null);
        long at = cache.getLong("catalogAt:" + which, 0);
        if (saved != null && System.currentTimeMillis() - at < CATALOG_DAYS) {
            loaded.onLoaded(parseCatalog(saved));
            return;
        }
        lookup.execute(() -> {
            String json = saved;
            try {
                JSONArray out = new JSONArray();
                if (which.equals("languages")) {
                    JSONArray a = new JSONArray(getText("/configuration/languages"));
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        out.put(new JSONArray().put(o.optString("iso_639_1")).put(o.optString("english_name")));
                    }
                } else if (which.equals("regions")) {
                    JSONArray a = get("/watch/providers/regions?language=" + lang()).getJSONArray("results");
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        out.put(new JSONArray().put(o.optString("iso_3166_1")).put(o.optString("native_name", o.optString("english_name"))));
                    }
                } else {
                    java.util.Set<Integer> ids = new java.util.HashSet<>();
                    for (String kind : new String[] {"movie", "tv"}) {
                        JSONArray a = get("/genre/" + kind + "/list?language=" + lang()).getJSONArray("genres");
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject o = a.getJSONObject(i);
                            if (ids.add(o.getInt("id"))) out.put(new JSONArray().put(String.valueOf(o.getInt("id"))).put(genre(o.optString("name"))));
                        }
                    }
                }
                json = out.toString();
                cache.edit().putString("catalog:" + which, json).putLong("catalogAt:" + which, System.currentTimeMillis()).apply();
            } catch (Exception e) {
                AppLog.w("catalog " + which + ": " + e.getMessage());
            }
            List<String[]> list = json == null ? new ArrayList<>() : parseCatalog(json);
            main.post(() -> loaded.onLoaded(list));
        });
    }

    private static List<String[]> parseCatalog(String json) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONArray item = a.getJSONArray(i);
                if (!item.optString(0).isEmpty()) out.add(new String[] {item.optString(0), item.optString(1)});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** A genre's name from the saved catalog ("" until Settings or a fetch has loaded it). */
    String genreName(String id) {
        for (String[] g : parseCatalog(cache.getString("catalog:genres", "[]"))) if (g[0].equals(id)) return g[1];
        return "";
    }

    // MARK: - Recommendations reset

    /** How many titles you hid with "Not for me". */
    int hiddenCount() {
        return taste.getStringSet("hidden", new HashSet<>()).size();
    }

    /** The hidden titles come back (the genres you turned down count again as before). */
    void clearHidden() {
        SharedPreferences.Editor e = taste.edit().remove("hidden");
        for (String k : taste.getAll().keySet()) if (k.startsWith("dislike:")) e.remove(k);
        e.apply();
        tasteAt = 0;
        notifyListeners();
    }

    /** Continue Watching shows at most this many, the most recent first. */
    static final int CONTINUE_MAX = 10;

    /** Off Continue Watching and Recently Watched (holding OK on it), until it's played again. */
    void forgetWatched(Pick p) {
        taste.edit().putLong("cwhide:" + WatchLog.norm(p.title), System.currentTimeMillis()).apply();
        tasteAt = 0;
        notifyListeners();
    }

    int forgottenCount() {
        int n = 0;
        for (String k : taste.getAll().keySet()) if (k.startsWith("cwhide:")) n++;
        return n;
    }

    /** Everything removed from Continue Watching comes back. */
    void clearForgotten() {
        SharedPreferences.Editor e = taste.edit();
        for (String k : taste.getAll().keySet()) if (k.startsWith("cwhide:")) e.remove(k);
        e.apply();
        tasteAt = 0;
        notifyListeners();
    }

    /** "Tamil" for content_language=ta (the first language, in the TV's language); "" without one. */
    static String languageName() {
        if (original().isEmpty()) return "";
        String code = original().split("\\|")[0];
        String name = new Locale(code).getDisplayLanguage(Ui.locale());
        return name.isEmpty() || name.equals(code) ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** A fetched section's titles that may show now: on an installed app, not hidden, not watched. */
    private List<Pick> keyed(Taste t, String id) {
        List<String> keys = sectionKeys.get(id);
        List<Pick> out = new ArrayList<>();
        if (keys == null) return out;
        Map<String, Pick> byKey = new HashMap<>();
        for (Pick p : picks) byKey.put(key(p), p);
        Map<String, Boolean> have = new HashMap<>();
        java.util.Set<String> franchises = new java.util.HashSet<>();
        for (String k : keys) {
            Pick p = byKey.get(k);
            if (p == null || !p.detailed || p.pkg == null || t.hidden.contains(k)) continue;
            if (!have.computeIfAbsent(p.pkg, this::installed) || seenBefore(t, p)) continue;
            if (out.size() < ROW_MAX && franchises.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /**
     * Continue Watching (started, not finished) and Recently Watched (finished), newest first, from what the
     * streaming apps reported playing in the last 30 days. Each opens in the app it was played in; how far you got is
     * the app's own position. The artwork is TMDB's for the same title (matched by name, looked up in the background);
     * without a sure match, the tile shows the name instead of a guessed picture.
     */
    private void watched(Taste t, List<Pick> started, List<Pick> finished) {
        long since = System.currentTimeMillis() - 30 * 86_400_000L;
        java.util.Set<String> shown = new java.util.HashSet<>();
        boolean unresolved = false;
        for (WatchLog.Entry e : t.log) {
            if (e.lastAt < since || !installed(e.pkg)) continue;
            String name = e.show.isEmpty() ? e.title : e.show;
            if (!shown.add(WatchLog.norm(name))) continue;   // a series once, at its latest episode
            if (taste.getLong("cwhide:" + WatchLog.norm(name), 0) >= e.lastAt) continue;   // removed, not played since
            if (!e.done() && started.size() >= CONTINUE_MAX) continue;
            Pick p = new Pick();
            p.kind = "watched";
            p.title = name;
            p.pkg = e.pkg;
            p.app = serviceOf(e.pkg);
            p.detailed = true;
            String found = taste.getString(WATCHED_KEY + WatchLog.norm(name), null);
            if (found == null) {
                unresolved = true;
            } else if (!found.startsWith("-")) {
                String[] f = found.split("\\|", 4);   // kind|id|backdrop|title
                if (f.length == 4) {
                    p.kind = f[0];
                    p.id = Integer.parseInt(f[1]);
                    p.backdrop = f[2].isEmpty() ? null : f[2];
                }
            }
            if (p.id == 0) p.id = WatchLog.norm(name).hashCode();
            // No TMDB picture (an episode's name, say): the one the app published with what it played.
            File art = p.backdrop == null ? WatchLog.artFile(context, e.pkg, e.title) : null;
            if (art != null && art.exists()) p.thumb = "file:" + art.getPath();
            String app = p.app == null ? "" : SERVICE_NAME.get(p.app);
            if (e.done()) {
                p.meta = app;
                finished.add(p);
            } else {
                p.progress = e.progress();
                long left = e.durationMs > 0 ? (e.durationMs - e.positionMs) / 60_000L : -1;
                String rest = left > 0 ? Ui.trf("{n} min left", "n", left) : "";
                p.meta = rest.isEmpty() ? app : app.isEmpty() ? rest : rest + " · " + app;
                started.add(p);
            }
        }
        if (unresolved) resolveWatched(t.log);
    }

    /** The streaming-service key of an app package ("hotstar"), or null. */
    private static String serviceOf(String pkg) {
        for (String[] s : SERVICES) if (s[2].equals(pkg)) return s[1];
        return null;
    }

    private volatile long resolvingAt;
    /** Where a watched title's TMDB match is kept ("wl2:": looked up again since films match in any language). */
    private static final String WATCHED_KEY = "wl2:";
    /** Played this long, with no series named: a film (episodes are shorter). */
    private static final long FILM_MS = 75 * 60_000L;

    /** Finds TMDB's artwork for watched titles not looked up yet (a few per call, at most every 2 minutes). */
    private void resolveWatched(List<WatchLog.Entry> log) {
        long now = System.currentTimeMillis();
        if (now - resolvingAt < 120_000L && now >= resolvingAt) return;
        resolvingAt = now;
        lookup.execute(() -> {
            int n = 0;
            boolean any = false;
            for (WatchLog.Entry e : log) {
                String name = e.show.isEmpty() ? e.title : e.show;
                String k = WATCHED_KEY + WatchLog.norm(name);
                if (taste.contains(k) || n++ >= 6) continue;
                boolean film = e.show.isEmpty() && e.durationMs >= FILM_MS;
                String value = "-";
                try {
                    JSONArray results = get("/search/multi?include_adult=false&language=" + lang() + "&region=" + region()
                            + "&query=" + URLEncoder.encode(name, "UTF-8")).getJSONArray("results");
                    for (int i = 0; i < results.length() && i < 5; i++) {
                        JSONObject o = results.getJSONObject(i);
                        String kind = o.optString("media_type");
                        if (!kind.equals("movie") && !kind.equals("tv") || film && !kind.equals("movie")) continue;
                        String title = o.optString(kind.equals("movie") ? "title" : "name");
                        String original = o.optString(kind.equals("movie") ? "original_title" : "original_name");
                        // Only the same name: a near match would put another title's picture on what you watched.
                        if (!WatchLog.norm(title).equals(WatchLog.norm(name)) && !WatchLog.norm(original).equals(WatchLog.norm(name))) continue;
                        // And in one of your languages when you have some: an episode named "The Resolution" isn't the
                        // English film of that name. A film the app played whole-length (Moana) matches in any language.
                        List<String> langs = Prefs.contentLanguages();
                        if (!film && !langs.isEmpty() && !langs.contains(o.optString("original_language"))) continue;
                        String backdrop = o.optString("backdrop_path", "");
                        value = kind + "|" + o.optInt("id") + "|" + ("null".equals(backdrop) ? "" : backdrop) + "|" + title;
                        break;
                    }
                } catch (Exception ex) {
                    continue;   // offline: try again later
                }
                taste.edit().putString(k, value).apply();
                any = true;
            }
            if (any) {
                tasteAt = 0;
                notifyListeners();
            }
        });
    }

    /** A pick from a TMDB list item, in any language (the sections choose their own), or null without a picture. */
    private static Pick item(JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return null;
        if (Config.HIDE_ANIMATION && animated(o)) return null;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.lang = o.optString("original_language", "");
        String poster = o.optString("poster_path", "");
        p.poster = "null".equals(poster) ? "" : poster;
        return p;
    }

    /**
     * Fetches every section from TMDB, then each title's details (four at a time): genre, length, and above all where it
     * streams in region(), which decides whether it shows at all.
     */
    private void fetchSections(int period) throws Exception {
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        StringBuilder ids = new StringBuilder();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + region()).getJSONArray("results");
        JSONArray tvProviders = get("/watch/providers/tv?language=" + lang() + "&watch_region=" + region()).getJSONArray("results");
        java.util.Set<Integer> found = new java.util.HashSet<>();
        for (JSONArray list : new JSONArray[] {providers, tvProviders}) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                String[] s = service(o.optString("provider_name"), o.optInt("provider_id"));
                if (s != null && installed(s[2]) && found.add(o.getInt("provider_id"))) ids.append(ids.length() == 0 ? "" : "|").append(o.getInt("provider_id"));
            }
        }
        if (ids.length() == 0) throw new IOException(NO_APPS);
        String on = "&language=" + lang() + "&watch_region=" + region() + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8") + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        String in = original().isEmpty() ? "" : "&with_original_language=" + URLEncoder.encode(original(), "UTF-8");
        int votes = original().isEmpty() ? 50 : 3;
        Map<String, Pick> all = new LinkedHashMap<>();
        Map<String, List<String>> keys = new LinkedHashMap<>();
        keys.put(POPULAR, collect(all, "movie", "/discover/movie?sort_by=popularity.desc&vote_count.gte=" + votes + on + in, 2));
        keys.put(LATEST, collect(all, "movie", "/discover/movie?sort_by=primary_release_date.desc&primary_release_date.lte=" + day(0)
                + "&primary_release_date.gte=" + day(-240) + on + in, 2));
        List<String> series = collect(all, "tv", "/discover/tv?sort_by=popularity.desc" + on + in, 3);
        List<String> trending = new ArrayList<>();
        JSONArray hot = get("/trending/all/week?language=" + lang()).getJSONArray("results");
        for (int i = 0; i < hot.length(); i++) {
            JSONObject o = hot.getJSONObject(i);
            String kind = o.optString("media_type");
            if (!kind.equals("movie") && !kind.equals("tv")) continue;
            Pick p = item(o, kind);
            if (p != null) trending.add(remember(all, p));
        }
        keys.put(TRENDING, trending);
        List<String> fresh = collect(all, "movie", "/discover/movie?sort_by=popularity.desc&vote_count.gte=5&primary_release_date.lte="
                + day(0) + "&primary_release_date.gte=" + day(-75) + on, 1);
        fresh.addAll(collect(all, "tv", "/discover/tv?sort_by=popularity.desc&first_air_date.lte=" + day(0)
                + "&first_air_date.gte=" + day(-75) + on, 1));
        keys.put(NEW, fresh);
        // Your genres (Settings > Recommendations): movies and shows of each, in your languages, on your apps.
        int genreRows = 0;
        for (String g : Prefs.genres()) {
            if (genreRows++ >= 4) break;
            String with = "&with_genres=" + URLEncoder.encode(g, "UTF-8");
            List<String> list = collect(all, "movie", "/discover/movie?sort_by=popularity.desc&vote_count.gte=" + votes + with + on + in, 1);
            list.addAll(collect(all, "tv", "/discover/tv?sort_by=popularity.desc" + with + on + in, 1));
            keys.put("genre:" + g, list);
        }
        // Details, four at a time (each is one request): what's missing from the saved list only.
        Map<String, Pick> known = new HashMap<>();
        for (Pick p : picks) if (p.detailed) known.put(key(p), p);
        List<java.util.concurrent.Callable<Void>> jobs = new ArrayList<>();
        for (Map.Entry<String, Pick> e : all.entrySet()) {
            Pick old = known.get(e.getKey());
            if (old != null && listLanguage.equals(Ui.language)) {
                e.setValue(old);
                continue;
            }
            Pick p = e.getValue();
            jobs.add(() -> {
                try {
                    fetchDetails(p);
                } catch (Exception ignored) {
                    // Left undetailed: it doesn't show (where it streams is unknown).
                }
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            pool.invokeAll(jobs);
        } finally {
            pool.shutdown();
        }
        List<String> shows = new ArrayList<>(), web = new ArrayList<>();
        for (String k : series) {
            Pick p = all.get(k);
            if (p != null) (p.web ? web : shows).add(k);
        }
        keys.put(SHOWS, shows);
        keys.put(WEB, web);
        picks = new ArrayList<>(all.values());
        sectionKeys = keys;
        this.period = period;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
        tasteAt = 0;
    }

    /** Up to `pages` pages of a TMDB list, as keys (the picks themselves go into `all`, one object per title). */
    private List<String> collect(Map<String, Pick> all, String kind, String path, int pages) throws Exception {
        List<String> out = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            JSONObject r = get(path + "&page=" + page);
            JSONArray results = r.getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                Pick p = item(results.getJSONObject(i), kind);
                if (p != null) out.add(remember(all, p));
            }
            if (page >= r.optInt("total_pages", 1)) break;
        }
        return out;
    }

    private static String remember(Map<String, Pick> all, Pick p) {
        String k = key(p);
        if (!all.containsKey(k)) all.put(k, p);
        return k;
    }

    private static Map<String, List<String>> parseSections(String json) {
        Map<String, List<String>> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONArray a = o.getJSONArray(id);
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) keys.add(a.getString(i));
                out.put(id, keys);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** TMDB's networks of a series: a streaming service among them makes it a web series (not a TV channel's show). */
    private static boolean streamingOriginal(JSONArray networks) {
        for (int i = 0; networks != null && i < networks.length(); i++) {
            String n = networks.optJSONObject(i) == null ? "" : networks.optJSONObject(i).optString("name").toLowerCase(Locale.ROOT);
            for (String s : new String[] {"netflix", "prime video", "amazon", "hotstar", "disney", "zee5", "sonyliv", "sony liv",
                    "aha", "sun nxt", "sunnxt", "mx player", "jiocinema", "voot", "alt balaji", "hoichoi", "apple tv", "airtel"}) {
                if (n.contains(s)) return true;
            }
        }
        return false;
    }

    /** "Not for me": the title leaves the row for good, and its genres count a little against similar ones. */
    void hide(Pick p) {
        if (p == null) return;
        Set<String> hidden = new HashSet<>(taste.getStringSet("hidden", new HashSet<>()));
        hidden.add(key(p));
        SharedPreferences.Editor e = taste.edit().putStringSet("hidden", hidden);
        for (int g : p.genres) e.putFloat("dislike:" + g, taste.getFloat("dislike:" + g, 0) + 1);
        e.apply();
        tasteAt = 0;
        notifyListeners();
    }

    /** What the row is ordered by, made again at most once a minute (or when you hide a title). */
    private static final class Taste {
        final List<WatchLog.Entry> log;
        final Set<String> hidden;
        final Map<Integer, Float> genres = new HashMap<>();
        final Map<String, Float> apps = new HashMap<>();

        private final Map<String, WatchLog.Entry> found = new HashMap<>();

        Taste(List<WatchLog.Entry> log, Set<String> hidden) {
            this.log = log;
            this.hidden = hidden;
        }

        /** The watch-log entry for a title (remembered for this Taste's minute: the rows ask for every title). */
        synchronized WatchLog.Entry watched(String title) {
            if (found.containsKey(title)) return found.get(title);
            WatchLog.Entry e = log.isEmpty() ? null : WatchLog.find(log, title);
            found.put(title, e);
            return e;
        }

        final List<String> liked = Prefs.genres(), languages = Prefs.contentLanguages(), services = Prefs.serviceOrder();

        /**
         * How well a title fits, explicit choices first: a preferred genre counts most (0.6 each), then the earlier
         * of your content languages and services, then what you watched and the apps you use (when history is on).
         */
        float score(Pick p) {
            float s = 0;
            for (int g : p.genres) {
                if (liked.contains(String.valueOf(g))) s += 0.6f;
                Float w = genres.get(g);
                if (w != null) s += w;
            }
            if (!languages.isEmpty() && !p.lang.isEmpty()) s -= 0.15f * Math.min(4, Lists.rank(languages, p.lang));
            if (!services.isEmpty() && p.app != null) s -= 0.08f * Math.min(4, Lists.rank(services, p.app));
            Float a = p.pkg == null ? null : apps.get(p.pkg);
            return s + (a == null ? 0 : a);
        }
    }

    private volatile Taste tasteNow;
    private volatile long tasteAt;
    private volatile int tasteVersion = -1;

    private Taste taste() {
        long now = System.currentTimeMillis();
        Taste t = tasteNow;
        if (t != null && now - tasteAt < 60_000L && now >= tasteAt && tasteVersion == Prefs.version) return t;
        tasteVersion = Prefs.version;
        // With viewing history off (Settings > Recommendations), nothing the apps reported counts, nor app usage.
        boolean history = Prefs.useHistory();
        t = new Taste(history ? WatchLog.read(context) : new ArrayList<>(), new HashSet<>(taste.getStringSet("hidden", new HashSet<>())));
        // Genres: the picks you watched (matched by title), weighted by the hours watched (at most 2 per title).
        float total = 0;
        for (Pick p : picks) {
            WatchLog.Entry e = t.watched(p.title);
            if (e == null || p.genres.length == 0) continue;
            float hours = Math.min(2, e.watchedMs / 3600_000f) + (e.done() ? 0.5f : 0);
            for (int g : p.genres) t.genres.put(g, (t.genres.containsKey(g) ? t.genres.get(g) : 0) + hours);
            total += hours;
        }
        if (total > 0) for (Map.Entry<Integer, Float> g : t.genres.entrySet()) g.setValue(g.getValue() / total);
        for (Map.Entry<String, ?> d : taste.getAll().entrySet()) {
            if (!d.getKey().startsWith("dislike:") || !(d.getValue() instanceof Float)) continue;
            try {
                int g = Integer.parseInt(d.getKey().substring(8));
                t.genres.put(g, (t.genres.containsKey(g) ? t.genres.get(g) : 0) - 0.15f * (Float) d.getValue());
            } catch (NumberFormatException ignored) {
            }
        }
        // Apps: the time each streaming app was in front over the last two weeks (appUsage, read in the background).
        if (history) t.apps.putAll(appUsage(now));
        tasteNow = t;
        tasteAt = now;
        return t;
    }

    private static final long USAGE_EVERY = 10 * 60_000L;
    private final ExecutorService usageIo = Executors.newSingleThreadExecutor();
    private volatile Map<String, Float> usageNow = new HashMap<>();
    private volatile long usageAt;
    private volatile boolean usagePending;

    /**
     * Each streaming app's share of the time in front over the last two weeks (with usage access, given by HomeMode
     * through ADB; without it, nothing changes the order). The last one read, at once: reading it again happens in the
     * background, at most every 10 minutes: taste() runs on the main thread, and two weeks of usage is a system call
     * that a busy TV can answer slowly.
     */
    private Map<String, Float> appUsage(long now) {
        if (!usagePending && (usageAt == 0 || now - usageAt >= USAGE_EVERY || now < usageAt)) {
            usagePending = true;
            usageIo.execute(() -> {
                Map<String, Float> out = new HashMap<>();
                try {
                    android.app.usage.UsageStatsManager usage = context.getSystemService(android.app.usage.UsageStatsManager.class);
                    long at = System.currentTimeMillis();
                    Map<String, android.app.usage.UsageStats> stats = usage == null ? null
                            : usage.queryAndAggregateUsageStats(at - 14 * 86_400_000L, at);
                    long most = 0;
                    if (stats != null) {
                        for (android.app.usage.UsageStats u : stats.values()) if (isService(u.getPackageName())) most = Math.max(most, u.getTotalTimeInForeground());
                        for (android.app.usage.UsageStats u : stats.values()) {
                            if (most > 0 && isService(u.getPackageName())) out.put(u.getPackageName(), 0.4f * u.getTotalTimeInForeground() / most);
                        }
                    }
                } catch (RuntimeException ignored) {
                }
                boolean changed = !out.equals(usageNow);
                usageNow = out;
                usageAt = System.currentTimeMillis();
                usagePending = false;
                if (changed) {
                    tasteAt = 0;   // the next taste() uses it
                    notifyListeners();
                }
            });
        }
        return usageNow;
    }

    // MARK: - Top Shelf: the focused title's full-screen photo

    /** The pick's full-screen photo if it's ready (or null: it's built from the saved image and listeners hear). */
    Bitmap full(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;
        synchronized (full) {
            Bitmap b = full.get(key);
            if (b != null) return b;
            if (!hasImage(p) || !fullPending.add(key)) return null;
        }
        final File f = imageFile(p);
        art.execute(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
                Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
                if (src == null) return;
                Backdrop[] glass = new Backdrop[1];
                Bitmap made = Backdrop.topShelf(src, glass);
                synchronized (full) {
                    full.put(key, made);
                    fullGlass.put(key, glass[0]);
                    fullGlass.keySet().retainAll(full.keySet());
                    // The latest three: the focused one and the ones just left (the cross-fade still draws them).
                    java.util.Iterator<String> it = full.keySet().iterator();
                    while (full.size() > 3 && it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                    fullGlass.keySet().retainAll(full.keySet());
                }
                notifyListeners();
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("top shelf image: " + e);
            } finally {
                synchronized (full) { fullPending.remove(key); }
            }
        });
        return null;
    }

    /** The glass (frosted and glass versions) of a ready full-screen photo, or null. */
    Backdrop glass(Pick p) {
        if (p == null || p.backdrop == null) return null;
        synchronized (full) {
            return fullGlass.get(p.backdrop);
        }
    }

    // MARK: - Voice search

    interface Found { void onFound(List<Pick> results, String error); }

    /**
     * Search across TMDB's catalog (it's TMDB's metadata, not the apps' own search): titles by name, or a person's
     * best-known titles (an actor or a director); "2023" in the query keeps only that year. Each result says where it
     * streams in your region, from TMDB's availability data; the ones on your apps (in your service order) come first,
     * then your languages. `found` runs on the main thread.
     */
    void search(String query, Found found) {
        lookup.execute(() -> {
            List<Pick> on = new ArrayList<>(), off = new ArrayList<>();
            String error = "";
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(19|20)\\d\\d\\b").matcher(query);
                String year = m.find() ? m.group() : "";
                String words = year.isEmpty() ? query : query.replace(year, " ").trim();
                JSONArray results = get("/search/multi?include_adult=false&language=" + lang() + "&region=" + region()
                        + "&query=" + URLEncoder.encode(words.isEmpty() ? query : words, "UTF-8")).getJSONArray("results");
                List<JSONObject> titles = new ArrayList<>();
                for (int i = 0; i < results.length(); i++) {
                    JSONObject o = results.getJSONObject(i);
                    if (o.optString("media_type").equals("person")) {
                        // An actor or a director: the titles TMDB lists them for.
                        JSONArray known = o.optJSONArray("known_for");
                        for (int j = 0; known != null && j < known.length(); j++) titles.add(known.getJSONObject(j));
                    } else {
                        titles.add(o);
                    }
                }
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (JSONObject o : titles) {
                    if (on.size() + off.size() >= 12) break;
                    String kind = o.optString("media_type");
                    String backdrop = o.optString("backdrop_path", "");
                    if ((!kind.equals("movie") && !kind.equals("tv")) || backdrop.isEmpty() || "null".equals(backdrop)) continue;
                    String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
                    if (!year.isEmpty() && !date.startsWith(year)) continue;
                    // Any language here: you asked for this title (your content languages only order the results).
                    Pick p = new Pick();
                    p.id = o.optInt("id");
                    p.kind = kind;
                    p.title = o.optString(kind.equals("movie") ? "title" : "name");
                    p.backdrop = backdrop;
                    if (!seen.add(key(p))) continue;
                    String language = o.optString("original_language");
                    try {
                        fetchDetails(p);
                    } catch (Exception e) {
                        continue;
                    }
                    p.meta = p.meta.isEmpty() ? p.year : p.year.isEmpty() ? p.meta : p.year + " · " + p.meta;
                    p.progress = Lists.rank(Prefs.contentLanguages(), language);   // the language order, for sorting below
                    (p.pkg != null ? on : off).add(p);
                }
                java.util.Comparator<Pick> order = (a, b) -> {
                    int byService = Integer.compare(Lists.rank(Prefs.serviceOrder(), a.app == null ? "" : a.app),
                            Lists.rank(Prefs.serviceOrder(), b.app == null ? "" : b.app));
                    return byService != 0 ? byService : Float.compare(a.progress, b.progress);
                };
                java.util.Collections.sort(on, order);
                java.util.Collections.sort(off, (a, b) -> Float.compare(a.progress, b.progress));
                for (Pick p : on) p.progress = -1;
                for (Pick p : off) p.progress = -1;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            on.addAll(off);
            final List<Pick> all = on;
            final String why = error;
            main.post(() -> found.onFound(all, why));
        });
    }

    interface Asked { void onAsked(List<Pick> results, String answer, String error); }

    /**
     * A question in plain words for Gemini ("a feel-good Tamil family movie"): its titles, each found on TMDB by its
     * name and year, with the details and availability of the normal search; what streams on the TV's apps first.
     */
    void askGemini(String question, Asked asked) {
        lookup.execute(() -> {
            List<Pick> on = new ArrayList<>(), off = new ArrayList<>();
            String error = "", answer = "";
            try {
                Gemini.Answer a = Gemini.ask(question, region(), Prefs.contentLanguages());
                answer = a.text;
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (String[] t : a.titles) {
                    Pick p = findTitle(t[0], t[1], t[2]);
                    if (p == null || !seen.add(key(p))) continue;
                    try {
                        fetchDetails(p);
                    } catch (Exception e) {
                        continue;
                    }
                    p.meta = p.meta.isEmpty() ? p.year : p.year.isEmpty() ? p.meta : p.year + " · " + p.meta;
                    (p.pkg != null ? on : off).add(p);
                }
                if (on.isEmpty() && off.isEmpty()) error = Ui.tr("Gemini's titles weren't found on TMDB");
            } catch (java.net.SocketTimeoutException e) {
                error = Ui.tr("Gemini took too long to answer. Try again.");
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            on.addAll(off);
            final String said = answer, why = error;
            main.post(() -> asked.onAsked(on, said, why));
        });
    }

    /** A title by its name, year and kind on TMDB (the year allows a year either side), or null. */
    private Pick findTitle(String title, String year, String kind) throws Exception {
        for (String k : new String[] {kind, kind.equals("tv") ? "movie" : "tv"}) {
            JSONArray results = get("/search/" + k + "?include_adult=false&language=" + lang()
                    + "&query=" + URLEncoder.encode(title, "UTF-8")).getJSONArray("results");
            JSONObject best = null;
            for (int i = 0; i < results.length() && best == null; i++) {
                JSONObject o = results.getJSONObject(i);
                String backdrop = o.optString("backdrop_path", "");
                if (backdrop.isEmpty() || "null".equals(backdrop)) continue;
                String date = o.optString(k.equals("movie") ? "release_date" : "first_air_date", "");
                if (!year.isEmpty() && date.length() >= 4) {
                    int y = Integer.parseInt(date.substring(0, 4)), want = Integer.parseInt(year);
                    if (Math.abs(y - want) > 1) continue;
                }
                best = o;
            }
            if (best == null) continue;
            Pick p = new Pick();
            p.id = best.optInt("id");
            p.kind = k;
            p.title = best.optString(k.equals("movie") ? "title" : "name");
            p.backdrop = best.optString("backdrop_path");
            return p;
        }
        return null;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        // Already on disk: decoded on `art`, never queued behind a fetch of the lists (a minute of network at times).
        (hasImage(p) ? art : io).execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /**
     * Settings > Recommendations > Refresh now: the lists from TMDB again, at once, without waiting for the next
     * scheduled update (titles already detailed keep their details, so it's quick).
     */
    void refreshNow() {
        listAt = 0;
        failedAt = 0;
        error = "";
        refreshIfStale();
    }

    /** Fetching from TMDB right now. */
    boolean refreshing() { return fetching; }

    /** When the lists were last fetched (0: never). */
    long updatedAt() { return listAt; }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) {
                    if (shelf) fetchSections(p); else fetchList(p);
                }
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light && !shelf) return;
        light = value;
        shelf = false;
        synchronized (images) {
            images.clear();
        }
    }

    /** Top Shelf design: thumbnails (Backdrop.row) for every title in the rows, full photos on demand (full()). */
    void setShelf() {
        if (shelf) return;
        shelf = true;
        light = false;
        synchronized (images) {
            images.clear();
        }
    }

    /** How many titles a Top Shelf row holds. */
    static final int ROW_MAX = 20;
    /** Row thumbnails kept ready (~200 KB of GPU memory each): the rows on screen and the next ones. */
    private static final int THUMBS = 80;

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (shelf) {
            // Every title in the rows: their images are downloaded ahead (scrolling never waits on the network).
            for (Section s : sections()) {
                for (Pick p : s.items) if ((p.kind.equals("movie") || p.kind.equals("tv")) && seen.add(key(p))) out.add(p);
            }
            return out;
        }
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + region()).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"), o.optInt("provider_id"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + region() + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "")
                + (original().isEmpty() ? "" : "&with_original_language=" + URLEncoder.encode(original(), "UTF-8"));
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready). With content_language, the
        //    most popular right now in those languages on the TV's services (popularity is TMDB's live measure).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get(original().isEmpty() ? "/trending/" + kind + "/week?language=" + lang()
                    : "/discover/" + kind + "?vote_count.gte=1" + common).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months. Titles in a language of their own get far fewer TMDB votes: a lower bar there.
        JSONArray movies = get("/discover/movie?vote_count.gte=" + (original().isEmpty() ? 30 : 3) + "&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=" + (original().isEmpty() ? 20 : 1) + "&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        if (!original().isEmpty() && !("|" + original() + "|").contains("|" + o.optString("original_language") + "|")) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en"
                + (p.lang.isEmpty() ? "" : "," + p.lang));
        p.title = title(p, o);
        p.logo = bestLogo(o.optJSONObject("images"), p.lang);
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        p.overview = o.optString("overview", "").trim();
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date", "");
        p.year = date.length() >= 4 ? date.substring(0, 4) : "";
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
            p.genres = new int[genres.length()];
            for (int i = 0; i < genres.length(); i++) p.genres[i] = genres.getJSONObject(i).optInt("id");
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
            p.web = streamingOriginal(o.optJSONArray("networks"));
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(region());
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"), flat.getJSONObject(i).optInt("provider_id"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        // Providers decides what TMDB's name means; among a service's apps, the installed one first.
        String[] first = null;
        for (Providers.Provider p : Providers.matching(name, providerId)) {
            for (String pkg : p.packages) {
                String[] s = {p.tmdbNames.length > 0 ? p.tmdbNames[0] : "", p.key, pkg};
                if (installed(pkg)) return s;
                if (first == null) first = s;
            }
        }
        return first;
    }

    /**
     * The service's app is on the TV, and the service isn't left out of the picks (Settings > Streaming Apps, or
     * config.properties' hide_services at first: an app the TV ships without a subscription, say).
     */
    private boolean installed(String pkg) {
        Providers.Provider p = Providers.byPackage(pkg);
        if (p != null && Prefs.hiddenServices().contains(p.key)) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        // A new key, region, language, genre set or app choice fetches the lists again.
        long stamp = ((Prefs.tmdbKey().hashCode() * 31L + region().hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0)) * 31L
                + original().hashCode();
        stamp = stamp * 31L + Prefs.genres().hashCode();
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Prefs.watchmodeKey();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + region()));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Prefs.watchmodeKey().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Prefs.watchmodeKey(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        return new JSONObject(getText(path));
    }

    /** A TMDB answer as text (some, like /configuration/languages, are arrays). */
    private String getText(String path) throws IOException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Prefs.tmdbKey();
        return key.isEmpty() ? null : key;
    }

    /**
     * The title's logo art for the hero: in the TV's language or English first (readable), then the language it was
     * made in, then one without text language; the highest rated; "" when TMDB has none (the hero writes the name).
     */
    private static String bestLogo(JSONObject images, String original) {
        JSONArray list = images == null ? null : images.optJSONArray("logos");
        String best = "";
        double bestScore = -1;
        String ui = Ui.language.replaceAll("-.*", "");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            if (l == null) continue;
            String path = l.optString("file_path", "");
            if (path.isEmpty() || path.endsWith(".svg")) continue;
            String lang = l.isNull("iso_639_1") ? "" : l.optString("iso_639_1");
            int rank = lang.equals(ui) ? 3 : lang.equals("en") ? 2 : lang.equals(original) ? 1 : lang.isEmpty() ? 0 : -1;
            if (rank < 0) continue;
            double score = rank * 100 + l.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    // MARK: - Logos and posters: small TMDB images, loaded when a row or the hero shows them

    private final Map<String, Bitmap> smallArt = new LinkedHashMap<>(16, 0.75f, true);   // the least recently drawn goes
    private final java.util.Set<String> smallPending = new java.util.HashSet<>();
    private static final int SMALL_ART = 70;

    /**
     * A logo (`crop` false: fitted inside w × h, transparency kept) or a poster (`crop` true: cut to w × h), ready to
     * draw, or null: it's fetched (network queue) or decoded (image thread) in the background and listeners hear.
     */
    Bitmap art(String path, int w, int h, boolean crop) {
        if (path == null || path.isEmpty()) return null;
        final String key = path + "@" + w + "x" + h;
        synchronized (smallArt) {
            Bitmap b = smallArt.get(key);
            if (b != null) return b;
            if (!smallPending.add(key)) return null;
        }
        final boolean local = path.startsWith("file:");   // an app's own artwork, saved by NowPlaying
        final File f = local ? new File(path.substring(5)) : new File(new File(context.getCacheDir(), "recs"), path.replace("/", ""));
        (f.exists() ? art : io).execute(() -> {
            try {
                if (!local) downloadTo("https://image.tmdb.org/t/p/" + (crop ? "w342" : "w500") + path, f);
                Bitmap b = decodeSmall(f, w, h, crop);
                if (b == null) return;
                synchronized (smallArt) {
                    smallArt.put(key, b);
                    while (smallArt.size() > SMALL_ART) smallArt.remove(smallArt.keySet().iterator().next());
                }
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("small image: " + e.getMessage());
            } finally {
                synchronized (smallArt) { smallPending.remove(key); }
            }
        });
        return null;
    }

    private static Bitmap decodeSmall(File f, int w, int h, boolean crop) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) return null;
        Bitmap out;
        if (crop) {
            float ratio = (float) w / h;
            int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio)), ch = Math.min(src.getHeight(), Math.round(cw / ratio));
            Bitmap cut = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
            out = Bitmap.createScaledBitmap(cut, w, h, true);
            if (cut != src && cut != out) cut.recycle();
        } else {
            float s = Math.min((float) w / src.getWidth(), (float) h / src.getHeight());
            out = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * s)), Math.max(1, Math.round(src.getHeight() * s)), true);
        }
        if (out != src) src.recycle();
        return Backdrop.gpu(out);
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        downloadTo(IMAGES + p.backdrop, imageFile(p));
    }

    private static void downloadTo(String url, File f) throws IOException {
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light, forShelf = shelf;
            Backdrop backdrop = forShelf ? Backdrop.row(b) : Backdrop.make(b, forLight);
            if (forLight != light || forShelf != shelf) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // Top Shelf: the three rows plus a voice search's results.
                while (images.size() > (forShelf ? THUMBS : forLight ? 10 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : shelf ? row(null, ROW_MAX) : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
        synchronized (full) {
            full.clear();
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        int keep = 320;   // the rows' photos, posters and logos, what you watched, a search's results
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - keep; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed).put("genres", new JSONArray(p.genres)).put("web", p.web)
                        .put("overview", p.overview).put("year", p.year).put("lang", p.lang)
                        .put("logo", p.logo).put("poster", p.poster));
            }
            JSONObject sections = new JSONObject();
            for (Map.Entry<String, List<String>> s : sectionKeys.entrySet()) sections.put(s.getKey(), new JSONArray(s.getValue()));
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("sections", sections.toString())
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if (k.startsWith("wm:") && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            JSONArray g = o.optJSONArray("genres");
            p.genres = new int[g == null ? 0 : g.length()];
            for (int j = 0; j < p.genres.length; j++) p.genres[j] = g.optInt(j);
            p.web = o.optBoolean("web");
            p.overview = o.optString("overview");
            p.year = o.optString("year");
            p.lang = o.optString("lang");
            p.logo = o.optString("logo");
            p.poster = o.optString("poster");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
