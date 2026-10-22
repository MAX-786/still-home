package dev.stillhome.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the owner changes on the TV (Launcher settings). No account, no sync: one SharedPreferences file.
 * The home screen and the screensaver compare `version` to know when to draw again.
 */
final class Prefs {
    private Prefs() {}

    /** Background choices: the live aurora, a bundled photo ("res:NIGHT"…), or a personal photo (a file URI). */
    static final String AURORA = "aurora";
    static final String DEFAULT_WALLPAPER = "res:NIGHT";

    /** Bumped by every change. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrate();
        Ui.applyLanguage();
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    /** Version 0.2 kept its choices in another file: the wallpaper and the favorites carry over once. */
    private static void migrate() {
        SharedPreferences old = app.getSharedPreferences("still_home", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp().edit();
        if (!sp().contains("wallpaper")) {
            String uri = old.getString("wallpaper_uri", null);
            String palette = old.getString("palette", null);
            if (uri != null) e.putString("wallpaper", uri);
            else if (palette != null) e.putString("wallpaper", "res:" + palette);
        }
        if (!sp().contains("shelf") && old.contains("favorites")) {
            e.putString("shelf", old.getString("favorites", "").replace('\n', ','));
        }
        e.apply();
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("hi", "pt", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? "auto" : sp().getString("language", "auto");
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", "auto").trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (the TV's setting), "12" or "24". */
    static String clock() {
        String v = sp().getString("clock", "auto");
        return v.equals("12") || v.equals("24") ? v : "auto";
    }

    static void setClock(String v) { put("clock", v); }

    // MARK: - City (for the weather and the sunset)

    static String city() { return sp().getString("city", "").trim(); }
    static String latitude() { return sp().getString("latitude", ""); }
    static String longitude() { return sp().getString("longitude", ""); }

    static boolean hasCity() {
        return !latitude().isEmpty() && !longitude().isEmpty();
    }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    // MARK: - Home screen

    /** The soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    /** The background: AURORA, "res:<name>" for a bundled photo, or a personal photo's file URI. */
    static String wallpaper() { return sp().getString("wallpaper", DEFAULT_WALLPAPER); }
    static void setWallpaper(String key) { put("wallpaper", key == null ? DEFAULT_WALLPAPER : key); }

    /** With the aurora background: it keeps flowing on the home screen (off: it only moves on the screensaver). */
    static boolean liveAurora() { return sp().getBoolean("liveAurora", true); }
    static void setLiveAurora(boolean on) { put("liveAurora", on); }

    /** Packages on the shelf, in order; null until the owner edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty() && !out.contains(p)) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    static void resetShelf() {
        sp().edit().remove("shelf").apply();
        version++;
    }

    // MARK: - Screensaver

    /** "analog" (the glass clock) or "digital". */
    static String dreamStyle() {
        return sp().getString("dreamStyle", "analog").equals("digital") ? "digital" : "analog";
    }

    static void setDreamStyle(String v) { put("dreamStyle", v); }

    /** "photos" (your wallpapers, slowly moving) or "aurora". */
    static String dreamBackground() {
        return sp().getString("dreamBackground", "photos").equals("aurora") ? "aurora" : "photos";
    }

    static void setDreamBackground(String v) { put("dreamBackground", v); }
}
