package dev.stillhome.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Decodes one wallpaper and a tiny frosted copy off the UI thread. */
final class WallpaperStore {
    private static final ExecutorService DECODE = Ui.worker("decode");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<View>> GLASS_SURFACES = new ArrayList<>();
    private static final Map<String, List<WeakReference<ImageView>>> PENDING = new HashMap<>();
    private static Bitmap cachedBitmap;
    private static Bitmap cachedBlur;
    private static String cachedUri;

    private WallpaperStore() { }

    static void clearMemoryCache() {
        synchronized (WallpaperStore.class) {
            cachedBitmap = null;
            cachedBlur = null;
            cachedUri = null;
        }
        invalidateGlassSurfaces();
    }

    static synchronized void registerGlassSurface(View owner) {
        for (Iterator<WeakReference<View>> iterator = GLASS_SURFACES.iterator();
                iterator.hasNext();) {
            View registered = iterator.next().get();
            if (registered == null) {
                iterator.remove();
            } else if (registered == owner) {
                return;
            }
        }
        GLASS_SURFACES.add(new WeakReference<>(owner));
    }

    static Bitmap blurFor(Context context, Palette palette) {
        String key = imageKey(context, palette);
        synchronized (WallpaperStore.class) {
            return key.equals(cachedUri) ? cachedBlur : null;
        }
    }

    static void loadInto(Context context, ImageView imageView, Palette palette) {
        String key = imageKey(context, palette);
        if (!key.equals(imageView.getTag())) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            imageView.setTag(key);
        }

        Bitmap cached;
        boolean startDecode = false;
        synchronized (WallpaperStore.class) {
            cached = key.equals(cachedUri) ? cachedBitmap : null;
            if (cached == null) {
                List<WeakReference<ImageView>> waiting = PENDING.get(key);
                if (waiting == null) {
                    waiting = new ArrayList<>();
                    PENDING.put(key, waiting);
                    startDecode = true;
                }
                waiting.add(new WeakReference<>(imageView));
            }
        }
        if (cached != null) {
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            return;
        }
        if (!startDecode) {
            return;
        }

        Context appContext = context.getApplicationContext();
        DECODE.execute(() -> {
            if (!key.equals(imageKey(appContext, Palette.current(appContext)))) {
                MAIN.post(() -> finishLoad(appContext, key, null, null));
                return;
            }
            Bitmap decoded = decode(appContext, key);
            if (decoded == null && !key.startsWith("res:")) {
                decoded = decode(appContext, "res:" + palette.name());
            }
            Bitmap ready = decoded;
            boolean current = key.equals(imageKey(appContext, Palette.current(appContext)));
            Bitmap readyBlur = current && ready != null && !"res:NIGHT".equals(key)
                    ? makeTinyBlur(ready) : null;
            MAIN.post(() -> finishLoad(appContext, key, ready, readyBlur));
        });
    }

    private static void finishLoad(Context context, String key, Bitmap image, Bitmap blur) {
        boolean current = key.equals(imageKey(context, Palette.current(context)));
        List<WeakReference<ImageView>> waiting;
        synchronized (WallpaperStore.class) {
            waiting = PENDING.remove(key);
            if (current && image != null) {
                cachedBitmap = image;
                cachedBlur = blur;
                cachedUri = key;
            }
        }
        if (waiting != null) {
            for (WeakReference<ImageView> reference : waiting) {
                ImageView target = reference.get();
                if (target == null || !key.equals(target.getTag())) {
                    continue;
                }
                if (current && image != null) {
                    target.setImageBitmap(image);
                    target.setVisibility(View.VISIBLE);
                } else {
                    // A failed decode may be retried the next time the screen resumes.
                    target.setTag(null);
                }
            }
        }
        if (current && image != null) {
            invalidateGlassSurfaces();
        }
    }

    /** A 256-pixel copy is enough for glass and uses at most 128 KiB in RGB_565. */
    private static Bitmap makeTinyBlur(Bitmap source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        float scale = Math.min(1f, 256f / longest);
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        int tinyWidth = Math.max(1, width / 4);
        int tinyHeight = Math.max(1, height / 4);
        try {
            Bitmap tiny = Bitmap.createScaledBitmap(source, tinyWidth, tinyHeight, true);
            Bitmap blurred = Bitmap.createScaledBitmap(tiny, width, height, true);
            if (tiny != source && tiny != blurred) {
                tiny.recycle();
            }
            return blurred;
        } catch (OutOfMemoryError error) {
            return null;
        }
    }

    private static void invalidateGlassSurfaces() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(WallpaperStore::invalidateGlassSurfaces);
            return;
        }
        List<View> active = new ArrayList<>();
        synchronized (WallpaperStore.class) {
            for (Iterator<WeakReference<View>> iterator = GLASS_SURFACES.iterator();
                    iterator.hasNext();) {
                View owner = iterator.next().get();
                if (owner == null) {
                    iterator.remove();
                } else {
                    active.add(owner);
                }
            }
        }
        for (View owner : active) {
            owner.invalidate();
        }
    }

    private static String imageKey(Context context, Palette palette) {
        String key = Prefs.wallpaper();
        return key.startsWith("res:") || key.equals(Prefs.AURORA) ? "res:" + palette.name() : key;
    }

    @SuppressLint("ResourceType") // Drawable JPEGs are decoded as streams to bound memory use.
    private static InputStream open(Context context, String key) throws IOException {
        if (key.startsWith("res:")) {
            int resource;
            switch (key.substring(4)) {
                case "STONE":
                    resource = R.drawable.wallpaper_stone;
                    break;
                case "NIGHT":
                    resource = R.drawable.wallpaper_night;
                    break;
                default:
                    resource = R.drawable.wallpaper_horizon;
            }
            return context.getResources().openRawResource(resource);
        }
        return context.getContentResolver().openInputStream(Uri.parse(key));
    }

    static boolean canRead(Context context, Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return false;
            }
            BitmapFactory.decodeStream(input, null, bounds);
            return bounds.outWidth > 0 && bounds.outHeight > 0;
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    private static Bitmap decode(Context context, String key) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = open(context, key)) {
            if (input == null) {
                return null;
            }
            BitmapFactory.decodeStream(input, null, bounds);
        } catch (IOException | SecurityException ignored) {
            return null;
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        DisplayMetrics display = context.getResources().getDisplayMetrics();
        int targetWidth = Math.min(Math.max(display.widthPixels, 1280), 2560);
        int targetHeight = Math.min(Math.max(display.heightPixels, 720), 1440);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                || bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream input = open(context, key)) {
            return input == null ? null : BitmapFactory.decodeStream(input, null, options);
        } catch (IOException | SecurityException | OutOfMemoryError ignored) {
            return null;
        }
    }
}
