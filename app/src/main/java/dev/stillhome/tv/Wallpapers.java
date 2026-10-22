package dev.stillhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's backgrounds: the three bundled photos ("res:HORIZON", "res:STONE", "res:NIGHT") and the photos
 * sent from the computer (a file URI). One Backdrop (the photo at the UI size with the scrims, plus its frosted and
 * glass copies) is built in the background and kept for the current wallpaper; the home screen is told when it's ready.
 */
final class Wallpapers {
    interface Listener { void onWallpaper(); }

    static final String[] BUNDLED = {"res:NIGHT", "res:HORIZON", "res:STONE"};
    static final String[] BUNDLED_NAMES = {"Dusk lake", "Horizon", "Stone"};

    private static Wallpapers instance;

    static synchronized Wallpapers get(Context c) {
        if (instance == null) instance = new Wallpapers(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final ExecutorService io = Ui.worker("wallpaper");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private Backdrop ready;
    private String readyKey = "", loadingKey = "";

    private Wallpapers(Context c) {
        app = c;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The backdrop for this wallpaper key if it is built; otherwise starts building it and returns null. */
    Backdrop backdrop(String key) {
        if (key.equals(readyKey)) return ready;
        if (key.equals(loadingKey)) return null;
        loadingKey = key;
        io.execute(() -> {
            Backdrop b = null;
            try {
                Bitmap src = decode(key);
                if (src != null) b = Backdrop.make(src, false);
            } catch (OutOfMemoryError | RuntimeException e) {
                AppLog.w("wallpaper " + key + ": " + e.getMessage());
            }
            final Backdrop built = b;
            main.post(() -> {
                if (!key.equals(loadingKey)) return;
                loadingKey = "";
                if (built == null) return;
                ready = built;
                readyKey = key;
                for (Listener l : new ArrayList<>(listeners)) l.onWallpaper();
            });
        });
        return null;
    }

    /** The photo decoded no bigger than needed (at most twice the UI size). */
    Bitmap decode(String key) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(key)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException | SecurityException e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W && bounds.outHeight / (opts.inSampleSize * 2) >= Backdrop.H) opts.inSampleSize *= 2;
        opts.inScaled = false;
        try (InputStream in = open(key)) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private InputStream open(String key) throws IOException {
        if (key.startsWith("res:")) {
            int resource;
            switch (key.substring(4)) {
                case "STONE": resource = R.drawable.wallpaper_stone; break;
                case "HORIZON": resource = R.drawable.wallpaper_horizon; break;
                default: resource = R.drawable.wallpaper_night;
            }
            return app.getResources().openRawResource(resource);
        }
        return app.getContentResolver().openInputStream(Uri.parse(key));
    }

    /** The photos sent from the computer, newest first. */
    static File[] imported(Context c) {
        File directory = new File(c.getFilesDir(), "wallpapers");
        File[] images = directory.listFiles(f -> f.isFile() && isImageName(f.getName()));
        if (images == null) return new File[0];
        Arrays.sort(images, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return images;
    }

    /** Every photo the screensaver can show: the current wallpaper first, then the imported ones, then the bundled ones. */
    static List<String> all(Context c) {
        List<String> out = new ArrayList<>();
        String current = Prefs.wallpaper();
        if (!current.equals(Prefs.AURORA)) out.add(current);
        for (File f : imported(c)) {
            String key = Uri.fromFile(f).toString();
            if (!out.contains(key)) out.add(key);
        }
        for (String key : BUNDLED) if (!out.contains(key)) out.add(key);
        return out;
    }

    static boolean isImageName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    /** A readable name for a photo key. */
    static String name(String key) {
        for (int i = 0; i < BUNDLED.length; i++) if (BUNDLED[i].equals(key)) return BUNDLED_NAMES[i];
        if (key.equals(Prefs.AURORA)) return "Aurora";
        String file = Uri.parse(key).getLastPathSegment();
        if (file == null) return "Photo";
        String name = file.replaceFirst("\\.[^.]+$", "").replaceFirst("-[0-9a-f]{8}$", "").replace('_', ' ');
        return name.isEmpty() ? "Photo" : name;
    }
}
