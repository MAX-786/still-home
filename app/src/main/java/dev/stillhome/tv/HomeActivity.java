package dev.stillhome.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The remote-first HOME screen: one fast shelf and an optional full app grid. */
public final class HomeActivity extends Activity {
    static final String EXTRA_IMPORTED_WALLPAPER = "dev.stillhome.tv.extra.IMPORTED_WALLPAPER";
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();
    private Palette palette;
    private BackdropLayout backdrop;
    private LinearLayout homePage;
    private LinearLayout allAppsPage;
    private LinearLayout favoriteRow;
    private GridLayout appGrid;
    private TextView appCount;
    private TextView allAppsButton;
    private List<AppEntry> apps = Collections.emptyList();
    private boolean showingAllApps;
    private boolean hasLoadedApps;
    private boolean allAppsDirty = true;
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.fullScreen(this);
        palette = LauncherPrefs.palette(this);
        buildScreen();
        applyImportedWallpaper(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (palette != LauncherPrefs.palette(this)) {
            recreate();
            return;
        }
        backdrop.refreshWallpaper();
        refreshApps();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showHome();
        applyImportedWallpaper(intent);
    }

    @Override
    public void onBackPressed() {
        if (showingAllApps) {
            showHome();
        }
        // Back on HOME itself should leave the launcher in place.
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        appLoader.shutdownNow();
        super.onDestroy();
    }

