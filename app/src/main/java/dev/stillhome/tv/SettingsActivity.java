package dev.stillhome.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
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

/** Launcher settings: background, weather and clock, favorites, screensaver, sounds, language, TV controls. */
public final class SettingsActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Palette palette;
    private BackdropLayout backdrop;
    private LinearLayout importedPhotos;
    private LinearLayout backgroundRow;
    private TextView screensaverStatus, homeStatus, cityButton, unitButton, clockButton, weatherButton;
    private TextView liveButton, styleButton, dreamBackgroundButton, soundsButton, languageButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.fullScreen(this);
        palette = Palette.current(this);
        buildScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (palette != Palette.current(this)) {
            recreate();
            return;
        }
        backdrop.refreshWallpaper();
        refreshBackgrounds();
        refreshImportedPhotos();
        refreshLabels();
        refreshScreensaverStatus();
        refreshHomeStatus();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildScreen() {
        backdrop = new BackdropLayout(this, palette, false);
        setContentView(backdrop);

        LinearLayout page = TvUi.column(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
        params.setMargins(TvUi.dp(this, 42), TvUi.dp(this, 30), TvUi.dp(this, 42), TvUi.dp(this, 30));
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

        // Background
        heading(sections, "Background", "The live aurora follows the sunset at your city. Photos get the frosted glass look.");
        backgroundRow = TvUi.row(this);
        sections.addView(backgroundRow);
        TvUi.addVerticalSpace(sections, this, 12);
        LinearLayout backgroundActions = TvUi.row(this);
        sections.addView(backgroundActions);
        liveButton = addAction(backgroundActions, "", () -> {
            Prefs.setLiveAurora(!Prefs.liveAurora());
            refreshLabels();
        });

        TvUi.addVerticalSpace(sections, this, 24);
        sections.addView(TvUi.text(this, "Photos from your computer", 18, TvUi.WHITE, true));
        TvUi.addVerticalSpace(sections, this, 8);
        sections.addView(TvUi.text(this, "Send a photo with scripts/send_wallpaper.py. Photos you send appear below; hold OK on one to remove it.",
                14, TvUi.MUTED, false));
        TvUi.addVerticalSpace(sections, this, 12);
        importedPhotos = TvUi.column(this);
        sections.addView(importedPhotos);

        // Weather and clock
        heading(sections, "Weather and clock", "Forecasts come from Open-Meteo (no account, no key). Only your city's coordinates are sent.");
        LinearLayout weatherRow = TvUi.row(this);
        sections.addView(weatherRow);
        weatherButton = addAction(weatherRow, "", () -> {
            Prefs.setWeather(!Prefs.weather());
            refreshLabels();
        });
        cityButton = addAction(weatherRow, "", this::askCity);
        unitButton = addAction(weatherRow, "", () -> {
            String u = Prefs.temperature();
            Prefs.setTemperature(u.equals("auto") ? "C" : u.equals("C") ? "F" : "auto");
            refreshLabels();
        });
        clockButton = addAction(weatherRow, "", () -> {
            String c = Prefs.clock();
            Prefs.setClock(c.equals("auto") ? "12" : c.equals("12") ? "24" : "auto");
            refreshLabels();
        });

        // Favorites
        heading(sections, "Favorites", "On Home: hold OK on a favorite to move it. In Apps: hold OK on an app to pin or unpin it.");
        LinearLayout shelfRow = TvUi.row(this);
        sections.addView(shelfRow);
        addAction(shelfRow, "Reset favorites", () -> {
            Prefs.resetShelf();
            Toast.makeText(this, "Favorites reset to the installed streaming apps", Toast.LENGTH_SHORT).show();
        });

        // Screensaver
        heading(sections, "Screensaver", "");
        screensaverStatus = TvUi.text(this, "", 14, TvUi.MUTED, false);
        sections.addView(screensaverStatus);
        TvUi.addVerticalSpace(sections, this, 12);
        LinearLayout dreamActions = TvUi.row(this);
        sections.addView(dreamActions);
        styleButton = addAction(dreamActions, "", () -> {
            Prefs.setDreamStyle(Prefs.dreamStyle().equals("analog") ? "digital" : "analog");
            refreshLabels();
        });
        dreamBackgroundButton = addAction(dreamActions, "", () -> {
            Prefs.setDreamBackground(Prefs.dreamBackground().equals("photos") ? "aurora" : "photos");
            refreshLabels();
        });
        addAction(dreamActions, "Preview", () -> startActivity(new Intent(this, AmbientPreviewActivity.class)));

        // Sounds and language
        heading(sections, "Sounds and language", "");
        LinearLayout miscRow = TvUi.row(this);
        sections.addView(miscRow);
        soundsButton = addAction(miscRow, "", () -> {
            Prefs.setSounds(!Prefs.sounds());
            refreshLabels();
        });
        languageButton = addAction(miscRow, "", this::askLanguage);

        // TV controls
        heading(sections, "TV controls", "");
        homeStatus = TvUi.text(this, "", 14, TvUi.MUTED, false);
        sections.addView(homeStatus);
        TvUi.addVerticalSpace(sections, this, 12);
        LinearLayout tvActions = TvUi.row(this);
        sections.addView(tvActions);
        addAction(tvActions, "Set as Home", this::requestHomeRole);
        addAction(tvActions, "Maintenance", () -> startActivity(new Intent(this, MaintenanceActivity.class)));

        back.requestFocus();
    }

    private void heading(LinearLayout sections, String title, String note) {
        TvUi.addVerticalSpace(sections, this, 26);
        sections.addView(TvUi.text(this, title, 20, TvUi.WHITE, true));
        TvUi.addVerticalSpace(sections, this, 8);
        if (!note.isEmpty()) {
            sections.addView(TvUi.text(this, note, 14, TvUi.MUTED, false));
            TvUi.addVerticalSpace(sections, this, 12);
        }
    }

    private TextView addAction(LinearLayout row, String label, Runnable action) {
        TextView button = TvUi.action(this, palette, label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.rightMargin = TvUi.dp(this, 12);
        row.addView(button, params);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void refreshLabels() {
        liveButton.setText(Prefs.liveAurora() ? "Aurora moves on Home: on" : "Aurora moves on Home: off");
        weatherButton.setText(Prefs.weather() ? "Weather: shown" : "Weather: hidden");
        cityButton.setText(Prefs.city().isEmpty() ? "City: not set" : "City: " + Prefs.city());
        String u = Prefs.temperature();
        unitButton.setText("Temperature: " + (u.equals("C") ? "°C" : u.equals("F") ? "°F" : "Automatic"));
        String c = Prefs.clock();
        clockButton.setText("Clock: " + (c.equals("12") ? "12-hour" : c.equals("24") ? "24-hour" : "Automatic"));
        styleButton.setText(Prefs.dreamStyle().equals("analog") ? "Clock: analog glass" : "Clock: digital");
        dreamBackgroundButton.setText(Prefs.dreamBackground().equals("photos") ? "Behind it: your photos" : "Behind it: aurora");
        soundsButton.setText(Prefs.sounds() ? "Click sounds: on" : "Click sounds: off");
        String lang = Prefs.language();
        languageButton.setText("Language: " + (lang.equals("auto") ? "Automatic" : Ui.languageName(lang)));
    }

    // MARK: - Background choices

    private void refreshBackgrounds() {
        backgroundRow.removeAllViews();
        String selected = Prefs.wallpaper();
        backgroundRow.addView(auroraPreview(selected.equals(Prefs.AURORA)), cardParams());
        for (int i = 0; i < Wallpapers.BUNDLED.length; i++) {
            backgroundRow.addView(photoPreview(Wallpapers.BUNDLED[i], Wallpapers.BUNDLED_NAMES[i], selected.equals(Wallpapers.BUNDLED[i])), cardParams());
        }
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(TvUi.dp(this, 170), TvUi.dp(this, 100));
        p.rightMargin = TvUi.dp(this, 14);
        return p;
    }

    private View auroraPreview(boolean selected) {
        FrameLayout card = new FrameLayout(this);
        View sky = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF070B1E, 0xFF16225A, 0xFF4B2F8F, 0xFF16225A});
        g.setCornerRadius(TvUi.dp(this, 14));
        sky.setBackground(g);
        card.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        card.setClipToOutline(true);
        TextView name = TvUi.text(this, "Aurora · live", 16, TvUi.WHITE, true);
        name.setGravity(Gravity.BOTTOM | Gravity.START);
        name.setPadding(TvUi.dp(this, 12), 0, 0, TvUi.dp(this, 10));
        card.addView(name, new FrameLayout.LayoutParams(-1, -1));
        TvUi.focusSurface(card, palette, selected ? 0x44A8B0FB : 0x22000000, 14);
        card.setContentDescription("Aurora" + (selected ? ", selected" : ""));
        card.setOnClickListener(view -> choose(Prefs.AURORA));
        return card;
    }

    private View photoPreview(String key, String title, boolean selected) {
        FrameLayout card = new FrameLayout(this);
        int resource = key.endsWith("STONE") ? R.drawable.wallpaper_stone : key.endsWith("HORIZON") ? R.drawable.wallpaper_horizon : R.drawable.wallpaper_night;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 8;
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        ImageView image = new ImageView(this);
        image.setImageBitmap(BitmapFactory.decodeResource(getResources(), resource, options));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));
        card.setClipToOutline(true);
        TextView name = TvUi.text(this, title, 16, TvUi.WHITE, true);
        name.setGravity(Gravity.BOTTOM | Gravity.START);
        name.setPadding(TvUi.dp(this, 12), 0, 0, TvUi.dp(this, 10));
        card.addView(name, new FrameLayout.LayoutParams(-1, -1));
        TvUi.focusSurface(card, palette, selected ? 0x44A8B0FB : 0x22000000, 14);
        card.setContentDescription(title + (selected ? ", selected" : ""));
        card.setOnClickListener(view -> choose(key));
        return card;
    }

    private void choose(String key) {
        Prefs.setWallpaper(key);
        WallpaperStore.clearMemoryCache();
        Toast.makeText(this, Wallpapers.name(key) + " selected", Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void refreshImportedPhotos() {
        importedPhotos.removeAllViews();
        File[] images = Wallpapers.imported(this);
        if (images.length == 0) {
            importedPhotos.addView(TvUi.text(this, "No photos yet. See the install guide for the one-command transfer.", 14, TvUi.MUTED, false));
            return;
        }
        String selected = Prefs.wallpaper();
        for (File file : images) {
            Uri uri = Uri.fromFile(file);
            String name = Wallpapers.name(uri.toString());
            boolean chosen = uri.toString().equals(selected);
            TextView photo = TvUi.action(this, palette, (chosen ? "✓  " : "") + (TextUtils.isEmpty(name) ? "Photo" : name));
            photo.setContentDescription("Use wallpaper " + name + (chosen ? ", selected" : ""));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = TvUi.dp(this, 7);
            importedPhotos.addView(photo, params);
            photo.setOnClickListener(view -> {
                if (!WallpaperStore.canRead(this, uri)) {
                    Toast.makeText(this, "This photo cannot be opened", Toast.LENGTH_LONG).show();
                    return;
                }
                choose(uri.toString());
            });
            TvUi.remoteLongClick(photo, view -> {
                if (!file.delete()) {
                    Toast.makeText(this, "Could not remove this photo", Toast.LENGTH_LONG).show();
                    return true;
                }
                if (uri.toString().equals(Prefs.wallpaper())) {
                    Prefs.setWallpaper(null);
                    WallpaperStore.clearMemoryCache();
                    backdrop.refreshWallpaper();
                }
                refreshImportedPhotos();
                Toast.makeText(this, "Photo removed", Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    // MARK: - City

    private void askCity() {
        EditText input = new EditText(this);
        input.setHint("City name");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setText(Prefs.city());
        input.selectAll();   // typing replaces the current city
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Your city")
                .setMessage("Type the name and choose a match. Only its coordinates are sent to Open-Meteo.")
                .setView(input)
                .setPositiveButton("Search", (d, which) -> searchCity(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.dismiss();
                searchCity(input.getText().toString());
                return true;
            }
            return false;
        });
        dialog.show();
    }

    private void searchCity(String name) {
        if (name.trim().isEmpty()) return;
        Toast.makeText(this, "Searching…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            List<String[]> found;
            try {
                found = HomeData.findCity(name);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't search: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (found.isEmpty()) {
                    Toast.makeText(this, "No place called “" + name.trim() + "” was found", Toast.LENGTH_LONG).show();
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = found.get(i)[0];
                new AlertDialog.Builder(this)
                        .setTitle("Which one?")
                        .setItems(labels, (d, which) -> {
                            String[] pick = found.get(which);
                            Prefs.setCity(pick[1], pick[2], pick[3]);
                            HomeData.get(this).settingsChanged();
                            HomeData.get(this).refreshIfStale();
                            refreshLabels();
                            Toast.makeText(this, "Weather for " + pick[0], Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void askLanguage() {
        String[] codes = Ui.languages();
        String[] labels = new String[codes.length + 1];
        labels[0] = "Automatic (the TV's language)";
        for (int i = 0; i < codes.length; i++) labels[i + 1] = Ui.languageName(codes[i]);
        new AlertDialog.Builder(this)
                .setTitle("Language")
                .setItems(labels, (d, which) -> {
                    Prefs.setLanguage(which == 0 ? "auto" : codes[which - 1]);
                    refreshLabels();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // MARK: - Screensaver and Home status (unchanged from 0.2)

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
            screensaverStatus.setText("On this Redmi TV, select Still Home with scripts/configure_screensaver.py from your computer.");
        }
    }

    private void refreshHomeStatus() {
        boolean chosen = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = getSystemService(RoleManager.class);
            chosen = roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME);
        }
        if (chosen && !stockHomeEnabled()) {
            homeStatus.setText("Still Home is set for the TV’s Home button.");
        } else if (chosen) {
            homeStatus.setText("This Redmi needs the computer setup step to keep Google TV from opening on Home.");
        } else {
            homeStatus.setText("Choose Still Home as Home, then finish setup from your computer.");
        }
    }

    private boolean stockHomeEnabled() {
        try {
            int state = getPackageManager().getApplicationEnabledSetting("com.google.android.tvlauncher");
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
                    Toast.makeText(this, stockHomeEnabled() ? "Finish the Redmi Home setup from your computer" : "Still Home is already Home", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "This TV does not expose that setting", Toast.LENGTH_LONG).show();
        }
    }
}
