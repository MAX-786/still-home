package dev.stillhome.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Honest housekeeping and shortcuts to the TV's own app/storage controls. */
public final class MaintenanceActivity extends Activity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private LinearLayout appList;
    private TextView storageValue;
    private TextView memoryValue;
    private Palette palette;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.fullScreen(this);
        palette = LauncherPrefs.palette(this);
        buildScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStats();
        loadApps();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void buildScreen() {
        BackdropLayout backdrop = new BackdropLayout(this, palette, false);
        setContentView(backdrop);
        LinearLayout page = TvUi.column(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
        params.setMargins(TvUi.dp(this, 42), TvUi.dp(this, 30),
                TvUi.dp(this, 42), TvUi.dp(this, 30));
        backdrop.addView(page, params);

        LinearLayout header = TvUi.row(this);
        page.addView(header);
        TextView back = TvUi.action(this, palette, "Back");
        back.setOnClickListener(view -> finish());
        header.addView(back);
        TextView title = TvUi.text(this, "Maintenance", 28, TvUi.WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.leftMargin = TvUi.dp(this, 18);
        header.addView(title, titleParams);
        TvUi.addVerticalSpace(page, this, 22);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        TvUi.clipToBounds(scroll);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout sections = TvUi.column(this);
        sections.setPadding(0, 0, 0, TvUi.dp(this, 30));
        scroll.addView(sections);

        sections.addView(TvUi.text(this,
                "Android manages memory automatically. Free storage is the useful thing to watch.",
                16, TvUi.MUTED, false));
        TvUi.addVerticalSpace(sections, this, 16);
        LinearLayout stats = TvUi.row(this);
        sections.addView(stats);

        storageValue = addStat(stats, "Storage free");
        memoryValue = addStat(stats, "Memory available");

        TvUi.addVerticalSpace(sections, this, 22);
        LinearLayout actions = TvUi.row(this);
        sections.addView(actions);
        addAction(actions, "Storage settings", () ->
                openSystem(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
        addAction(actions, "Manage apps", () ->
                openSystem(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        addAction(actions, "Clear launcher cache", this::clearOwnCache);

        TvUi.addVerticalSpace(sections, this, 24);
        sections.addView(TvUi.text(this, "Installed apps", 20, TvUi.WHITE, true));
        TvUi.addVerticalSpace(sections, this, 6);
        sections.addView(TvUi.text(this,
                "Select an app to open Android’s App info, where you can clear its cache or uninstall it.",
                14, TvUi.MUTED, false));
        TvUi.addVerticalSpace(sections, this, 12);
        appList = TvUi.column(this);
        sections.addView(appList);
        appList.addView(TvUi.text(this, "Loading apps…", 16, TvUi.MUTED, false));
        back.requestFocus();
    }

    private TextView addStat(LinearLayout row, String label) {
        LinearLayout card = TvUi.column(this);
        card.setPadding(TvUi.dp(this, 16), TvUi.dp(this, 14),
                TvUi.dp(this, 16), TvUi.dp(this, 14));
        card.setBackground(TvUi.rounded(this, palette.panel(), 0x55FFFFFF, 14));
        card.addView(TvUi.text(this, label, 14, TvUi.MUTED, false));
        TvUi.addVerticalSpace(card, this, 6);
        TextView value = TvUi.text(this, "", 20, TvUi.WHITE, true);
        card.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(TvUi.dp(this, 255), -2);
        params.rightMargin = TvUi.dp(this, 14);
        row.addView(card, params);
        return value;
    }

    private void updateStats() {
        StatFs storage = new StatFs(Environment.getDataDirectory().getPath());
        storageValue.setText(format(storage.getAvailableBytes())
                + " of " + format(storage.getTotalBytes()));
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memory);
        memoryValue.setText(format(memory.availMem) + " of " + format(memory.totalMem));
    }

    private void addAction(LinearLayout row, String label, Runnable action) {
        TextView button = TvUi.action(this, palette, label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.rightMargin = TvUi.dp(this, 10);
        row.addView(button, params);
        button.setOnClickListener(view -> action.run());
    }

    private void loadApps() {
        loader.execute(() -> {
            List<AppEntry> apps = AppCatalog.load(getApplicationContext());
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    renderApps(apps);
                }
            });
        });
    }

    private void renderApps(List<AppEntry> apps) {
        appList.removeAllViews();
        for (AppEntry app : apps) {
            LinearLayout row = TvUi.row(this);
            row.setPadding(TvUi.dp(this, 12), TvUi.dp(this, 7),
                    TvUi.dp(this, 12), TvUi.dp(this, 7));
            TvUi.focusSurface(row, palette, palette.panel(), 12);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.iconFor(getResources()));
            row.addView(icon, new LinearLayout.LayoutParams(TvUi.dp(this, 34), TvUi.dp(this, 34)));
            TextView name = TvUi.text(this, app.label, 16, TvUi.WHITE, true);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
            nameParams.leftMargin = TvUi.dp(this, 12);
            row.addView(name, nameParams);
            TextView info = TvUi.text(this, "App info", 13, TvUi.MUTED, false);
            info.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(info);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 52));
            rowParams.bottomMargin = TvUi.dp(this, 6);
            appList.addView(row, rowParams);
            row.setOnClickListener(view -> openAppInfo(app.packageName));
        }
        if (apps.isEmpty()) {
            appList.addView(TvUi.text(this, "No launchable apps found", 16, TvUi.MUTED, false));
        }
    }

    private void openAppInfo(String packageName) {
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName));
        openSystem(details);
    }

    private void openSystem(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException | SecurityException ignored) {
                Toast.makeText(this, "System settings are unavailable", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void clearOwnCache() {
        deleteChildren(getCacheDir());
        WallpaperStore.clearMemoryCache();
        AppCatalog.clearMemoryCache();
        Toast.makeText(this, "Still Home cache cleared", Toast.LENGTH_SHORT).show();
    }

    private void deleteChildren(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                deleteChildren(child);
            }
            // Cache files belong to this app only.
            child.delete();
        }
    }

    private String format(long bytes) {
        return Formatter.formatFileSize(this, bytes);
    }
}
