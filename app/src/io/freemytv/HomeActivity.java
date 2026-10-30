package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The EndGrid iQTV+ home screen (Android's "Home" role in EndGrid iQTV+ mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("EndGrid iQTV+'s home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (fromRemote(getIntent())) home.post(home::searchFromRemote);
    }

    /** The remote's Assistant key (RemoteKeys): the Search screen, listening. */
    static final String ACTION_SEARCH = "io.freemytv.action.SEARCH";

    /** Our search key, or the system's Assistant key with EndGrid iQTV+ as the TV's assistant. */
    private static boolean fromRemote(Intent intent) {
        return ACTION_SEARCH.equals(intent.getAction()) || Intent.ACTION_ASSIST.equals(intent.getAction());
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (fromRemote(intent)) home.searchFromRemote(); else home.reset(true);
    }

    /** Voice search: what the speech recognizer heard goes to the home screen's row. */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != HomeView.VOICE_REQUEST || resultCode != RESULT_OK || data == null || home == null) return;
        java.util.ArrayList<String> heard = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (heard != null && !heard.isEmpty()) home.voiceResult(heard.get(0));
    }

    /** The microphone for the Search screen's Speak: allowed or not, the home screen carries on. */
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != HomeView.MIC_REQUEST || home == null) return;
        home.micAllowed(results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Hidden behind an app (YouTube, Netflix…): the full-screen photos go at once, not only when Android is about
        // to stop the home screen. On a 2 GB TV the app in front needs the memory, and the photo on screen stays.
        if (level >= TRIM_MEMORY_UI_HIDDEN) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        // The remote's menu button (☰ on OnePlus remotes, KEYCODE_MENU): the TV's settings, as the stock home screen did.
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (android.content.ActivityNotFoundException e) {
                    AppLog.w("no settings app for the menu button");
                }
            }
            return true;
        }
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
