package dev.stillhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * A still, frosted surface. Night's bundled blur is prebuilt; other wallpapers use the
 * small copy prepared by WallpaperStore, with no blur work during a TV frame.
 */
final class GlassSurfaceDrawable extends Drawable {
    private static Bitmap nightBlur;
    private static boolean blurLoadAttempted;

    private final View owner;
    private final Palette palette;
    private final boolean toolbar;
    private final float radius;
    private final float strokeWidth;
    private final Path shape = new Path();
    private final RectF area = new RectF();
    private final RectF outlineArea = new RectF();
    private final int[] ownerLocation = new int[2];
    private final int[] rootLocation = new int[2];
    private final Paint wallpaperPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    GlassSurfaceDrawable(Context context, View owner, Palette palette, boolean toolbar) {
        this.owner = owner;
        this.palette = palette;
        this.toolbar = toolbar;
        radius = TvUi.dp(context, toolbar ? 18 : 23);
        strokeWidth = Math.max(1f, TvUi.dp(context, 0.8f));
        WallpaperStore.registerGlassSurface(owner);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setColor(toolbar ? 0x4FE6F2FF : 0x55DCE9F5);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        area.set(bounds);
        shape.reset();
        shape.addRoundRect(area, radius, radius, Path.Direction.CW);
        outlineArea.set(area);
        outlineArea.inset(strokeWidth / 2f, strokeWidth / 2f);

        if (toolbar) {
            shadePaint.setShader(new LinearGradient(area.left, area.top,
                    area.right, area.bottom,
                    0xAD253A52, 0xB3314054, Shader.TileMode.CLAMP));
            glowPaint.setShader(new LinearGradient(area.left, area.top,
                    area.left, area.bottom,
                    0x19F4F8FF, 0x001A2637, Shader.TileMode.CLAMP));
        } else {
            shadePaint.setShader(new LinearGradient(area.left, area.top,
                    area.right, area.bottom,
                    new int[] { 0xC0041626, 0xB50C1C2A, 0xD0051220 },
                    new float[] { 0f, 0.52f, 1f }, Shader.TileMode.CLAMP));
            glowPaint.setShader(new RadialGradient(
                    area.left + area.width() * 0.8f,
                    area.top + area.height() * 0.16f,
                    Math.max(area.width() * 0.55f, 1f),
                    0x22836765, 0x00836765, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (area.isEmpty()) {
            return;
        }
        int alphaLayer = 0;
        if (alpha < 255) {
            alphaLayer = canvas.saveLayerAlpha(area.left, area.top,
                    area.right, area.bottom, alpha);
        }
        int clip = canvas.save();
        canvas.clipPath(shape);
        drawAlignedBlur(canvas);
        canvas.drawRect(area, shadePaint);
        canvas.drawRect(area, glowPaint);
        canvas.restoreToCount(clip);
        canvas.drawRoundRect(outlineArea, radius - strokeWidth / 2f,
                radius - strokeWidth / 2f, strokePaint);
        if (alphaLayer != 0) {
            canvas.restoreToCount(alphaLayer);
        }
    }

    private void drawAlignedBlur(Canvas canvas) {
        Context context = owner.getContext();
        boolean bundledNight = palette == Palette.NIGHT
                && LauncherPrefs.wallpaperUri(context) == null;
        Bitmap blurredWallpaper = bundledNight ? bundledNightBlur(context) : null;
        if (blurredWallpaper == null) {
            blurredWallpaper = WallpaperStore.blurFor(context, palette);
        }
        if (blurredWallpaper == null) {
            return;
        }
        View root = owner.getRootView();
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        owner.getLocationOnScreen(ownerLocation);
        root.getLocationOnScreen(rootLocation);
        float scale = Math.max(width / (float) blurredWallpaper.getWidth(),
                height / (float) blurredWallpaper.getHeight());
        float left = (width - blurredWallpaper.getWidth() * scale) / 2f;
        float top = (height - blurredWallpaper.getHeight() * scale) / 2f;
        int save = canvas.save();
        canvas.translate(left - (ownerLocation[0] - rootLocation[0]),
                top - (ownerLocation[1] - rootLocation[1]));
        canvas.scale(scale, scale);
        canvas.drawBitmap(blurredWallpaper, 0, 0, wallpaperPaint);
        canvas.restoreToCount(save);
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        wallpaperPaint.setColorFilter(filter);
        shadePaint.setColorFilter(filter);
        glowPaint.setColorFilter(filter);
        strokePaint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static synchronized Bitmap bundledNightBlur(Context context) {
        if (!blurLoadAttempted) {
            blurLoadAttempted = true;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try {
                nightBlur = BitmapFactory.decodeResource(context.getResources(),
                        R.drawable.wallpaper_night_blur, options);
            } catch (OutOfMemoryError ignored) {
                nightBlur = null;
            }
        }
        return nightBlur;
    }
}
