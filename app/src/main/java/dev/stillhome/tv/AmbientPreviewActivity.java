package dev.stillhome.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** Lets the owner inspect the screensaver before choosing it with the setup script. */
public final class AmbientPreviewActivity extends Activity {
    private AmbientView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.fullScreen(this);
        Ui.init(this);
        view = new AmbientView(this, true);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override
    protected void onPause() {
        view.stop();
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
