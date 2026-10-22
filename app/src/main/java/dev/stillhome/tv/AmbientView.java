package dev.stillhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver: a glass clock over a background that never sits still.
 *  - Background "photos": your wallpapers in full screen, one every 30 s, with a slow zoom and pan (the Ken Burns
 *    move of the Apple TV photos) and a crossfade between them; each photo is loaded 10% larger than the screen,
 *    so the zoom never upscales it. Background "aurora": the night aurora, flowing slowly.
 *  - The clock, "analog": a disc of frosted glass (the background shows through it, blurred, with a rim that
 *    catches the light), hour and minute marks, white hands with soft shadows and a thin orange second hand that
 *    sweeps continuously; "digital": the big thin digits. Under it the date and the weather for your city.
 *  - The whole clock drifts slowly across the screen (no pixel stays lit in one place).
 * 30 frames a second (every other display frame): the sweep stays fluid and the TV's GPU keeps room to spare.
 */
final class AmbientView extends View implements Choreographer.FrameCallback, HomeData.Listener {
    private static final long SHOW_MS = 30_000, FADE_MS = 3_000, FIRST_MS = 1_600;
    private static final float ZOOM = 0.1f;
    private static final float DISC_R = 232, DRIFT_X = 44, DRIFT_Y = 28;
    private static final double DRIFT_PERIOD_S = 600;
    private static final int ACCENT = 0xFFFF7A45;

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its blurred copies. */
    private static final class Slide {
        final String key;
        final Bitmap image;
        final int[] frost, glass;
        final boolean zoomIn;
        final float panX, panY;
        long startedAt;

        Slide(String key, Bitmap image, int[] frost, int[] glass, int n) {
            this.key = key;
            this.image = image;
            this.frost = frost;
            this.glass = glass;
            zoomIn = n % 2 == 0;
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    private final Scene scene = new Scene(true);
    private final HomeData data;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Ui.worker("screensaver");
    private final Recording face = Recording.create("face");      // the disc's marks and numerals (static)
    private final Recording caption = Recording.create("caption"); // date and weather (once a minute)
    private final Shadows shadows = new Shadows();
    private final float timeOffset = (float) (Math.random() * 300);
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF disc = new RectF(), tmp = new RectF(), drop = new RectF();
    private RadialGradient reflection;
    private SweepGradient rimLight;
    private final boolean preview;
    private boolean running, photos, analog, faceDirty = true, captionDirty = true, decoding;
    private int generation, slides;
    private List<String> keys;
    private Slide current, incoming, prepared;
    private long startedAt, firstAt, lastFrame, lastSky;
    private int w, h, pw, ph;
    private float k = 1;
    private long minute = -1;
    private String clock = "", date = "", weatherLine = "";
    private Paint numerals, datePaint, weatherPaint, digitsPaint, digitDate;

    private final Runnable tickMinute = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            captionDirty = true;
            prepareNext();
            handler.postDelayed(this, 60_000);
        }
    };

