package dev.stillhome.tv;

import android.util.Log;

/** The app's log (adb logcat -s StillHome). */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("StillHome", message);
    }
}
