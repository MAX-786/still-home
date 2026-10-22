package dev.stillhome.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background (aurora) and glass, using the shaders in Shaders (lifted from Free My TV). The aurora is drawn into a
 * ¼-scale layer, scaled up, and only redrawn when it moves (advance, up to ~30 times per second); in the frames
 * between steps the GPU just reuses the layer. The glass reads the frosted aurora computed in Frost or, with a photo
 * as the background, its blurred version (setFrost / setGlassImage).
 *
 * Without AGSL (Android 8 to 12, this Redmi's Android 11 included) nothing is left blank or flat: the aurora is the
 * same math computed on the CPU in a 96×54 image (Frost, ~5k pixels per step) and scaled up with filtering, and the
 * glass is the blurred background drawn through the rounded shape (a BitmapShader: one texture read per pixel), with
 * the tint, a light sheen from the top and a rim that catches the light at the top left.
 */
final class Scene {
    final Shaders.Palette palette = new Shaders.Palette();
    private RuntimeShader aurora, glass, liquid;
    private final Frost frost = new Frost();
    // Without AGSL the aurora is computed on a worker thread (~25 ms a step on this Redmi's ARMv7 CPU), into one of
    // two images while the GPU may still be reading the other; the result is swapped in on the main thread.
    private static final ExecutorService SKY_WORKER = Ui.worker("aurora");
    private final Handler main = new Handler(Looper.getMainLooper());
    private Frost[] skies, frosts;
    private int skyBuffer;
    private boolean skyBusy, skyWanted = true;
    private float pendingTime = Float.NaN;
    private Runnable onSky;
    private final Paint auroraPaint = new Paint();
    private final Paint glassPaint = new Paint();
    private final Paint liquidPaint = new Paint();
    private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Recording auroraNode = Recording.create("aurora");
    // The layer is drawn at a quarter size and scaled up: filtered, its soft shapes stay soft instead of stepping.
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF screen = new RectF(), inner = new RectF();
    private int w = 1, h = 1, lw = 1, lh = 1;
    private float speed = 1, baseDim, dim, time;
    private float offsetX, offsetY;
    private boolean auroraDirty = true, imageFrost, imageGlass;
    private Bitmap frostImage, glassA, glassB, skyImage;
    private BitmapShader glassShaderA, glassShaderB, frostShader, skyShader;
    private float glassMix;
    private final boolean withGlass;

    Scene() {
        this(true);
    }

    Scene(boolean withGlass) {
        this.withGlass = withGlass;
        try {
            if (Ui.debugFlag("flat") || Ui.LEGACY) throw new UnsupportedOperationException("CPU paths requested");
            aurora = new RuntimeShader(Shaders.AURORA);
            glass = new RuntimeShader(Shaders.GLASS);
            liquid = new RuntimeShader(Shaders.LIQUID);
            auroraPaint.setShader(aurora);
            glassPaint.setShader(glass);
            liquidPaint.setShader(liquid);
        } catch (RuntimeException | LinkageError e) {   // Android 8 to 12: no AGSL, the CPU aurora and plain glass instead
            AppLog.w("shader unavailable, drawing the aurora on the CPU: " + e.getMessage());
            aurora = null;
            glass = null;
            liquid = null;
            // 64×36 on Android 8 to 11 (a 32-bit CPU here: ~35 ms a step instead of ~70), 96×54 when only AGSL is off.
            int sw = Ui.LEGACY ? 64 : 96, sh = Ui.LEGACY ? 36 : 54;
            skies = new Frost[] {new Frost(sw, sh), new Frost(sw, sh)};
            frosts = new Frost[] {new Frost(), new Frost()};
        }
        auroraNode.setUseCompositingLayer(true, layerPaint);
        flatRim.setStyle(Paint.Style.STROKE);
        flatRim.setStrokeWidth(1.5f);
        flatEdge.setStyle(Paint.Style.STROKE);
        flatEdge.setStrokeWidth(1f);
    }

    /** False while a photo is the background: the CPU aurora is not computed at all (the glass reads the photo). */
    void setSkyWanted(boolean wanted) {
        skyWanted = wanted;
        if (wanted && aurora == null && skyImage == null) advance(time);
    }

    /** Called on the main thread when a CPU aurora step is ready to draw (the view invalidates what shows it). */
    void setOnSky(Runnable r) {
        onSky = r;
    }

    /** How often the CPU aurora should step (ms): 5 a second on the older TVs, 10 elsewhere; the motion is slow. */
    int stepMillis() {
        return aurora != null ? 100 : Ui.LEGACY ? 200 : 100;
    }

    /** True with the AGSL shaders (Android 13+); false on the CPU paths. */
    boolean hasShaders() {
        return aurora != null;
    }

    void setSize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        lw = Math.max(1, Math.round(w / 4f));
        lh = Math.max(1, Math.round(h / 4f));
        screen.set(0, 0, w, h);
        auroraNode.setPosition(0, 0, lw, lh);
        if (aurora != null) {
            aurora.setFloatUniform("res", w, h);
            aurora.setFloatUniform("layer", lw, lh);
            setFrostScale();
            if (imageGlass) liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
        }
        push();
    }

    /** speed: 1 on the home screen, less on the screensaver; dim: how much to darken (0 to 1). */
    void setMotion(float speed, float dim) {
        this.speed = speed;
        baseDim = dim;
        push();
    }

    /** Call after changing `palette`. Bright palettes are dimmed a little so white text stays readable. */
    void applyPalette() {
        push();
    }

    private void push() {
        dim = Math.min(0.6f, baseDim + Math.max(0, Math.min(0.22f, (palette.brightest() - 0.45f) * 0.5f)));
        if (aurora != null) {
            aurora.setFloatUniform("speed", speed);
            aurora.setFloatUniform("dim", dim);
            palette.apply(aurora);
        }
        advance(time);
    }

    /** Moves the aurora to `seconds`: redraws the layer on the next draw, recomputes the glass's frosted aurora now. */
    void advance(float seconds) {
        time = seconds;
        if (aurora == null) {
            renderSkyLater(seconds);
            return;
        }
        aurora.setFloatUniform("t", seconds);
        if (withGlass && (!imageFrost || !imageGlass)) {
            BitmapShader s = frost.render(seconds, speed, dim, palette.c, (float) w / h);
            if (!imageFrost) glass.setInputShader("frost", s);
            if (!imageGlass) {
                liquid.setInputShader("imageA", s);
                liquid.setInputShader("imageB", s);
                liquid.setFloatUniform("mixB", 0);
                liquid.setFloatUniform("imageScale", (float) Frost.W / w, (float) Frost.H / h);
            }
        }
        auroraDirty = true;
    }

    /** CPU paths: the sky and, unless a photo is in, the frosted copy the glass reads, computed off the main thread. */
    private void renderSkyLater(float seconds) {
        if (!skyWanted) return;
        if (skyBusy) {
            pendingTime = seconds;   // the latest time wins; one step at a time
            return;
        }
        skyBusy = true;
        pendingTime = Float.NaN;
        final int buffer = skyBuffer = 1 - skyBuffer;
        final float sp = speed, dm = dim, aspect = (float) w / h;
        final float[] colors = palette.c.clone();
        final boolean needFrost = withGlass && !imageFrost;
        SKY_WORKER.execute(() -> {
            long t0 = android.os.SystemClock.uptimeMillis();
            final BitmapShader skyS = skies[buffer].render(seconds, sp, dm, colors, aspect);
            final BitmapShader frostS = needFrost ? frosts[buffer].render(seconds, sp, dm, colors, aspect) : null;
            long ms = android.os.SystemClock.uptimeMillis() - t0;
            if (ms > 40) AppLog.w("cpu aurora step " + ms + " ms");
            main.post(() -> {
                skyShader = skyS;
                skyImage = skies[buffer].bitmap();
                if (frostS != null) frostShader = frostS;
                if (withGlass && !imageGlass) {
                    glassShaderA = frostShader != null ? frostShader : skyShader;
                    glassShaderB = glassShaderA;
                    glassMix = 0;
                }
                auroraDirty = true;
                skyBusy = false;
                if (onSky != null) onSky.run();
                if (!Float.isNaN(pendingTime)) renderSkyLater(pendingTime);
            });
        });
    }

    /** The liquid glass now shows this photo (Backdrop.GW × GH, slightly blurred). */
    void setGlassImage(int[] pixels) {
        setGlassImages(pixels, null);
    }

    /** Photo transition: both photos in the glass, blended by setGlassMix (0 = the first one). */
    void setGlassImages(int[] from, int[] to) {
        imageGlass = true;
        glassA = fill(glassA, from);
        if (to != null) glassB = fill(glassB, to);
        glassShaderA = linear(glassA);
        glassShaderB = to != null ? linear(glassB) : glassShaderA;
        glassMix = 0;
        if (liquid == null) return;
        liquid.setInputShader("imageA", glassShaderA);
        liquid.setInputShader("imageB", glassShaderB);
        liquid.setFloatUniform("mixB", 0);
        liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
    }

    void setGlassMix(float t) {
        glassMix = Math.max(0, Math.min(1, t));
        if (liquid != null) liquid.setFloatUniform("mixB", glassMix);
    }

    /** The liquid glass goes back to the frosted aurora. */
    void clearGlass() {
        if (!imageGlass) return;
        imageGlass = false;
        advance(time);
    }

    private static Bitmap fill(Bitmap b, int[] pixels) {
        if (b == null) b = Bitmap.createBitmap(Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888);
        b.setPixels(pixels, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
        return b;
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        return Frost.filter(s, BitmapShader.FILTER_MODE_LINEAR);
    }

    /**
     * Liquid Glass in rectangle `r` (screen coordinates). band and refraction in px (the thick rim and how much it
     * bends the image), specular = rim highlight, alpha = opacity of the whole glass.
     */
    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band, float refraction,
                float specular, float alpha) {
        if (alpha <= 0.004f) return;
        if (liquid == null) {
            flat(c, r, radius, tint, tintAlpha, lift, specular, alpha, true);
            return;
        }
        liquid.setFloatUniform("center", r.centerX(), r.centerY());
        liquid.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
        liquid.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
        liquid.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        liquid.setFloatUniform("lift", lift);
        liquid.setFloatUniform("band", Math.max(1, band));
        liquid.setFloatUniform("refraction", refraction);
        liquid.setFloatUniform("specular", specular);
        liquidPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, liquidPaint);
        liquidPaint.setAlpha(255);
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    void setFrost(int[] pixels) {
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostImage.setPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        imageFrost = true;
        frostShader = linear(frostImage);
        if (glass == null) return;
        glass.setInputShader("frost", frostShader);
        setFrostScale();
    }

    /** Back to the frosted aurora. */
    void clearFrost() {
        if (!imageFrost) return;
        imageFrost = false;
        setFrostScale();
        advance(time);
    }

    /** The current blurred image (so the bottom drawer can blur the background), or null with the aurora. */
    Bitmap frostImage() {
        return imageFrost ? frostImage : null;
    }

    /** Offset (px) of the layer that will draw glass, if it was moved on screen without being redrawn. */
    void setOffset(float x, float y) {
        offsetX = x;
        offsetY = y;
        if (glass != null) glass.setFloatUniform("offset", x, y);
        if (liquid != null) liquid.setFloatUniform("offset", x, y);
    }

    private void setFrostScale() {
        if (glass == null) return;
        if (imageFrost) glass.setFloatUniform("frostScale", (float) Backdrop.FW / w, (float) Backdrop.FH / h);
        else glass.setFloatUniform("frostScale", (float) Frost.W / w, (float) Frost.H / h);
    }

    void drawBackground(Canvas c) {
        if (aurora == null) {
            if (skyImage == null) {
                // The first step is still being computed: the palette's base color until it lands.
                c.drawColor(palette.color(0));
                return;
            }
            c.drawBitmap(skyImage, null, screen, fallback);
            return;
        }
        if (auroraDirty) {
            Canvas rc = auroraNode.beginRecording(lw, lh);
            try {
                rc.drawPaint(auroraPaint);
            } finally {
                auroraNode.endRecording();
            }
            auroraDirty = false;
        }
        c.save();
        c.scale((float) w / lw, (float) h / lh);
        auroraNode.draw(c);
        c.restore();
    }

    /**
     * Glass in rectangle `r` (screen coordinates, no canvas transform: the shader uses the real position).
     * body = 1: full glass; body = 0: just the light rim, on top of something already drawn.
     */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (glass == null) {
            if (body > 0) flat(c, r, radius, tint, tintAlpha, lift, 0.7f, alpha, false);
            else rimOnly(c, r, radius, alpha);
            return;
        }
        float hw = r.width() / 2, hh = r.height() / 2, inner = Math.min(hw, hh);
        glass.setFloatUniform("center", r.centerX(), r.centerY());
        glass.setFloatUniform("halfSize", hw, hh);
        glass.setFloatUniform("invHalf", 1 / Math.max(hw, 1), 1 / Math.max(hh, 1));
        glass.setFloatUniform("radius", radius);
        glass.setFloatUniform("top", r.top);
        glass.setFloatUniform("depth", 1 / Math.max(inner * 0.6f, 1), Math.min(24, inner * 0.5f), 1 / Math.max(inner * 0.9f, 1), 1 / Math.max(r.height(), 1));
        glass.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        glass.setFloatUniform("glow", Color.red(glow) / 255f, Color.green(glow) / 255f, Color.blue(glow) / 255f, glowAlpha);
        glass.setFloatUniform("lift", lift);
        glass.setFloatUniform("body", body);
        glassPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, glassPaint);
        glassPaint.setAlpha(255);
    }

    // MARK: - Without AGSL

    /**
     * Frosted glass without a shader: the blurred background (the frosted aurora, or the photo's blurred copy) through
     * the rounded shape, then the tint, a sheen fading from the top, a thin bright rim at the top-left edge (the light)
     * and a darker one at the bottom right. `clear`: the lightly blurred photo (liquid glass) instead of the frosted one.
     */
    private void flat(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float specular, float alpha, boolean clear) {
        int save = alpha < 0.996f ? c.saveLayerAlpha(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2, Math.round(255 * alpha)) : c.save();
        BitmapShader source = clear ? glassShaderA : frostShader != null ? frostShader : skyShader;
        if (source == null) source = skyShader;
        if (source != null) {
            Bitmap b = clear ? (glassA != null ? glassA : frosts[1 - skyBuffer].bitmap())
                    : frostShader != null ? (imageFrost ? frostImage : frosts[1 - skyBuffer].bitmap()) : skyImage;
            if (b == null) b = frosts[0].bitmap();
            matrix.setScale((float) w / b.getWidth(), (float) h / b.getHeight());
            matrix.postTranslate(-offsetX, -offsetY);
            source.setLocalMatrix(matrix);
            flatFill.setShader(source);
            flatFill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, radius, radius, flatFill);
            if (clear && glassShaderB != null && glassShaderB != glassShaderA && glassMix > 0.004f) {
                glassShaderB.setLocalMatrix(matrix);
                flatFill.setShader(glassShaderB);
                flatFill.setAlpha(Math.round(255 * glassMix));
                c.drawRoundRect(r, radius, radius, flatFill);
                flatFill.setAlpha(255);
            }
            flatFill.setShader(null);
        }
        // The smoky (or white) tint, with a little lift for the focused state.
        flatFill.setColor(Ui.alpha(tint | 0xFF000000, Math.min(1, tintAlpha + lift)));
        c.drawRoundRect(r, radius, radius, flatFill);
        // Sheen: light from the top, gone by the middle; a faint darkening at the bottom gives the slab its depth.
        flatSheen.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom,
                new int[] {Ui.alpha(0xFFFFFFFF, 0.16f + 0.5f * lift), Ui.alpha(0xFFFFFFFF, 0.03f), 0, Ui.alpha(0xFF000000, 0.12f)},
                new float[] {0, 0.42f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, flatSheen);
        flatSheen.setShader(null);
        rim(c, r, radius, specular);
        c.restoreToCount(save);
    }

    /** Only the rim (body = 0): on top of something already drawn. */
    private void rimOnly(Canvas c, RectF r, float radius, float alpha) {
        int save = alpha < 0.996f ? c.saveLayerAlpha(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2, Math.round(255 * alpha)) : c.save();
        rim(c, r, radius, 0.7f);
        c.restoreToCount(save);
    }

    /** The light catches the top-left edge; the opposite edge gets a faint echo (the rim of the real shader). */
    private void rim(Canvas c, RectF r, float radius, float specular) {
        flatRim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[] {Ui.alpha(0xFFFFFFFF, 0.9f * specular), Ui.alpha(0xFFFFFFFF, 0.28f * specular), Ui.alpha(0xFFFFFFFF, 0.08f * specular), Ui.alpha(0xFFFFFFFF, 0.4f * specular)},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        inner.set(r);
        inner.inset(0.75f, 0.75f);
        c.drawRoundRect(inner, Math.max(0, radius - 0.75f), Math.max(0, radius - 0.75f), flatRim);
        flatRim.setShader(null);
        // A hairline of shadow just inside the rim: the glass reads as a slab, not a sticker.
        flatEdge.setColor(Ui.alpha(0xFF000000, 0.18f));
        inner.inset(1.5f, 1.5f);
        c.drawRoundRect(inner, Math.max(0, radius - 2.25f), Math.max(0, radius - 2.25f), flatEdge);
    }
}
