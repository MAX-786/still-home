package dev.stillhome.tv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Small, resolution-independent badges for apps whose TV icons differ from their familiar marks. */
final class AppBadgeDrawable extends Drawable {
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String LIVE_TV = "com.android.tv";
    private static final Path NETFLIX_DIAGONAL = netflixDiagonal();
    private static final Path PLAY_TRIANGLE = playTriangle();

    private enum Kind { NETFLIX, YOUTUBE, LIVE_TV }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;
    private ColorFilter colorFilter;

    private AppBadgeDrawable(Kind kind) {
        this.kind = kind;
        paint.setStyle(Paint.Style.FILL);
    }

    static Drawable forApp(String packageName, Drawable fallback) {
        if (NETFLIX.equals(packageName)) {
            return new AppBadgeDrawable(Kind.NETFLIX);
        }
        if (YOUTUBE.equals(packageName)) {
            return new AppBadgeDrawable(Kind.YOUTUBE);
        }
        if (LIVE_TV.equals(packageName)) {
            return new AppBadgeDrawable(Kind.LIVE_TV);
        }
        return fallback;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 100f, bounds.height() / 100f);
        switch (kind) {
            case NETFLIX:
                drawNetflix(canvas);
                break;
            case YOUTUBE:
                drawYouTube(canvas);
                break;
            case LIVE_TV:
                drawLiveTv(canvas);
                break;
        }
        canvas.restoreToCount(saved);
    }

    private void drawNetflix(Canvas canvas) {
        color(0xFF08090B);
        canvas.drawRoundRect(0, 0, 100, 100, 20, 20, paint);

        // The narrower, darker strokes give the N its folded-ribbon shape.
        color(0xFFB1060E);
        canvas.drawRect(30, 17, 43, 82, paint);
        canvas.drawRect(57, 17, 70, 82, paint);

        color(0xFFE50914);
        canvas.drawPath(NETFLIX_DIAGONAL, paint);
    }

    private void drawYouTube(Canvas canvas) {
        color(0xFFFF0808);
        canvas.drawRoundRect(0, 10, 100, 90, 18, 18, paint);

        color(Color.WHITE);
        canvas.drawPath(PLAY_TRIANGLE, paint);
    }

    private void drawLiveTv(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 100, 100,
                0xFF35D1F8, 0xFF0395D6, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0, 0, 100, 100, 18, 18, paint);
        paint.setShader(null);

        color(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawRoundRect(22, 29, 78, 76, 7, 7, paint);
        canvas.drawLine(50, 29, 37, 17, paint);
        canvas.drawLine(50, 29, 63, 17, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static Path netflixDiagonal() {
        Path path = new Path();
        path.moveTo(30, 17);
        path.lineTo(43, 17);
        path.lineTo(70, 82);
        path.lineTo(57, 82);
        path.close();
        return path;
    }

    private static Path playTriangle() {
        Path path = new Path();
        path.moveTo(41, 31);
        path.lineTo(70, 50);
        path.lineTo(41, 69);
        path.close();
        return path;
    }

    private void color(int value) {
        paint.setColor(value);
        paint.setAlpha((Color.alpha(value) * alpha + 127) / 255);
        paint.setColorFilter(colorFilter);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        this.colorFilter = colorFilter;
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 96;
    }

    @Override
    public int getIntrinsicHeight() {
        return 96;
    }
}