    private void buildScreen() {
        backdrop = new BackdropLayout(this, palette, false);
        setContentView(backdrop);

        homePage = TvUi.column(this);
        FrameLayout.LayoutParams pageParams = fullPage();
        backdrop.addView(homePage, pageParams);

        LinearLayout top = TvUi.row(this);
        homePage.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(this, 48)));
        TextView brand = TvUi.text(this, "Still Home", 18, TvUi.WHITE, true);
        top.addView(brand);
        top.addView(TvUi.space(this), new LinearLayout.LayoutParams(0, 1, 1));

        allAppsButton = TvUi.action(this, palette, "All apps");
        allAppsButton.setOnClickListener(view -> showAllApps());
        top.addView(allAppsButton);
        TextView settings = TvUi.action(this, palette, "Settings");
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        settingsParams.leftMargin = TvUi.dp(this, 10);
        top.addView(settings, settingsParams);
        settings.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));

        TvUi.addVerticalSpace(homePage, this, 56);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(72);
        time.setTextColor(TvUi.WHITE);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        homePage.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(18);
        date.setTextColor(TvUi.WHITE);
        date.setIncludeFontPadding(false);
        homePage.addView(date);

        homePage.addView(TvUi.space(this), new LinearLayout.LayoutParams(1, 0, 1));
        buildShelf();
    }

    private void buildShelf() {
        LinearLayout shelf = TvUi.column(this);
        shelf.setPadding(TvUi.dp(this, 18), TvUi.dp(this, 16),
                TvUi.dp(this, 18), TvUi.dp(this, 14));
        shelf.setBackground(TvUi.rounded(this, palette.panel(), 0x60FFFFFF, 20));
        homePage.addView(shelf, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout heading = TvUi.row(this);
        shelf.addView(heading);
        heading.addView(TvUi.text(this, "Favorites", 18, TvUi.WHITE, true));
        heading.addView(TvUi.space(this), new LinearLayout.LayoutParams(0, 1, 1));
        heading.addView(TvUi.text(this, "Hold OK to unpin", 13, TvUi.MUTED, false));
        TvUi.addVerticalSpace(shelf, this, 12);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        shelf.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(this, 124)));
        favoriteRow = TvUi.row(this);
        scroll.addView(favoriteRow);
        renderFavorites();
    }

    private void refreshApps() {
        int generation = ++loadGeneration;
        appLoader.execute(() -> {
            List<AppEntry> loaded = AppCatalog.load(getApplicationContext());
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) {
                    return;
                }
                if (!hasLoadedApps || loaded != apps) {
                    hasLoadedApps = true;
                    apps = loaded;
                    allAppsDirty = true;
                    renderFavorites();
                    if (showingAllApps) {
                        renderAllApps();
                    }
                }
            });
        });
    }

    private void renderFavorites() {
        if (favoriteRow == null) {
            return;
        }
        favoriteRow.removeAllViews();
        if (apps.isEmpty()) {
            favoriteRow.addView(TvUi.text(this,
                    hasLoadedApps ? "No launchable apps found" : "Loading your apps…",
                    16, TvUi.MUTED, false));
            return;
        }
        Map<String, AppEntry> byPackage = new HashMap<>();
        for (AppEntry app : apps) {
            byPackage.put(app.packageName, app);
        }
        List<String> favorites = LauncherPrefs.favoritePackages(this, apps);
        if (favorites.isEmpty()) {
            favoriteRow.addView(TvUi.text(this,
                    "Open All apps and hold OK to pin favorites.", 16, TvUi.MUTED, false));
            if (!showingAllApps) {
                allAppsButton.requestFocus();
            }
            return;
        }

        boolean first = true;
        for (String packageName : favorites) {
            AppEntry app = byPackage.get(packageName);
            if (app == null) {
                continue;
            }
            View tile = appTile(app, true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    TvUi.dp(this, 112), TvUi.dp(this, 116));
            params.rightMargin = TvUi.dp(this, 9);
            favoriteRow.addView(tile, params);
            if (first && !showingAllApps) {
                first = false;
                tile.requestFocus();
            }
        }
    }

    private View appTile(AppEntry app, boolean favoriteShelf) {
        LinearLayout tile = TvUi.column(this);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(TvUi.dp(this, 7), TvUi.dp(this, 8),
                TvUi.dp(this, 7), TvUi.dp(this, 8));
        TvUi.focusSurface(tile, palette, 0x25FFFFFF, 15);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.iconFor(getResources()));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new LinearLayout.LayoutParams(TvUi.dp(this, 50), TvUi.dp(this, 50)));
        TvUi.addVerticalSpace(tile, this, 10);
        boolean pinned = LauncherPrefs.isFavorite(this, apps, app.packageName);
        TextView label = TvUi.text(this, app.label, 14,
                !favoriteShelf && pinned ? palette.accent : TvUi.WHITE, true);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        tile.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(this, 22)));

        tile.setContentDescription(app.label + (pinned ? ", favorite" : ""));
        tile.setTag(app.id());
        tile.setOnClickListener(view -> app.launch(this));
        TvUi.remoteLongClick(tile, view -> {
            boolean nowPinned = LauncherPrefs.toggleFavorite(this, apps, app.packageName);
            allAppsDirty = true;
            Toast.makeText(this, app.label + (nowPinned ? " pinned" : " unpinned"),
                    Toast.LENGTH_SHORT).show();
            label.setTextColor(nowPinned ? palette.accent : TvUi.WHITE);
            tile.setContentDescription(app.label + (nowPinned ? ", favorite" : ""));
            renderFavorites();
            return true;
        });
        return tile;
    }

    private void showAllApps() {
        showingAllApps = true;
        homePage.setVisibility(View.GONE);
        if (allAppsPage == null) {
            buildAllAppsPage();
        }
        allAppsPage.setVisibility(View.VISIBLE);
        if (allAppsDirty) {
            renderAllApps();
        }
        focusFirstApp();
    }

    private void showHome() {
        showingAllApps = false;
        if (allAppsPage != null) {
            allAppsPage.setVisibility(View.GONE);
        }
        homePage.setVisibility(View.VISIBLE);
        if (favoriteRow.getChildCount() > 0 && favoriteRow.getChildAt(0).isFocusable()) {
            favoriteRow.getChildAt(0).requestFocus();
        } else {
            allAppsButton.requestFocus();
        }
    }

    private void buildAllAppsPage() {
        allAppsPage = TvUi.column(this);
        backdrop.addView(allAppsPage, fullPage());
        LinearLayout top = TvUi.row(this);
        allAppsPage.addView(top);
        TextView back = TvUi.action(this, palette, "Back");
        back.setOnClickListener(view -> showHome());
        top.addView(back);
        TextView title = TvUi.text(this, "All apps", 28, TvUi.WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.leftMargin = TvUi.dp(this, 20);
        top.addView(title, titleParams);
        top.addView(TvUi.space(this), new LinearLayout.LayoutParams(0, 1, 1));
        appCount = TvUi.text(this, "", 14, TvUi.MUTED, false);
        top.addView(appCount);
        TvUi.addVerticalSpace(allAppsPage, this, 18);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        TvUi.clipToBounds(scroll);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        allAppsPage.addView(scroll, scrollParams);
        appGrid = new GridLayout(this);
        appGrid.setClipChildren(false);
        appGrid.setClipToPadding(false);
        appGrid.setPadding(0, TvUi.dp(this, 6), 0, TvUi.dp(this, 8));
        scroll.addView(appGrid);
        TvUi.addVerticalSpace(allAppsPage, this, 12);
        allAppsPage.addView(TvUi.text(this, "Hold OK on an app to pin or unpin it.",
                13, TvUi.MUTED, false));
    }

    private void renderAllApps() {
        if (appGrid == null) {
            return;
        }
        View focusedTile = appGrid.findFocus();
        Object focusTag = focusedTile == null ? null : focusedTile.getTag();
        String focusedId = focusTag instanceof String ? (String) focusTag : null;
        View currentFocus = getCurrentFocus();
        boolean hadFocusableTile = appGrid.getChildCount() > 0
                && appGrid.getChildAt(0).isFocusable();
        boolean restoreFocus = showingAllApps && (focusedTile != null
                || !hadFocusableTile || currentFocus == null || !currentFocus.isShown());
        allAppsDirty = false;
        appCount.setText(apps.size() + " installed apps");
        appGrid.removeAllViews();
        if (apps.isEmpty()) {
            appGrid.addView(TvUi.text(this, "No launchable apps found.", 18, TvUi.WHITE, false));
            return;
        }

        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int columns = Math.max(4, Math.min(8, (int) ((widthDp - 72) / 130)));
        int tileWidth = Math.max(100, (int) ((widthDp - 72) / columns) - 12);
        appGrid.setColumnCount(columns);
        for (int index = 0; index < apps.size(); index++) {
            View tile = appTile(apps.get(index), false);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = TvUi.dp(this, tileWidth);
            params.height = TvUi.dp(this, 116);
            params.setMargins(0, 0, TvUi.dp(this, 12), TvUi.dp(this, 12));
            appGrid.addView(tile, params);
        }
        if (restoreFocus) {
            appGrid.post(() -> {
                if (!showingAllApps) {
                    return;
                }
                for (int index = 0; index < appGrid.getChildCount(); index++) {
                    View tile = appGrid.getChildAt(index);
                    if (focusedId != null && focusedId.equals(tile.getTag())) {
                        tile.requestFocusFromTouch();
                        return;
                    }
                }
                if (appGrid.getChildCount() > 0) {
                    appGrid.getChildAt(0).requestFocusFromTouch();
                }
            });
        }
    }

    private void focusFirstApp() {
        appGrid.post(() -> {
            if (showingAllApps && appGrid.getChildCount() > 0) {
                appGrid.getChildAt(0).requestFocusFromTouch();
            }
        });
    }

    private FrameLayout.LayoutParams fullPage() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        params.setMargins(TvUi.dp(this, 34), TvUi.dp(this, 28),
                TvUi.dp(this, 34), TvUi.dp(this, 28));
        return params;
    }

    private void applyImportedWallpaper(Intent intent) {
        if (intent == null) {
            return;
        }
        String filename = intent.getStringExtra(EXTRA_IMPORTED_WALLPAPER);
        intent.removeExtra(EXTRA_IMPORTED_WALLPAPER);
        if (filename == null || filename.contains("..")
                || !filename.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,79}")) {
            return;
        }
        File file = new File(new File(getFilesDir(), "wallpapers"), filename);
        Uri uri = Uri.fromFile(file);
        if (!file.isFile() || !WallpaperStore.canRead(this, uri)) {
            Toast.makeText(this, "Imported wallpaper is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        LauncherPrefs.setWallpaperUri(this, uri.toString());
        backdrop.refreshWallpaper();
        Toast.makeText(this, "Wallpaper applied", Toast.LENGTH_SHORT).show();
    }
}
