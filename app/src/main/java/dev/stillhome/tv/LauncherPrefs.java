package dev.stillhome.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Only personal UI choices are stored; the app has no account or network access. */
final class LauncherPrefs {
    private static final String FILE = "still_home";
    private static final String PALETTE = "palette";
    private static final String WALLPAPER = "wallpaper_uri";
    private static final String FAVORITES = "favorites";

    private LauncherPrefs() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static Palette palette(Context context) {
        return Palette.fromName(prefs(context).getString(PALETTE, Palette.HORIZON.name()));
    }

    static void setPalette(Context context, Palette palette) {
        prefs(context).edit().putString(PALETTE, palette.name()).apply();
    }

    static String wallpaperUri(Context context) {
        return prefs(context).getString(WALLPAPER, null);
    }

    static void setWallpaperUri(Context context, String uri) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (uri == null) {
            editor.remove(WALLPAPER);
        } else {
            editor.putString(WALLPAPER, uri);
        }
        editor.apply();
        WallpaperStore.clearMemoryCache();
    }

    static List<String> favoritePackages(Context context, List<AppEntry> apps) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(FAVORITES)) {
            return suggestedFavorites(apps);
        }

        Set<String> installed = new HashSet<>();
        for (AppEntry app : apps) {
            installed.add(app.packageName);
        }
        List<String> result = new ArrayList<>();
        String stored = preferences.getString(FAVORITES, "");
        for (String candidate : stored.split("\\n")) {
            if (!candidate.isEmpty() && installed.contains(candidate) && !result.contains(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    static boolean isFavorite(Context context, List<AppEntry> apps, String packageName) {
        return favoritePackages(context, apps).contains(packageName);
    }

    static boolean toggleFavorite(Context context, List<AppEntry> apps, String packageName) {
        List<String> favorites = favoritePackages(context, apps);
        boolean pinned;
        if (favorites.contains(packageName)) {
            favorites.remove(packageName);
            pinned = false;
        } else {
            favorites.add(packageName);
            pinned = true;
        }
        StringBuilder stored = new StringBuilder();
        for (String favorite : favorites) {
            if (stored.length() > 0) {
                stored.append('\n');
            }
            stored.append(favorite);
        }
        prefs(context).edit().putString(FAVORITES, stored.toString()).apply();
        return pinned;
    }

    private static List<String> suggestedFavorites(List<AppEntry> apps) {
        List<String> result = new ArrayList<>();
        String[] preferred = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.mitv.tvhome.mitvplus"
        };
        for (String packageName : preferred) {
            for (AppEntry app : apps) {
                if (app.packageName.equals(packageName)) {
                    result.add(packageName);
                    break;
                }
            }
        }
        String[] additional = { "Prime Video", "JioHotstar", "Live TV" };
        for (String label : additional) {
            for (AppEntry app : apps) {
                if (app.label.equalsIgnoreCase(label) && !result.contains(app.packageName)) {
                    result.add(app.packageName);
                    break;
                }
            }
        }
        return result;
    }
}
