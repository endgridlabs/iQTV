package io.freemytv;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows what the streaming apps play, through their media sessions (Android hands those to a notification listener,
 * allowed through ADB by HomeMode), and writes it to the WatchLog: the picks drop what you finished and lean toward
 * what you watch. Notifications themselves are never read. Only the apps the picks know (Recs.isService) are followed.
 * Apps that publish no title (or no session) are simply not followed.
 */
public class NowPlaying extends NotificationListenerService implements MediaSessionManager.OnActiveSessionsChangedListener {
    private static final long SAMPLE_MS = 30_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> followed = new HashMap<>();
    private final Map<String, Long> sampledAt = new HashMap<>();   // per package: elapsed time of the last sample
    private MediaSessionManager sessions;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean playing = false;
            for (MediaController c : followed.keySet()) playing |= sample(c);
            if (playing) handler.postDelayed(this, SAMPLE_MS);
        }
    };

    @Override public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        ComponentName me = new ComponentName(this, NowPlaying.class);
        try {
            sessions.addOnActiveSessionsChangedListener(this, me, handler);
            onActiveSessionsChanged(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            AppLog.w("now playing: " + e.getMessage());
        }
    }

    @Override public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(this);
        for (MediaController c : followed.keySet()) {
            sample(c);
            c.unregisterCallback(followed.get(c));
        }
        followed.clear();
        handler.removeCallbacks(tick);
    }

    @Override public void onActiveSessionsChanged(List<MediaController> controllers) {
        // Sessions that went away: one last sample (the position where it stopped), then let go.
        for (MediaController c : new java.util.ArrayList<>(followed.keySet())) {
            boolean still = false;
            for (MediaController n : controllers == null ? java.util.Collections.<MediaController>emptyList() : controllers) {
                still |= n.getSessionToken().equals(c.getSessionToken());
            }
            if (!still) {
                sample(c);
                c.unregisterCallback(followed.remove(c));
            }
        }
        if (controllers == null) return;
        for (MediaController c : controllers) {
            if (!Recs.isService(c.getPackageName())) continue;
            boolean known = false;
            for (MediaController f : followed.keySet()) known |= f.getSessionToken().equals(c.getSessionToken());
            if (known) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata metadata) { sample(c); }
                @Override public void onPlaybackStateChanged(PlaybackState state) {
                    if (sample(c)) {
                        handler.removeCallbacks(tick);
                        handler.postDelayed(tick, SAMPLE_MS);
                    }
                }
            };
            c.registerCallback(cb, handler);
            followed.put(c, cb);
            sample(c);
        }
    }

    /** Writes where the session is; true while it plays. */
    private boolean sample(MediaController c) {
        MediaMetadata md = c.getMetadata();
        PlaybackState st = c.getPlaybackState();
        String pkg = c.getPackageName();
        long now = SystemClock.elapsedRealtime();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        Long last = sampledAt.put(pkg, now);
        if (md == null) return playing;
        String title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        // Series: some apps put the show's name in one of these (the title is then the episode's). Not the artist: it's
        // music's field, and video apps fill it with other things (ZEE5: the cast, "A • B • C").
        String show = text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM);
        long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = 0;
        if (st != null && st.getPosition() > 0) {
            position = st.getPosition();
            if (playing && st.getLastPositionUpdateTime() > 0) {
                position += (long) ((now - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
        }
        long watched = playing && last != null ? Math.min(now - last, 2 * SAMPLE_MS) : 0;
        WatchLog.update(this, pkg, title, show, duration, position, watched);
        saveArt(pkg, title, md);
        return playing;
    }

    // MARK: The app's own artwork (Continue Watching's picture when TMDB doesn't know an episode's name)

    private static final int ART_W = 640, ART_KEPT = 200;
    private final java.util.concurrent.ExecutorService saver = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.Set<String> saving = new java.util.HashSet<>(), logged = new java.util.HashSet<>();

    /** Saves the artwork the app publishes with this title (WatchLog.artFile), once, in the background. */
    private void saveArt(String pkg, String title, MediaMetadata md) {
        if (title.isEmpty()) return;
        String key = pkg + "|" + title;
        File f = WatchLog.artFile(this, pkg, title), dir = f.getParentFile();
        if (f.exists()) return;
        Bitmap bitmap = null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            if (bitmap == null) bitmap = md.getBitmap(k);
        }
        String uri = null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String s = md.getString(k);
            if (uri == null && s != null && !s.isEmpty()) uri = s;
        }
        if (logged.add(key)) {
            // Once per title: what the app published, so a missing picture can be told apart from a bug.
            AppLog.w("now playing art: " + pkg + " " + (bitmap != null ? "bitmap " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    : uri != null ? "uri " + uri.replaceAll("^(\\w+://[^/]+).*", "$1/…") : "none") + ", keys " + md.keySet());
        }
        if (bitmap == null && uri == null || !saving.add(key)) return;
        final Bitmap b = bitmap;
        final String u = uri;
        saver.execute(() -> {
            try {
                Bitmap src = b != null ? b : load(u);
                if (src == null) return;
                Bitmap out = src.getWidth() > ART_W
                        ? Bitmap.createScaledBitmap(src, ART_W, Math.max(1, src.getHeight() * ART_W / src.getWidth()), true) : src;
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File part = new File(f.getPath() + ".part");
                try (FileOutputStream o = new FileOutputStream(part)) {
                    out.compress(Bitmap.CompressFormat.JPEG, 85, o);
                }
                if (!part.renameTo(f)) return;
                prune(dir);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("now playing art: " + e.getMessage());
            } finally {
                handler.post(() -> saving.remove(key));
            }
        });
    }

    /** A picture from a link the app gave (web, or its own content provider), about ART_W wide. */
    private Bitmap load(String uri) throws Exception {
        java.io.InputStream in;
        java.net.HttpURLConnection http = null;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            http = (java.net.HttpURLConnection) new java.net.URL(uri).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(15000);
            in = http.getInputStream();
        } else {
            in = getContentResolver().openInputStream(Uri.parse(uri));
        }
        try (java.io.InputStream s = in) {
            byte[] data = readAll(s);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= ART_W) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 8 << 20) throw new java.io.IOException("artwork too big");
        }
        return out.toByteArray();
    }

    /** The newest ART_KEPT pictures stay (the watch log keeps 200 titles). */
    private static void prune(File dir) {
        File[] all = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (all == null || all.length <= ART_KEPT) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        //noinspection ResultOfMethodCallIgnored
        for (int i = ART_KEPT; i < all.length; i++) all[i].delete();
    }

    private static String text(MediaMetadata md, String key, String fallback) {
        CharSequence s = md.getText(key);
        if ((s == null || s.length() == 0) && fallback != null) s = md.getText(fallback);
        return s == null ? "" : s.toString().trim();
    }
}
