package dev.stillhome.tv;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/** Appearance, wallpaper and Android-managed Home/screensaver selection. */
public final class SettingsActivity extends Activity {
    private Palette palette;
    private BackdropLayout backdrop;
    private LinearLayout importedPhotos;
    private TextView screensaverStatus;
    private TextView homeStatus;

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
        backdrop.refreshWallpaper();
        refreshImportedPhotos();
        refreshScreensaverStatus();
        refreshHomeStatus();
    }

    private void buildScreen() {
        backdrop = new BackdropLayout(this, palette, false);
        setContentView(backdrop);

        LinearLayout page = TvUi.column(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        params.setMargins(TvUi.dp(this, 42), TvUi.dp(this, 30),
                TvUi.dp(this, 42), TvUi.dp(this, 30));
        backdrop.addView(page, params);

        LinearLayout header = TvUi.row(this);
        page.addView(header);
        TextView back = TvUi.action(this, palette, "Back");
        back.setOnClickListener(view -> finish());
        header.addView(back);
        TextView title = TvUi.text(this, "Launcher settings", 28, TvUi.WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.leftMargin = TvUi.dp(this, 18);
        header.addView(title, titleParams);
        TvUi.addVerticalSpace(page, this, 24);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        TvUi.clipToBounds(scroll);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout sections = TvUi.column(this);
        sections.setPadding(0, 0, 0, TvUi.dp(this, 32));
        scroll.addView(sections);

        sections.addView(TvUi.text(this, "Built-in wallpapers", 20, TvUi.WHITE, true));
        TvUi.addVerticalSpace(sections, this, 12);
        LinearLayout choices = TvUi.row(this);
        sections.addView(choices);
        for (Palette choice : Palette.values()) {
            View preview = palettePreview(choice);
            LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(
                    TvUi.dp(this, 170), TvUi.dp(this, 100));
            choiceParams.rightMargin = TvUi.dp(this, 14);
            choices.addView(preview, choiceParams);
        }

        TvUi.addVerticalSpace(sections, this, 24);
        sections.addView(TvUi.text(this, "Personal photo", 20, TvUi.WHITE, true));
        TvUi.addVerticalSpace(sections, this, 8);
        sections.addView(TvUi.text(this,
                "Send a photo from your Mac with the included wallpaper script. Photos you send appear below.",
                14, TvUi.MUTED, false));
        TvUi.addVerticalSpace(sections, this, 12);
        LinearLayout wallpaperActions = TvUi.row(this);
        sections.addView(wallpaperActions);
        addAction(wallpaperActions, "Use built-in", () -> {
            LauncherPrefs.setWallpaperUri(this, null);
            backdrop.refreshWallpaper();
            Toast.makeText(this, "Built-in wallpaper selected", Toast.LENGTH_SHORT).show();
        });
        TvUi.addVerticalSpace(sections, this, 12);
        importedPhotos = TvUi.column(this);
        sections.addView(importedPhotos);

        TvUi.addVerticalSpace(sections, this, 26);
        sections.addView(TvUi.text(this, "Screensaver", 20, TvUi.WHITE, true));
        screensaverStatus = TvUi.text(this, "", 14, TvUi.MUTED, false);
        TvUi.addVerticalSpace(sections, this, 8);
        sections.addView(screensaverStatus);
        TvUi.addVerticalSpace(sections, this, 12);
        LinearLayout dreamActions = TvUi.row(this);
        sections.addView(dreamActions);
        addAction(dreamActions, "Preview", () ->
                startActivity(new Intent(this, AmbientPreviewActivity.class)));

        TvUi.addVerticalSpace(sections, this, 26);
        sections.addView(TvUi.text(this, "TV controls", 20, TvUi.WHITE, true));
        TvUi.addVerticalSpace(sections, this, 8);
        homeStatus = TvUi.text(this, "", 14, TvUi.MUTED, false);
        sections.addView(homeStatus);
        TvUi.addVerticalSpace(sections, this, 12);
        LinearLayout tvActions = TvUi.row(this);
        sections.addView(tvActions);
        addAction(tvActions, "Set as Home", this::requestHomeRole);
        addAction(tvActions, "Maintenance", () ->
                startActivity(new Intent(this, MaintenanceActivity.class)));

        back.requestFocus();
    }

    private View palettePreview(Palette choice) {
        FrameLayout card = new FrameLayout(this);
        int resource;
        switch (choice) {
            case STONE:
                resource = R.drawable.wallpaper_stone;
                break;
            case NIGHT:
                resource = R.drawable.wallpaper_night;
                break;
            default:
                resource = R.drawable.wallpaper_horizon;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 8;
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        ImageView image = new ImageView(this);
        image.setImageBitmap(BitmapFactory.decodeResource(getResources(), resource, options));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));
        card.setClipToOutline(true);
        TextView name = TvUi.text(this, choice.title, 16, TvUi.WHITE, true);
        name.setGravity(Gravity.BOTTOM | Gravity.START);
        name.setPadding(TvUi.dp(this, 12), 0, 0, TvUi.dp(this, 10));
        card.addView(name, new FrameLayout.LayoutParams(-1, -1));
        TvUi.focusSurface(card, palette, 0x22000000, 14);
        card.setContentDescription(choice.title + (choice == palette ? ", selected" : ""));
        card.setOnClickListener(view -> {
            LauncherPrefs.setPalette(this, choice);
            LauncherPrefs.setWallpaperUri(this, null);
            recreate();
        });
        return card;
    }

    private void addAction(LinearLayout row, String label, Runnable action) {
        TextView button = TvUi.action(this, palette, label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.rightMargin = TvUi.dp(this, 12);
        row.addView(button, params);
        button.setOnClickListener(view -> action.run());
    }

    private void refreshImportedPhotos() {
        importedPhotos.removeAllViews();
        File directory = new File(getFilesDir(), "wallpapers");
        File[] images = directory.listFiles(file -> file.isFile() && isImageName(file.getName()));
        if (images == null || images.length == 0) {
            importedPhotos.addView(TvUi.text(this,
                    "No Mac photos yet. See the install guide for the one-command transfer.",
                    14, TvUi.MUTED, false));
            return;
        }
        Arrays.sort(images, Comparator.comparingLong(File::lastModified).reversed());
        importedPhotos.addView(TvUi.text(this, "Photos from Mac", 16, TvUi.WHITE, true));
        TvUi.addVerticalSpace(importedPhotos, this, 8);
        importedPhotos.addView(TvUi.text(this, "Hold OK on a photo to remove it.",
                14, TvUi.MUTED, false));
        TvUi.addVerticalSpace(importedPhotos, this, 8);
        for (File file : images) {
            Uri uri = Uri.fromFile(file);
            String name = file.getName().replaceFirst("\\.[^.]+$", "")
                    .replaceFirst("-[0-9a-f]{8}$", "").replace('_', ' ');
            TextView photo = TvUi.action(this, palette, TextUtils.isEmpty(name) ? "Photo" : name);
            photo.setContentDescription("Use wallpaper " + name);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = TvUi.dp(this, 7);
            importedPhotos.addView(photo, params);
            photo.setOnClickListener(view -> {
                if (!WallpaperStore.canRead(this, uri)) {
                    Toast.makeText(this, "This photo cannot be opened", Toast.LENGTH_LONG).show();
                    return;
                }
                LauncherPrefs.setWallpaperUri(this, uri.toString());
                backdrop.refreshWallpaper();
                Toast.makeText(this, "Wallpaper applied", Toast.LENGTH_SHORT).show();
            });
            TvUi.remoteLongClick(photo, view -> {
                if (!file.delete()) {
                    Toast.makeText(this, "Could not remove this photo", Toast.LENGTH_LONG).show();
                    return true;
                }
                if (uri.toString().equals(LauncherPrefs.wallpaperUri(this))) {
                    LauncherPrefs.setWallpaperUri(this, null);
                    backdrop.refreshWallpaper();
                }
                refreshImportedPhotos();
                Toast.makeText(this, "Photo removed", Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    private boolean isImageName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private void refreshScreensaverStatus() {
        String selected = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        String enabled = Settings.Secure.getString(getContentResolver(), "screensaver_enabled");
        String component = getPackageName() + "/" + StillDreamService.class.getName();
        boolean isSelected = false;
        if (selected != null) {
            for (String candidate : selected.split(",")) {
                if (candidate.trim().equals(component)) {
                    isSelected = true;
                    break;
                }
            }
        }
        if (isSelected && "1".equals(enabled)) {
            screensaverStatus.setText("Still Home is the selected and enabled TV screensaver.");
        } else {
            screensaverStatus.setText(
                    "On this Redmi TV, select Still Home with the Mac setup script in the install guide.");
        }
    }

    private void refreshHomeStatus() {
        RoleManager roles = Build.VERSION.SDK_INT >= 29
                ? getSystemService(RoleManager.class) : null;
        boolean chosen = roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME);
        if (chosen && !stockHomeEnabled()) {
            homeStatus.setText("Still Home is set for the TV’s Home button.");
        } else if (chosen) {
            homeStatus.setText(
                    "This Redmi needs the Mac setup step to keep Google TV from opening on Home.");
        } else {
            homeStatus.setText("Choose Still Home as Home, then finish setup from the Mac.");
        }
    }

    private boolean stockHomeEnabled() {
        try {
            int state = getPackageManager().getApplicationEnabledSetting(
                    "com.google.android.tvlauncher");
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (IllegalArgumentException error) {
            return true;
        }
    }

    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    Toast.makeText(this, stockHomeEnabled()
                            ? "Finish the Redmi Home setup from your Mac"
                            : "Still Home is already Home", Toast.LENGTH_LONG).show();
                } else {
                    try {
                        startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_HOME));
                    } catch (ActivityNotFoundException | SecurityException error) {
                        openSettings(new Intent(Settings.ACTION_HOME_SETTINGS));
                    }
                }
                return;
            }
        }
        openSettings(new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, "This TV does not expose that setting",
                    Toast.LENGTH_LONG).show();
        }
    }
}
