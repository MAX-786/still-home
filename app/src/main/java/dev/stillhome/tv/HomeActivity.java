package dev.stillhome.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
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
    private boolean hasRenderedFavorites;
    private boolean pendingHomeFocus;
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
        pendingHomeFocus = true;
        showHome();
        applyImportedWallpaper(intent);
        homePage.post(this::restorePendingHomeFocus);
        // Android may restore the prior toolbar focus after HOME has resumed.
        homePage.postDelayed(() -> {
            if (!showingAllApps && hasWindowFocus()) {
                focusHomeStart();
                pendingHomeFocus = false;
            }
        }, 150);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingHomeFocus) {
            homePage.post(this::restorePendingHomeFocus);
        }
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
        pageParams.topMargin = TvUi.dp(this, 26);
        pageParams.rightMargin = TvUi.dp(this, 26);
        pageParams.bottomMargin = TvUi.dp(this, 47);
        backdrop.addView(homePage, pageParams);

        LinearLayout top = TvUi.row(this);
        homePage.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(this, 40)));
        TextView brand = TvUi.text(this, "Still Home", 18, TvUi.WHITE, true);
        top.addView(brand);
        top.addView(TvUi.space(this), new LinearLayout.LayoutParams(0, 1, 1));

        allAppsButton = TvUi.iconAction(this, palette, "Apps", TvUi.ActionIcon.GRID);
        allAppsButton.setOnClickListener(view -> showAllApps());
        top.addView(allAppsButton, new LinearLayout.LayoutParams(
                TvUi.dp(this, 83), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvSettings = TvUi.iconAction(this, palette,
                "TV settings", TvUi.ActionIcon.DISPLAY);
        LinearLayout.LayoutParams tvSettingsParams = new LinearLayout.LayoutParams(
                TvUi.dp(this, 113), LinearLayout.LayoutParams.WRAP_CONTENT);
        tvSettingsParams.leftMargin = TvUi.dp(this, 13);
        top.addView(tvSettings, tvSettingsParams);
        tvSettings.setOnClickListener(view -> openTvSettings());

        TextView launcherSettings = TvUi.iconAction(this, palette,
                "Launcher settings", TvUi.ActionIcon.SLIDERS);
        LinearLayout.LayoutParams launcherSettingsParams = new LinearLayout.LayoutParams(
                TvUi.dp(this, 145), LinearLayout.LayoutParams.WRAP_CONTENT);
        launcherSettingsParams.leftMargin = TvUi.dp(this, 13);
        top.addView(launcherSettings, launcherSettingsParams);
        launcherSettings.setOnClickListener(
                view -> startActivity(new Intent(this, SettingsActivity.class)));

        TvUi.addVerticalSpace(homePage, this, 40);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(72);
        time.setTextColor(TvUi.WHITE);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clockParams.leftMargin = TvUi.dp(this, 2);
        homePage.addView(time, clockParams);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(18);
        date.setTextColor(0xFFC6CBD3);
        date.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dateParams.leftMargin = TvUi.dp(this, 2);
        homePage.addView(date, dateParams);

        homePage.addView(TvUi.space(this), new LinearLayout.LayoutParams(1, 0, 1));
        buildShelf();
    }

    private void buildShelf() {
        LinearLayout shelf = TvUi.column(this);
        shelf.setPadding(TvUi.dp(this, 24), TvUi.dp(this, 13),
                TvUi.dp(this, 24), TvUi.dp(this, 15));
        shelf.setBackground(new GlassSurfaceDrawable(this, shelf, palette, false));
        LinearLayout.LayoutParams shelfParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shelfParams.leftMargin = TvUi.dp(this, 3);
        shelfParams.rightMargin = TvUi.dp(this, 11);
        homePage.addView(shelf, shelfParams);

        LinearLayout heading = TvUi.row(this);
        heading.setGravity(Gravity.TOP);
        shelf.addView(heading);
        LinearLayout headingLabel = TvUi.column(this);
        headingLabel.addView(TvUi.text(this, "Favorites", 18, TvUi.WHITE, true),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        View headingRule = new View(this);
        headingRule.setBackgroundColor(0xFFA8B0FB);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                TvUi.dp(this, 26), TvUi.dp(this, 2));
        ruleParams.topMargin = TvUi.dp(this, 7);
        headingLabel.addView(headingRule, ruleParams);
        heading.addView(headingLabel, new LinearLayout.LayoutParams(
                TvUi.dp(this, 112), LinearLayout.LayoutParams.WRAP_CONTENT));
        heading.addView(TvUi.space(this), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = TvUi.dp(this, 3);
        heading.addView(TvUi.text(this, "Hold OK to unpin", 11, 0xFFB7BDC7, false),
                hintParams);
        TvUi.addVerticalSpace(shelf, this, 10);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(TvUi.dp(this, 4), 0, TvUi.dp(this, 4), 0);
        shelf.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(this, 112)));
        favoriteRow = TvUi.row(this);
        favoriteRow.setMinimumHeight(TvUi.dp(this, 112));
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
        View oldFocused = favoriteRow.findFocus();
        String focusedId = oldFocused != null && oldFocused.getTag() instanceof String
                ? (String) oldFocused.getTag() : null;
        boolean focusShelf = !showingAllApps && (oldFocused != null || !hasRenderedFavorites);
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
            if (!showingAllApps && (oldFocused != null || getCurrentFocus() == null)) {
                allAppsButton.requestFocus();
            }
            return;
        }

        View firstTile = null;
        View restoredTile = null;
        for (String packageName : favorites) {
            AppEntry app = byPackage.get(packageName);
            if (app == null) {
                continue;
            }
            View tile = appTile(app, true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    TvUi.dp(this, 122), TvUi.dp(this, 96));
            params.rightMargin = TvUi.dp(this, 18);
            favoriteRow.addView(tile, params);
            if (firstTile == null) {
                firstTile = tile;
            }
            if (focusedId != null && focusedId.equals(tile.getTag())) {
                restoredTile = tile;
            }
        }
        if (firstTile != null) {
            hasRenderedFavorites = true;
        }
        if (focusShelf && firstTile != null) {
            (restoredTile != null ? restoredTile : firstTile).requestFocus();
        }
    }

    private View appTile(AppEntry app, boolean favoriteShelf) {
        FrameLayout tile = new FrameLayout(this);
        tile.setClipChildren(false);
        LinearLayout content = TvUi.column(this);
        content.setGravity(Gravity.CENTER);
        content.setPadding(TvUi.dp(this, 7), TvUi.dp(this, 8),
                TvUi.dp(this, 7), TvUi.dp(this, 8));
        tile.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (favoriteShelf) {
            View focusRule = new View(this);
            focusRule.setBackgroundColor(0xFFA8B0FB);
            focusRule.setAlpha(0f);
            FrameLayout.LayoutParams ruleParams = new FrameLayout.LayoutParams(
                    TvUi.dp(this, 20), TvUi.dp(this, 2),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            ruleParams.bottomMargin = TvUi.dp(this, 8);
            tile.addView(focusRule, ruleParams);
            TvUi.favoriteCard(tile, focusRule);
        } else {
            TvUi.appGridCard(tile);
        }

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(AppBadgeDrawable.forApp(app.packageName,
                app.iconFor(getResources())));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(icon, new LinearLayout.LayoutParams(
                TvUi.dp(this, 45), TvUi.dp(this, 45)));
        TvUi.addVerticalSpace(content, this, 12);
        boolean pinned = LauncherPrefs.isFavorite(this, apps, app.packageName);
        TextView label = TvUi.text(this, app.label, favoriteShelf ? 13 : 14,
                !favoriteShelf && pinned ? palette.accent : TvUi.WHITE, true);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        content.addView(label, new LinearLayout.LayoutParams(
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

    private void openTvSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, "TV settings are unavailable", Toast.LENGTH_LONG).show();
        }
    }

    private void showHome() {
        showingAllApps = false;
        if (allAppsPage != null) {
            allAppsPage.setVisibility(View.GONE);
        }
        homePage.setVisibility(View.VISIBLE);
        focusHomeStart();
    }

    private void focusHomeStart() {
        if (favoriteRow.getChildCount() > 0 && favoriteRow.getChildAt(0).isFocusable()) {
            favoriteRow.getChildAt(0).requestFocus();
        } else {
            allAppsButton.requestFocus();
        }
    }

    private void restorePendingHomeFocus() {
        if (pendingHomeFocus && hasWindowFocus() && !showingAllApps) {
            pendingHomeFocus = false;
            focusHomeStart();
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
