package dev.stillhome.tv;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Decodes a chosen photo off the UI thread and keeps at most one small bitmap. */
final class WallpaperStore {
    private static final ExecutorService DECODE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Bitmap cachedBitmap;
    private static String cachedUri;

    private WallpaperStore() { }

    static synchronized void clearMemoryCache() {
        cachedBitmap = null;
        cachedUri = null;
    }

    static void loadInto(Context context, ImageView imageView, Palette palette) {
        String key = imageKey(context, palette);
        if (!key.equals(imageView.getTag())) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            imageView.setTag(key);
        }

        Bitmap cached;
        synchronized (WallpaperStore.class) {
            cached = key.equals(cachedUri) ? cachedBitmap : null;
        }
        if (cached != null) {
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            return;
        }

        Context appContext = context.getApplicationContext();
        WeakReference<ImageView> imageRef = new WeakReference<>(imageView);
        DECODE.execute(() -> {
            Bitmap decoded = decode(appContext, key);
            if (decoded == null && !key.startsWith("res:")) {
                decoded = decode(appContext, "res:" + palette.name());
            }
            if (decoded == null) {
                return;
            }
            Bitmap ready = decoded;
            synchronized (WallpaperStore.class) {
                // A newer wallpaper selection wins over a slow earlier decode.
                if (!key.equals(imageKey(appContext, LauncherPrefs.palette(appContext)))) {
                    return;
                }
                cachedBitmap = ready;
                cachedUri = key;
            }
            MAIN.post(() -> {
                ImageView target = imageRef.get();
                if (target != null && key.equals(imageKey(appContext,
                        LauncherPrefs.palette(appContext)))) {
                    target.setImageBitmap(ready);
                    target.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private static String imageKey(Context context, Palette palette) {
        String custom = LauncherPrefs.wallpaperUri(context);
        return custom == null ? "res:" + palette.name() : custom;
    }

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
