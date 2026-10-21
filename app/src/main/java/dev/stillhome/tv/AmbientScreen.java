package dev.stillhome.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/** The same quiet clock is used for preview and Android's DreamService. */
final class AmbientScreen {
    final FrameLayout root;
    final LinearLayout clockGroup;

    AmbientScreen(Context context, boolean preview) {
        Palette palette = LauncherPrefs.palette(context);
        root = new BackdropLayout(context, palette, true);
        clockGroup = TvUi.column(context);
        clockGroup.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(clockGroup, clockParams);

        TextClock time = new TextClock(context);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(82);
        time.setTextColor(TvUi.WHITE);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setGravity(Gravity.CENTER);
        clockGroup.addView(time);

        TextClock date = new TextClock(context);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(20);
        date.setTextColor(TvUi.MUTED);
        date.setGravity(Gravity.CENTER);
        date.setIncludeFontPadding(false);
        clockGroup.addView(date);

        if (preview) {
            TextView hint = TvUi.text(context, "Press any button to return", 14,
                    TvUi.MUTED, false);
            hint.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1,
                    TvUi.dp(context, 32), Gravity.BOTTOM);
            hintParams.bottomMargin = TvUi.dp(context, 30);
            root.addView(hint, hintParams);
        }
    }
}
