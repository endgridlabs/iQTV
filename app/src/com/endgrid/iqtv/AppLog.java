package com.endgrid.iqtv;

import android.util.Log;

/** The app's log (adb logcat -s iQTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("iQTV", message);
    }
}
