package dev.stillhome.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** Lets the user inspect the screensaver before choosing it in TV settings. */
public final class AmbientPreviewActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.fullScreen(this);
        setContentView(new AmbientScreen(this, true).root);
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