    AmbientView(Context c, boolean preview) {
        super(c);
        this.preview = preview;
        data = HomeData.get(c);
        Shaders.dream(scene.palette);
        scene.setMotion(0.4f, 0.3f);
        scene.applyPalette();
        black.setColor(0xFF000000);
        tick.setStrokeCap(Paint.Cap.ROUND);
        hand.setStrokeCap(Paint.Cap.ROUND);
        handShadow.setStrokeCap(Paint.Cap.ROUND);
        handShadow.setColor(0x66000000);
        ring.setStyle(Paint.Style.STROKE);
        hint.setColor(0x99FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Ui.worker("screensaver");
        photos = Prefs.dreamBackground().equals("photos");
        analog = Prefs.dreamStyle().equals("analog");
        keys = Wallpapers.all(getContext());
        if (keys.isEmpty()) photos = false;
        scene.setSkyWanted(!photos);   // with photos, the glass reads their blurred copies: no aurora steps at all
        startedAt = SystemClock.uptimeMillis();
        lastFrame = 0;
        minute = -1;
        data.addListener(this);
        data.refreshIfStale();
        if (photos && pw > 0) prepareNext();   // before the first layout, onSizeChanged starts it
        handler.postDelayed(tickMinute, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tickMinute);
        data.removeListener(this);
        final java.util.Set<Bitmap> gone = new java.util.HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) gone.add(s.image);
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
        current = incoming = prepared = null;
        slides = 0;
        scene.clearGlass();
        scene.clearFrost();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onData() {
        captionDirty = true;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        pw = Math.round(w * (1 + ZOOM));
        ph = Math.round(h * (1 + ZOOM));
        scene.setSize(w, h);
        face.setPosition(0, 0, w, h);
        caption.setPosition(0, 0, w, h);
        numerals = Ui.numbers(Ui.displayMedium, 44 * k, 0xF2FFFFFF);
        numerals.setTextAlign(Paint.Align.CENTER);
        datePaint = Ui.text(Ui.semibold, 34 * k, 0xF0FFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        weatherPaint = Ui.numbers(Ui.medium, 28 * k, 0xCCFFFFFF);
        weatherPaint.setTextAlign(Paint.Align.CENTER);
        digitsPaint = Ui.numbers(Ui.thin, 300 * k, 0xF5FFFFFF);
        digitsPaint.setTextAlign(Paint.Align.CENTER);
        digitsPaint.setLetterSpacing(-0.01f);
        digitDate = Ui.text(Ui.semibold, 38 * k, 0xD9FFFFFF);
        digitDate.setTextAlign(Paint.Align.CENTER);
        for (Paint p : new Paint[] {datePaint, weatherPaint, digitsPaint, digitDate}) p.setShadowLayer(18 * k, 0, 2 * k, 0x80000000);
        hint.setTextSize(22 * k);
        hint.setTypeface(Ui.medium);
        float r = DISC_R * k;
        disc.set(w / 2f - r, h * 0.44f - r, w / 2f + r, h * 0.44f + r);
        reflection = null;
        faceDirty = captionDirty = true;
        minute = -1;
        if (running && photos && current == null) prepareNext();
    }

    // MARK: - Photos

    private void prepareNext() {
        if (!running || !photos || decoding || prepared != null || keys.isEmpty()) return;
        Slide last = incoming != null ? incoming : current;
        int start = 0;
        if (last != null) start = (keys.indexOf(last.key) + 1) % keys.size();
        final String key = keys.get(start);
        if (last != null && key.equals(last.key)) {
            prepared = new Slide(key, last.image, last.frost, last.glass, slides++);   // one photo: again, with another motion
            return;
        }
        decoding = true;
        final int gen = generation;
        final int tw = pw, th = ph;
        worker.execute(() -> {
            Slide s = null;
            try {
                Bitmap src = Wallpapers.get(getContext()).decode(key);
                if (src != null) {
                    Bitmap b = Backdrop.cropScale(src, tw, th);
                    if (!b.isMutable()) {
                        Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, true);
                        b.recycle();
                        b = copy;
                    }
                    new Canvas(b).drawColor(0x30000000);   // a little darker than on the home screen: it is night
                    int[] frost = Backdrop.frostOf(b), glass = Backdrop.glassOf(b);
                    s = new Slide(key, Backdrop.gpu(b), frost, glass, slides);
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver photo " + key + ": " + e.getMessage());
            }
            final Slide ready = s;
            handler.post(() -> {
                if (gen != generation) {
                    if (ready != null) ready.image.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) {
                    keys.remove(key);
                    prepareNext();
                    return;
                }
                slides++;
                prepared = ready;
                invalidate();
            });
        });
    }

    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            scene.setFrost(current.frost);
            scene.setGlassImage(current.glass);
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && up >= current.startedAt + SHOW_MS) {
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            scene.setGlassImages(current.glass, incoming.glass);
            return;
        }
        if (incoming != null && up >= incoming.startedAt + FADE_MS) {
            Slide old = current;
            current = incoming;
            incoming = null;
            scene.setFrost(current.frost);
            scene.setGlassImage(current.glass);
            if (old.image != current.image) {
                Bitmap gone = old.image;
                handler.postDelayed(gone::recycle, 1000);
            }
            prepareNext();
        }
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        float u = Ui.clamp((up - s.startedAt) / (float) (SHOW_MS + FADE_MS));
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 30 per second

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (frameTimeNanos - lastFrame >= 28_000_000L) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = SystemClock.uptimeMillis();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Clock.digits(getContext(), now);
            date = Clock.date(now);
            captionDirty = true;
        }
        float t = (up - startedAt) / 1000f;
        if (running) advance(up);

