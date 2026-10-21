package dev.stillhome.tv;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;

/** System-managed screensaver. The clock shifts gently to protect static pixels. */
public final class StillDreamService extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AmbientScreen screen;
    private int minuteStep;
    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            if (screen == null) {
                return;
            }
            minuteStep++;
            float x = TvUi.dp(StillDreamService.this, (minuteStep % 5 - 2) * 12);
            float y = TvUi.dp(StillDreamService.this, (minuteStep % 3 - 1) * 10);
            screen.clockGroup.setTranslationX(x);
            screen.clockGroup.setTranslationY(y);
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setFullscreen(true);
        setInteractive(false);
        setScreenBright(false);
        screen = new AmbientScreen(this, false);
        setContentView(screen.root);
        handler.postDelayed(drift, 60_000);
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(drift);
        screen = null;
        super.onDetachedFromWindow();
    }
}
