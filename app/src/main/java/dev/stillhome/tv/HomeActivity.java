package dev.stillhome.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;

import java.io.File;

/** The HOME screen: one Canvas-drawn view (HomeView), the Favorites shelf focused when the Home key brings it back. */
public final class HomeActivity extends Activity {
    static final String EXTRA_IMPORTED_WALLPAPER = "dev.stillhome.tv.extra.IMPORTED_WALLPAPER";
    private HomeView home;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.fullScreen(this);
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        applyImportedWallpaper(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        home.resume();
    }

    @Override
    protected void onPause() {
        home.pause();
        super.onPause();
    }

    /** Home pressed while the home screen is showing: back to the entry state. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        home.reset(true);
        applyImportedWallpaper(intent);
    }

    @Override
    protected void onDestroy() {
        home.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // Back on HOME itself leaves the launcher in place (HomeView handles its own Back).
    }

    /** A photo just sent from the computer (scripts/send_wallpaper.py): it becomes the wallpaper. */
    private void applyImportedWallpaper(Intent intent) {
        if (intent == null) return;
        String filename = intent.getStringExtra(EXTRA_IMPORTED_WALLPAPER);
        intent.removeExtra(EXTRA_IMPORTED_WALLPAPER);
        if (filename == null || filename.contains("..") || !filename.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,79}")) return;
        File file = new File(new File(getFilesDir(), "wallpapers"), filename);
        Uri uri = Uri.fromFile(file);
        if (!file.isFile() || !WallpaperStore.canRead(this, uri)) {
            Toast.makeText(this, "Imported wallpaper is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setWallpaper(uri.toString());
        WallpaperStore.clearMemoryCache();
        home.onWallpaper();
        Toast.makeText(this, "Wallpaper applied", Toast.LENGTH_SHORT).show();
    }
}