        // Background.
        float first = 1;
        if (photos) {
            first = current == null ? 0 : fadeIn(up - firstAt, FIRST_MS);
            if (current == null || first < 1) canvas.drawRect(0, 0, w, h, black);
            if (current != null) {
                place(current, up, placeA);
                float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, FADE_MS);
                if (incoming != null) scene.setGlassMix(e);
                drawPhoto(canvas, current, placeA, first);
                if (incoming != null) {
                    place(incoming, up, placeB);
                    drawPhoto(canvas, incoming, placeB, e);
                }
            }
        } else {
            if (up - lastSky >= scene.stepMillis()) {
                lastSky = up;
                scene.advance(timeOffset + t);
            }
            scene.drawBackground(canvas);
            first = fadeIn(up - startedAt, FIRST_MS);
            if (first < 1) {
                black.setAlpha(Math.round(255 * (1 - first)));
                canvas.drawRect(0, 0, w, h, black);
                black.setAlpha(255);
            }
        }
        if (photos && current == null) {
            if (preview) drawHint(canvas);
            return;
        }

        // The clock drifts slowly; everything on it moves together.
        double phase = 2 * Math.PI * t / DRIFT_PERIOD_S;
        float dx = (float) (DRIFT_X * k * Math.sin(phase)), dy = (float) (DRIFT_Y * k * Math.sin(2 * phase + 0.7));
        // Only the fade-in needs a layer; afterwards everything draws straight to the screen.
        int save = first < 0.996f ? canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * Math.min(1, first))) : canvas.save();
        if (analog) drawAnalog(canvas, now, dx, dy);
        else drawDigital(canvas, dx, dy);
        canvas.restoreToCount(save);
        if (preview) drawHint(canvas);
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) duration);
        return t * t * (3 - 2 * t);
    }

    // MARK: - The glass clock

    private void drawAnalog(Canvas c, long now, float dx, float dy) {
        float r = DISC_R * k, cx = disc.centerX() + dx, cy = disc.centerY() + dy;
        tmp.set(cx - r, cy - r, cx + r, cy + r);
        // Its shadow on the photo, then the glass: the background shows through, blurred, with the lit rim.
        drop.set(tmp);
        drop.offset(0, 22 * k);
        shadow.setColor(0x73000000);
        shadows.draw(c, drop, r, 34 * k, shadow);
        scene.setOffset(0, 0);
        scene.liquid(c, tmp, r, 0xFF18222E, 0.24f, 0.05f, 40 * k, 30 * k, 1f, 1);
        // Reflection: a soft highlight at the top left (the light) and a thin rim that is brightest there, with a
        // faint echo at the bottom right. The gradients sit at the origin, made once; the canvas moves to the disc.
        if (reflection == null) {
            reflection = new RadialGradient(-r * 0.42f, -r * 0.5f, r * 0.95f,
                    new int[] {0x52FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[] {0, 0.42f, 1}, Shader.TileMode.CLAMP);
            rimLight = new SweepGradient(0, 0, new int[] {0x14FFFFFF, 0xC0FFFFFF, 0x1AFFFFFF, 0x55FFFFFF, 0x14FFFFFF}, new float[] {0, 0.62f, 0.78f, 0.9f, 1});
        }
        c.save();
        c.translate(cx, cy);
        fill.setShader(reflection);
        c.drawCircle(0, 0, r, fill);
        fill.setShader(null);
        ring.setStrokeWidth(2 * k);
        ring.setShader(rimLight);
        c.drawCircle(0, 0, r - k, ring);
        ring.setShader(null);
        ring.setColor(0x26000000);
        c.drawCircle(0, 0, r - 3.5f * k, ring);   // the hairline of shadow just inside the rim
        c.restore();
        // Marks and numerals (recorded once).
        if (faceDirty) recordFace(r);
        face.setTranslationX(cx - disc.centerX());
        face.setTranslationY(cy - disc.centerY());
        face.draw(c);
        // Hands. The second hand sweeps: the fraction of the second counts.
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        float sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f;
        float min = cal.get(Calendar.MINUTE) + sec / 60f;
        float hour = (cal.get(Calendar.HOUR) % 12) + min / 60f;
        hand.setColor(0xFFFFFFFF);
        drawHand(c, cx, cy, hour * 30, r * 0.52f, 10 * k, r * 0.1f);
        drawHand(c, cx, cy, min * 6, r * 0.78f, 7 * k, r * 0.1f);
        hand.setColor(ACCENT);
        drawHand(c, cx, cy, sec * 6, r * 0.88f, 2.6f * k, r * 0.2f);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, 8.5f * k, fill);
        fill.setColor(ACCENT);
        c.drawCircle(cx, cy, 4 * k, fill);
        fill.setColor(0xFF1C1C1E);
        c.drawCircle(cx, cy, 1.6f * k, fill);
        // Date and weather, under the disc.
        if (captionDirty) recordCaption();
        caption.setTranslationX(dx);
        caption.setTranslationY(dy);
        caption.draw(c);
    }

    /** A hand from the center: its soft shadow first (offset down and right), then the hand, with a short tail. */
    private void drawHand(Canvas c, float cx, float cy, float degrees, float length, float width, float tail) {
        double a = Math.toRadians(degrees - 90);
        float ex = cx + (float) Math.cos(a) * length, ey = cy + (float) Math.sin(a) * length;
        float tx = cx - (float) Math.cos(a) * tail, ty = cy - (float) Math.sin(a) * tail;
        handShadow.setStrokeWidth(width + 2 * k);
        c.drawLine(tx + 2 * k, ty + 5 * k, ex + 2 * k, ey + 5 * k, handShadow);
        hand.setStrokeWidth(width);
        c.drawLine(tx, ty, ex, ey, hand);
    }

    private void recordFace(float r) {
        float cx = disc.centerX(), cy = disc.centerY();
        Canvas c = face.beginRecording(w, h);
        try {
            for (int i = 0; i < 60; i++) {
                boolean hourMark = i % 5 == 0;
                double a = Math.toRadians(i * 6 - 90);
                float outer = r - 18 * k, inner = outer - (hourMark ? 22 : 10) * k;
                tick.setStrokeWidth((hourMark ? 4 : 2) * k);
                tick.setColor(hourMark ? 0xE6FFFFFF : 0x66FFFFFF);
                c.drawLine(cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                        cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer, tick);
            }
            float nr = r - 78 * k, half = Ui.halfCap(numerals);
            c.drawText("12", cx, cy - nr + half, numerals);
            c.drawText("3", cx + nr, cy + half, numerals);
            c.drawText("6", cx, cy + nr + half, numerals);
            c.drawText("9", cx - nr, cy + half, numerals);
        } finally {
            face.endRecording();
        }
        faceDirty = false;
    }

    private void recordCaption() {
        Canvas c = caption.beginRecording(w, h);
        try {
            float cx = disc.centerX(), base = disc.bottom + 74 * k;
            c.drawText(date, cx, base, datePaint);
            if (data.hasWeather()) {
                HomeData.Weather wx = data.weather;
                weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "    " + HomeData.highLow(wx, "  ");
                String symbol = Symbols.weather(wx.code, wx.isDay);
                float size = 32 * k, sw = Symbols.width(getContext(), symbol, size);
                float tw = weatherPaint.measureText(weatherLine), x = cx - (sw + 12 * k + tw) / 2;
                float wb = base + 46 * k;
                Symbols.draw(getContext(), c, symbol, x + sw / 2, wb - Ui.halfCap(weatherPaint), size, 0xCCFFFFFF);
                weatherPaint.setTextAlign(Paint.Align.LEFT);
                c.drawText(weatherLine, x + sw + 12 * k, wb, weatherPaint);
                weatherPaint.setTextAlign(Paint.Align.CENTER);
            }
        } finally {
            caption.endRecording();
        }
        captionDirty = false;
    }

    // MARK: - The digital clock

    private void drawDigital(Canvas c, float dx, float dy) {
        float cx = w / 2f + dx;
        float cap = Ui.cap(digitsPaint);
        float base = h * 0.5f + cap / 2 - 10 * k + dy;
        c.drawText(date, cx, base - cap - 40 * k, digitDate);
        c.drawText(clock, cx, base, digitsPaint);
        if (data.hasWeather()) {
            HomeData.Weather wx = data.weather;
            String line = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "    " + HomeData.highLow(wx, "  ");
            String symbol = Symbols.weather(wx.code, wx.isDay);
            float size = 34 * k, sw = Symbols.width(getContext(), symbol, size);
            float tw = weatherPaint.measureText(line), x = cx - (sw + 14 * k + tw) / 2, wb = base + 84 * k;
            Symbols.draw(getContext(), c, symbol, x + sw / 2, wb - Ui.halfCap(weatherPaint), size, 0xCCFFFFFF);
            weatherPaint.setTextAlign(Paint.Align.LEFT);
            c.drawText(line, x + sw + 14 * k, wb, weatherPaint);
            weatherPaint.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void drawHint(Canvas c) {
        c.drawText("Press any button to return", w / 2f, h - 54 * k, hint);
    }
}
