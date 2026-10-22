package dev.stillhome.tv;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;

/** The time and date as text, in the owner's format (Launcher settings: auto, 12 h or 24 h). */
final class Clock {
    private Clock() {}

    static boolean is24(Context c) {
        String v = Prefs.clock();
        if (v.equals("24")) return true;
        if (v.equals("12")) return false;
        return android.text.format.DateFormat.is24HourFormat(c);
    }

    /** "6:39 PM" or "18:39". */
    static String time(Context c, long now) {
        return new SimpleDateFormat(is24(c) ? "H:mm" : "h:mm a", Ui.locale()).format(new Date(now));
    }

    /** Just the digits, for the screensaver's big clock. */
    static String digits(Context c, long now) {
        return new SimpleDateFormat(is24(c) ? "H:mm" : "h:mm", Ui.locale()).format(new Date(now));
    }

    /** "Wednesday, 21 October" (Still Home's wording). */
    static String date(long now) {
        return new SimpleDateFormat("EEEE, d MMMM", Ui.locale()).format(new Date(now));
    }
}
