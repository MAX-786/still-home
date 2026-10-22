package dev.stillhome.tv;

import android.service.dreams.DreamService;

/** System-managed screensaver: the glass clock over moving photos (or the aurora). Android decides when it runs. */
public final class StillDreamService extends DreamService {
    private AmbientView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Ui.init(this);
        setFullscreen(true);
        setInteractive(false);
        setScreenBright(true);
        view = new AmbientView(this, false);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override
    public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (view != null) view.stop();
        view = null;
        super.onDetachedFromWindow();
    }
}
