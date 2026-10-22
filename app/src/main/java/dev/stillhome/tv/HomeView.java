package dev.stillhome.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Still Home screen, drawn by hand on one Canvas (the way Free My TV draws its home screen):
 *  - the wordmark top left and three glass pills top right (Apps, TV settings, Launcher settings);
 *  - the thin clock, the date and the weather for your city;
 *  - the Favorites shelf: a glass slab with the pinned apps (icon and name), the focused one rising a little;
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, the next hours' weather,
 *    sunrise and sunset); ↑ or Back closes it;
 *  - Apps (or ↑ past the shelf): the "All apps" sheet, 16:9 cards; hold OK on one to pin it to or unpin it from
 *    the shelf; hold OK on the shelf to move an app.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU
 * only redraws what changed; nothing is redrawn while nothing moves.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Wallpapers.Listener {
    private static final float MARGIN = 72, CONTENT_R = 1848;
    private static final float BRAND_BASE = 102;
    // Top right: icon-only glass discs; the focused one slides its name out to the right of the icon and turns white.
    private static final float BUTTON_H = 64, BUTTON_CY = 92, BUTTON_GAP = 16, BUTTON_ICON = 27, LABEL_GAP = 10, LABEL_PAD = 24;
    private static final int NETWORK = 9;   // the network chip's kind (opens the TV's network settings)
    private static final float CLOCK_BASE = 332, DATE_BASE = 396, WEATHER_BASE = 452;
    private static final float PLATTER_TOP = 626, PLATTER_BOTTOM = 984, PLATTER_PAD = 36, PLATTER_RADIUS = 36;
    private static final float HEAD_BASE = 684, TILE_TOP = 724, TILE_W = 243, TILE_H = 196, TILE_GAP = 34, TILE_RADIUS = 26;
    private static final float ICON = 86, FOCUS_GROW = 0.12f;
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF0B1620, INK = 0xFF1C1C1E, WHITE = 0xFFF5F7FA, MUTED = 0xFFC2C9D2, ACCENT = 0xFFA8B0FB;
    private static final long TOAST_MS = 6000, FADE_MS = 800, HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30, LIFT_GROW = 0.06f, WIGGLE_DEG = 1.5f;
    private static final int SKY = 0, TOP = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, NAMAZ = 8;
    // The namaz card, opposite the clock: the emblem, the next time, then the day in three columns.
    private static final float NAMAZ_L = 1176, NAMAZ_T = 156, NAMAZ_PAD = 34, NAMAZ_RADIUS = 30, EMBLEM_H = 92;
    private static final float NAMAZ_ROW = 52;
    private static final int Z_STATUS = 0, Z_SHELF = 2, Z_DRAWER = 3, WIDGETS = 8, GRID = 9;
    private static final ExecutorService LOADER = Ui.worker("lists");

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Wallpapers wallpapers;
    private Click click;   // made just after the first frame (SoundPool setup is slow on an old TV)
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TvInputManager tvInputs;   // fetched on the loader thread: creating it talks to the TV input service (~150 ms here)
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, topLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, namazLayer;
    private int zone = Z_SHELF;

    // Top: the three pills
    private final List<HomeApps.Item> statusItems = new ArrayList<>();
    private final Spring[] statusFocus = new Spring[4];
    private final RectF[] statusRects = new RectF[4];   // resting discs; the focused one grows leftwards (drawn rects in statusDrawn)
    private final RectF[] statusDrawn = new RectF[4];
    private final float[] statusExtra = new float[4];  // how much wider each pill is with its name out
    private int statusIndex;
    private final NetworkStatus network;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    private String shelfHint = "";

    // Moving apps on the shelf: hold OK, ← → move, OK drops, Back cancels (lifted from Free My TV).
    private boolean reordering, reorderAnim, okDown, okHeld, holdArmed, backDown;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (okDown && holdArmed) hold(); };

    // Background
    private Backdrop bgFrom, bgTo;     // null = the aurora
    private long bgFadeAt;
    private boolean auroraLive;
    private long lastSky;
    private final float timeOffset = (float) (Math.random() * 300);
    private long startedAt;

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex, widgetIndex;

    // "All apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, othersWanted;
    private int gridIndex;
    private final Runnable loadOthers = () -> { othersWanted = true; reload(); };

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, topDirty, drawerDirty, gridDirty, namazDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private long clockMinute = -1;
    private int prefsSeen = -1;

    private Paint brandPaint, clockPaint, datePaint, weatherPaint, buttonText, headPaint, hintPaint, tileLabel;
    private Paint nLabel, nName, nTime, nUntil, nGroup, nRow, nRowTime;
    private Bitmap emblem;
    private long namazMinute = -1;
    private List<Prayer.Time> namazDay;
    private Prayer.Time namazNext;
    private String[] namazText = new String[0];
    private String namazNextTime = "", namazUntil = "";
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, bigTemp, hourPaint, hourTemp, sheetTitle, emptyPaint;
    private TextPaint gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF(), gridCell = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private LinearGradient cardSheen, tileSheen;
    private float cardSheenH, tileSheenH;
    private ColorFilter veilFilter;
    private float veilAmount = -1;
    private HomeData.Weather textWeather;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText;
    private final String[] hourText = new String[6], hourTempText = new String[6];

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (clockPaint == null) return;
            long t0 = SystemClock.uptimeMillis();
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top pills are drawn as if resting in place (they only show there).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case TOP: drawTop(c); break;
                case STATUS: drawButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case NAMAZ: drawNamaz(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
            long ms = SystemClock.uptimeMillis() - t0;
            if (ms > 12) AppLog.w("layer " + kind + " drew in " + ms + " ms");
        }
    }

    private final Runnable reloadSoon = this::reload;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            updatePalette();
            handler.postDelayed(this, 30_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        long t0 = SystemClock.uptimeMillis();
        activity = a;
        data = HomeData.get(a);
        wallpapers = Wallpapers.get(a);
        handler.post(() -> click = new Click(a));
        setFocusable(true);
        sky = new Layer(a, SKY);
        topLayer = new Layer(a, TOP);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        namazLayer = new Layer(a, NAMAZ);
        // Hardware layers on the GPU: only the one that changed is redrawn. Not on Android 8 to 11 (this Redmi): on
        // older GPUs redrawing and compositing big layers every frame costs more than drawing straight to the screen.
        for (View v : new View[] {topLayer, status, namazLayer, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, topLayer, namazLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v);
        }
        setContentDescription("Still Home");
        drawerLayer.setVisibility(INVISIBLE);
        sheetLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < 4; i++) {
            statusFocus[i] = new Spring(0.26f, 0.85f, 0);
            statusRects[i] = new RectF();
            statusDrawn[i] = new RectF();
        }
        network = new NetworkStatus(a);
        statusItems.add(HomeApps.action(NETWORK, "network", network.label(), "network"));
        statusItems.add(HomeApps.action(HomeApps.ALL_APPS, "all", "All apps", "apps"));
        statusItems.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", "TV settings", "tv_settings"));
        statusItems.add(HomeApps.action(HomeApps.SETTINGS, "settings", "Launcher settings", "settings"));
        scene.setSkyWanted(Prefs.wallpaper().equals(Prefs.AURORA));
        scene.setMotion(0.6f, 0.05f);
        scene.setOnSky(() -> {
            sky.invalidate();
            platter.invalidate();
            status.invalidate();
            if (drawerLayer.getVisibility() == VISIBLE) drawerLayer.invalidate();
        });
        updatePalette();
        startedAt = SystemClock.uptimeMillis();
        long t1 = SystemClock.uptimeMillis();
        // First time: right away, with the icons saved by an earlier start (a cold start opens with the apps
        // already there); the rest, and the drawer's inputs, come from reload() in the background.
        setShelf(HomeApps.shelf(a, false));
        prefsSeen = Prefs.version;
        long t2 = SystemClock.uptimeMillis();
        AppLog.w("home view built in " + (t2 - t0) + " ms (scene " + (t1 - t0) + ", shelf " + (t2 - t1) + ")");
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        wallpapers.addListener(this);
        data.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        network.start(() -> {
            statusItems.get(0).label = network.label();
            layoutStatus();
            status.invalidate();
            wake();
        });
        handler.postDelayed(tick, 30_000);
        // The All apps cards (16 banners to decode) come a little later, once the home screen is calm.
        if (!othersWanted) handler.postDelayed(loadOthers, 2500);
        if (prefsSeen != Prefs.version) {
            prefsSeen = Prefs.version;
            data.settingsChanged();
            data.refreshIfStale();
            weatherLineAt = -1;
            namazMinute = -1;
            namazDirty = true;
        }
        reload();
        updatePalette();
        applyWallpaper(false);
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(loadOthers);
        network.stop();
        data.removeListener(this);
        wallpapers.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (click != null) click.release();
    }

    private void clickMove() {
        if (click != null) click.move();
    }

    private void clickOpen() {
        if (click != null) click.open();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        retarget(!animated);
        motionDirty = true;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        topDirty = drawerDirty = true;
        weatherLineAt = -1;
    }

    @Override public void onWallpaper() {
        wake();
        applyWallpaper(true);
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, topLayer, namazLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(topLayer, Math.round(1100 * kk), Math.round(520 * kk));
        measure(status, Math.round(1100 * kk), Math.round(200 * kk));
        measure(platter, width, Math.round(430 * kk));
        measure(tiles, width, Math.round(330 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(300 * kk));
        measure(namazLayer, Math.round((1920 - NAMAZ_L + 40) * kk), Math.round(440 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(topLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(580 * kk));
        place(tiles, 0, Math.round(690 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        place(namazLayer, width - namazLayer.getMeasuredWidth(), Math.round((NAMAZ_T - 20) * kk));
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutStatus();
        layoutShelf();
        layoutDrawer();
        applyWallpaper(false);
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        brandPaint = Ui.text(Ui.displaySemibold, 28 * k, WHITE);
        brandPaint.setLetterSpacing(0.01f);
        clockPaint = Ui.numbers(Ui.thin, 152 * k, WHITE);
        clockPaint.setLetterSpacing(-0.015f);
        datePaint = Ui.text(Ui.regular, 29 * k, MUTED);
        weatherPaint = Ui.text(Ui.medium, 27 * k, 0xE6FFFFFF);
        for (Paint p : new Paint[] {brandPaint, clockPaint, datePaint, weatherPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x66000000);
        buttonText = Ui.text(Ui.semibold, 21 * k, WHITE);
        headPaint = Ui.text(Ui.displaySemibold, 27 * k, WHITE);
        hintPaint = Ui.text(Ui.regular, 17 * k, 0xB3C9D1DB);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        tileLabel = Ui.text(Ui.semibold, 20 * k, WHITE);
        tileLabel.setTextAlign(Paint.Align.CENTER);
        emptyPaint = Ui.text(Ui.regular, 24 * k, MUTED);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        nLabel = Ui.text(Ui.semibold, 17 * k, 0xA6FFFFFF);
        nLabel.setLetterSpacing(0.12f);
        nName = Ui.text(Ui.displaySemibold, 34 * k, WHITE);
        nTime = Ui.numbers(Ui.displayMedium, 44 * k, WHITE);
        nTime.setTextAlign(Paint.Align.RIGHT);
        nUntil = Ui.text(Ui.medium, 20 * k, 0xB8FFFFFF);
        nUntil.setTextAlign(Paint.Align.RIGHT);
        nGroup = Ui.text(Ui.semibold, 16 * k, 0x8CFFFFFF);
        nGroup.setLetterSpacing(0.1f);
        nRow = Ui.text(Ui.medium, 16 * k, 0xA6FFFFFF);
        nRowTime = Ui.numbers(Ui.medium, 23 * k, WHITE);
        for (Paint p : new Paint[] {nName, nTime}) p.setShadowLayer(14 * k, 0, 1.5f * k, 0x4D000000);
        emblem = null;
        namazMinute = -1;
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        cardSheen = tileSheen = null;
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long t0 = SystemClock.uptimeMillis();
        frame(frameTimeNanos);
        long ms = SystemClock.uptimeMillis() - t0;
        if (ms > 24) AppLog.w("frame step took " + ms + " ms");
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        long t0 = SystemClock.uptimeMillis();
        super.dispatchDraw(canvas);
        long ms = SystemClock.uptimeMillis() - t0;
        if (ms > 24) AppLog.w("draw pass took " + ms + " ms");
    }

    private void frame(long frameTimeNanos) {
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean skyMoving = tickAurora(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: the glass shelf is redrawn once it stops, so
        // the glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (topDirty || bgFading) topLayer.invalidate();
        if (namazDirty || bgFading) namazLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading || skyMoving) sky.invalidate();
        if (bgFading || skyMoving || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        if (skyMoving && !drawerMoving) {
            // The aurora moved under the glass: the pills and the drawer read it too (cheap on the CPU path).
            status.invalidate();
            namazLayer.invalidate();
            if (drawerLayer.getVisibility() == VISIBLE) drawerLayer.invalidate();
        }
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = topDirty = drawerDirty = gridDirty = motionDirty = namazDirty = false;
        if (updateClock()) {
            topLayer.invalidate();
            namazLayer.invalidate();
        }

        boolean busy = tilesMoving || statusMoving || drawerItemsMoving || gridMoving || drawerMoving || sheetMoving
                || toastFading || bgFading || sheetOpen || sheet.value > 0.001f || drawerOpen || drawer.value > 0.001f
                || toast != null || !toasts.isEmpty() || auroraLive;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(topLayer, top, lift * 0.6f);
        show(status, top, lift * 0.6f);
        show(namazLayer, top, lift * 0.6f);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Clock.time(getContext(), now);
        dateText = Clock.date(now);
        return true;
    }

    /** The aurora's colors follow the sun at your location. */
    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    /** With the aurora background on, it moves ~10 times a second (every frame redraws only the small sky image). */
    private boolean tickAurora(long up) {
        if (!auroraLive) return false;
        if (up - lastSky >= scene.stepMillis()) {
            lastSky = up;
            scene.advance(timeOffset + (up - startedAt) / 1000f);
            return true;
        }
        return false;
    }

    // MARK: - Background

    /** Reads the wallpaper setting: a photo (cross-faded in once it is ready) or the aurora. */
    private void applyWallpaper(boolean animate) {
        if (clockPaint == null) return;
        String key = Prefs.wallpaper();
        scene.setSkyWanted(key.equals(Prefs.AURORA));
        if (key.equals(Prefs.AURORA)) {
            auroraLive = Prefs.liveAurora();
            showBackdrop(null, animate);
            return;
        }
        auroraLive = false;
        Backdrop b = wallpapers.backdrop(key);
        if (b != null) showBackdrop(b, animate);
        else if (bgTo == null) { sky.invalidate(); platter.invalidate(); }
    }

    /** Swaps the background with a cross-fade (from one photo to another, from the aurora to a photo, or back). */
    private void showBackdrop(Backdrop b, boolean animate) {
        if (b == bgTo && (bgFadeAt != 0 || bgFrom == null)) {
            if (b == null && !animate) { scene.clearFrost(); scene.clearGlass(); }
            return;
        }
        long up = SystemClock.uptimeMillis();
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = animate ? up : 0;
        if (b == null) {
            if (!animate) {
                scene.clearFrost();
                scene.clearGlass();
                bgFrom = null;
            }
        } else if (bgFrom == null || !animate) {
            scene.setFrost(b.frost);
            scene.setGlassImage(b.glass);
            if (!animate) bgFrom = null;
        } else {
            scene.setGlassImages(bgFrom.glass, b.glass);
        }
        topDirty = statusDirty = tilesDirty = drawerDirty = namazDirty = true;
        sky.invalidate();
        platter.invalidate();
        wake();
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null) scene.setGlassMix(e);
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                scene.clearFrost();
                scene.clearGlass();
            } else {
                scene.setFrost(bgTo.frost);
                scene.setGlassImage(bgTo.glass);
            }
            statusDirty = drawerDirty = namazDirty = true;
            platter.invalidate();
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        float t = Ui.clamp((up - start) / (float) FADE_MS);
        return t * t * (3 - 2 * t);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private ColorFilter veil(float amount) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount) return veilFilter;
        veilAmount = amount;
        float keep = 1 - amount;
        return veilFilter = new ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, 0,
            0, keep, 0, 0, 0,
            0, 0, keep, 0, 0,
            0, 0, 0, 1, 0});
    }

    private void drawSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        ColorFilter v = veil(0.42f * Ui.clamp(drawer.value));
        if (bgTo == null || (fading && bgFrom == null)) {
            // The aurora, dimmed a little while the drawer is open.
            scene.drawBackground(c);
            if (v != null) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFF000000, 0.42f * Ui.clamp(drawer.value)));
                c.drawRect(screen, fill);
            }
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawImage(c, bgFrom.image, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawImage(c, bgTo.image, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Top: wordmark, clock, date, weather

    private void drawTop(Canvas c) {
        float x = MARGIN * k;
        c.drawText("Still Home", x - Ui.leftBearing(brandPaint, "Still Home"), BRAND_BASE * k, brandPaint);
        c.drawText(clockText, x - Ui.leftBearing(clockPaint, clockText), CLOCK_BASE * k, clockPaint);
        c.drawText(dateText, x - Ui.leftBearing(datePaint, dateText), DATE_BASE * k, datePaint);
        if (!data.hasWeather()) {
            if (Prefs.weather() && !Prefs.hasCity()) {
                hintPaint.setTextAlign(Paint.Align.LEFT);
                c.drawText("Set your city in Launcher settings for the weather", x, WEATHER_BASE * k, hintPaint);
                hintPaint.setTextAlign(Paint.Align.RIGHT);
            }
            return;
        }
        HomeData.Weather wx = data.weather;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "    " + HomeData.highLow(wx, "  ");
        }
        float size = 34 * k, base = WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(weatherPaint), size, 0xF2FFFFFF);
        c.drawText(weatherLine, x + sw + 14 * k, base, weatherPaint);
    }

    // MARK: - Namaz times (opposite the clock)

    /** The day's times, the next one and their text, made again once a minute. */
    private void refreshNamaz(long now) {
        long minute = now / 60_000;
        if (minute == namazMinute) return;
        namazMinute = minute;
        namazDay = null;
        if (!Prefs.hasCity()) return;
        try {
            namazDay = Prayer.day(now, Double.parseDouble(Prefs.latitude()), Double.parseDouble(Prefs.longitude()));
        } catch (NumberFormatException e) {
            namazDay = null;
        }
        if (namazDay == null) return;
        namazNext = Prayer.next(namazDay, now);
        namazText = new String[namazDay.size()];
        for (int i = 0; i < namazText.length; i++) namazText[i] = Clock.time(getContext(), namazDay.get(i).at);
        namazNextTime = Clock.time(getContext(), namazNext.at);
        namazUntil = Prayer.until(namazNext.at, now);
    }

    private Bitmap emblem() {
        if (emblem != null) return emblem;
        Bitmap src = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.namaz_emblem);
        if (src == null) return null;
        int h = Math.max(1, Math.round(EMBLEM_H * k)), w = Math.max(1, Math.round(src.getWidth() * (float) h / src.getHeight()));
        emblem = Bitmap.createScaledBitmap(src, w, h, true);
        if (emblem != src) src.recycle();
        return emblem;
    }

    /**
     * Smoked glass card: the emblem, "UP NEXT" with the coming time's name, its hour and how long until it; under a
     * hairline, the whole day in three columns (Morning, Afternoon, Evening). The coming row is in the accent color,
     * the ones already past are dimmed.
     */
    private void drawNamaz(Canvas c) {
        if (!Prefs.namaz()) return;
        long now = System.currentTimeMillis();
        refreshNamaz(now);
        float left = NAMAZ_L * k, top = NAMAZ_T * k, right = CONTENT_R * k, pad = NAMAZ_PAD * k, radius = NAMAZ_RADIUS * k;
        if (namazDay == null) {
            tmp.set(left, top, right, top + 112 * k);
            drawerGlass(c, tmp, radius, 10 * k);
            Bitmap e = emblem();
            float x = left + pad;
            if (e != null) {
                float eh = 60 * k, ew = e.getWidth() * eh / e.getHeight();
                cell.set(x, tmp.centerY() - eh / 2, x + ew, tmp.centerY() + eh / 2);
                c.drawBitmap(e, null, cell, bitmapPaint);
                x += ew + 20 * k;
            }
            c.drawText("Namaz times", x, tmp.centerY() - 6 * k, nName);
            c.drawText("Set your city in Launcher settings", x, tmp.centerY() + 26 * k, nUntil.getTextAlign() == Paint.Align.RIGHT ? leftAligned(nUntil) : nUntil);
            return;
        }
        float colTop = top + 150 * k + 24 * k;
        float bottom = colTop + 30 * k + 3 * NAMAZ_ROW * k + pad - 6 * k;
        tmp.set(left, top, right, bottom);
        drawerGlass(c, tmp, radius, 10 * k);

        // Header: emblem, "UP NEXT", the name; the hour and the countdown at the right.
        float x = left + pad, hy = top + pad;
        Bitmap e = emblem();
        if (e != null) {
            cell.set(x, hy, x + e.getWidth(), hy + e.getHeight());
            c.drawBitmap(e, null, cell, bitmapPaint);
            x += e.getWidth() + 22 * k;
        }
        c.drawText("UP NEXT", x, hy + 22 * k, nLabel);
        c.drawText(namazNext.name, x - Ui.leftBearing(nName, namazNext.name), hy + 66 * k, nName);
        c.drawText(namazNextTime, right - pad, hy + 62 * k, nTime);
        c.drawText(namazUntil, right - pad, hy + 92 * k, nUntil);
        rule.setColor(0x2EFFFFFF);
        c.drawRect(left + pad, top + 150 * k, right - pad, top + 150 * k + k, rule);

        // Three equal columns; each time is its name (small) over the hour, the coming one in the accent color.
        float cw = (right - left - 2 * pad) / 3, cx = left + pad;
        int index = 0;
        for (int g = 0; g < 3; g++) {
            c.drawText(Prayer.GROUPS[g].toUpperCase(Ui.locale()), cx, colTop + 14 * k, nGroup);
            if (g > 0) {
                rule.setColor(0x24FFFFFF);
                c.drawRect(cx - 14 * k, colTop, cx - 14 * k + k, colTop + 30 * k + 3 * NAMAZ_ROW * k - 14 * k, rule);
            }
            int row = 0;
            while (index < namazDay.size() && namazDay.get(index).group == g) {
                Prayer.Time t = namazDay.get(index);
                boolean next = t.name.equals(namazNext.name), past = !next && t.at < now;
                float rowTop = colTop + 30 * k + row * NAMAZ_ROW * k;
                nRow.setColor(next ? ACCENT : Ui.alpha(0xA6FFFFFF, past ? 0.55f : 1));
                nRowTime.setColor(next ? ACCENT : Ui.alpha(WHITE, past ? 0.5f : 1));
                float lx = cx;
                if (next) {
                    fill.setShader(null);
                    fill.setColor(ACCENT);
                    c.drawCircle(cx + 3 * k, rowTop + 14 * k - Ui.halfCap(nRow), 3 * k, fill);
                    lx += 12 * k;
                }
                fitPaint.set(nRow);
                c.drawText(TextUtils.ellipsize(t.name, fitPaint, cw - 20 * k - (lx - cx), TextUtils.TruncateAt.END).toString(), lx, rowTop + 14 * k, nRow);
                c.drawText(namazText[index], cx, rowTop + 42 * k, nRowTime);
                index++;
                row++;
            }
            cx += cw;
        }
        nRow.setColor(0xA6FFFFFF);
        nRowTime.setColor(WHITE);
    }

    private final Paint leftUntil = new Paint();

    private Paint leftAligned(Paint p) {
        leftUntil.set(p);
        leftUntil.setTextAlign(Paint.Align.LEFT);
        return leftUntil;
    }

    // MARK: - Top right: the glass discs (network, All apps, TV settings, Launcher settings)

    private void layoutStatus() {
        if (buttonText == null) return;
        float right = CONTENT_R * k, cy = BUTTON_CY * k, bh = BUTTON_H * k;
        for (int i = statusItems.size() - 1; i >= 0; i--) {
            statusRects[i].set(right - bh, cy - bh / 2, right, cy + bh / 2);
            // With the name out: icon, gap, name, padding; the disc's own padding stays on the left.
            statusExtra[i] = LABEL_GAP * k + buttonText.measureText(statusItems.get(i).label) + LABEL_PAD * k - (bh - BUTTON_ICON * k) / 2;
            right -= bh + BUTTON_GAP * k;
        }
        statusDirty = true;
    }

    /** Where each disc is right now: the focused one is wider (its name is out), and pushes the ones on its left. */
    private void placeStatus() {
        float push = 0;
        for (int i = statusItems.size() - 1; i >= 0; i--) {
            float f = Ui.clamp(statusFocus[i].value);
            RectF r = statusRects[i];
            float extra = statusExtra[i] * f;
            statusDrawn[i].set(r.left - extra - push, r.top, r.right - push, r.bottom);
            push += extra;
        }
    }

    /**
     * Smoked glass discs with only the icon. Focused: the disc grows 8% and rises, turns white with a dark icon, and
     * its name slides out to the right of the icon (the disc widens to the left; the discs on its left move over).
     * The network disc's icon says Wi-Fi or Ethernet, and whether the TV is online.
     */
    private void drawButtons(Canvas c) {
        placeStatus();
        int n = statusItems.size();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                float f = Ui.clamp(statusFocus[i].value);
                if ((pass == 0) == (f > 0.02f)) continue;   // the focused one last, on top of its neighbors
                HomeApps.Item it = statusItems.get(i);
                RectF r = statusDrawn[i];
                float s = 1 + 0.08f * f;
                float cx = r.centerX(), cy = r.centerY() - 2 * k * f;
                cell.set(cx - r.width() * s / 2, cy - r.height() * s / 2, cx + r.width() * s / 2, cy + r.height() * s / 2);
                float rad = cell.height() / 2;
                tmp.set(cell);
                tmp.offset(0, (6 + 8 * f) * k);
                shadow.setColor(Ui.alpha(0xFF000000, 0.28f + 0.22f * f));
                shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
                scene.glass(c, cell, rad, SMOKE, 0.34f, 0, 0, 0.02f, 1, 1 - f);
                if (f > 0.01f) {
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(WHITE, f));
                    c.drawRoundRect(cell, rad, rad, fill);
                    rim.setShader(null);
                    rim.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * f));
                    rim.setStrokeWidth(1.5f * k);
                    tmp.set(cell);
                    tmp.inset(0.75f * k, 0.75f * k);
                    c.drawRoundRect(tmp, rad, rad, rim);
                    rim.setStrokeWidth(2.5f * k);
                }
                float icon = BUTTON_ICON * k * s;
                float ix = cell.left + cell.height() / 2;   // the icon keeps the disc's own center
                int ink = blend(0xF5FFFFFF, INK, f);
                if (it.kind == NETWORK) netIcon(c, ix, cell.centerY(), icon, f);
                else Symbols.draw(getContext(), c, it.symbol, ix, cell.centerY(), icon, ink);
                if (f > 0.01f) {
                    buttonText.setTextSize(21 * k * s);
                    buttonText.setColor(Ui.alpha(INK, f));
                    float tx = ix + icon / 2 + LABEL_GAP * k * s + (1 - f) * 14 * k;   // slides out from the icon
                    c.drawText(it.label, tx, cell.centerY() + Ui.halfCap(buttonText), buttonText);
                    buttonText.setTextSize(21 * k);
                    buttonText.setColor(WHITE);
                }
            }
        }
    }

    private final Paint netPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF netArc = new RectF();

    /**
     * The network icon, drawn in a 24-unit box: Wi-Fi arcs or an Ethernet (LAN) glyph. Online: the icon's normal
     * color. Connected but without internet: amber. Not connected: dim, with a red slash.
     */
    private void netIcon(Canvas c, float cx, float cy, float size, float f) {
        int transport = network.transport;
        boolean online = network.online, connected = transport != NetworkStatus.NONE;
        int normal = blend(0xF5FFFFFF, INK, f);
        int color = !connected ? Ui.alpha(normal, 0.45f) : online ? normal : blend(0xFFFFC266, 0xFFB26A00, f);
        float u = size / 24f;
        c.save();
        c.translate(cx - size / 2, cy - size / 2);
        c.scale(u, u);
        netPaint.setStyle(Paint.Style.STROKE);
        netPaint.setStrokeCap(Paint.Cap.ROUND);
        netPaint.setStrokeJoin(Paint.Join.ROUND);
        netPaint.setStrokeWidth(2f);
        netPaint.setColor(color);
        if (transport == NetworkStatus.ETHERNET) {
            c.drawRoundRect(8.5f, 2.5f, 15.5f, 8f, 1.2f, 1.2f, netPaint);
            c.drawLine(12, 8, 12, 12.5f, netPaint);
            c.drawLine(5, 12.5f, 19, 12.5f, netPaint);
            c.drawLine(5, 12.5f, 5, 15.5f, netPaint);
            c.drawLine(19, 12.5f, 19, 15.5f, netPaint);
            c.drawRoundRect(1.5f, 15.5f, 8.5f, 21f, 1.2f, 1.2f, netPaint);
            c.drawRoundRect(15.5f, 15.5f, 22.5f, 21f, 1.2f, 1.2f, netPaint);
        } else {
            for (float r : new float[] {4.2f, 8.6f, 13f}) {
                netArc.set(12 - r, 19.5f - r, 12 + r, 19.5f + r);
                c.drawArc(netArc, 225, 90, false, netPaint);
            }
            netPaint.setStyle(Paint.Style.FILL);
            c.drawCircle(12, 19.5f, 1.7f, netPaint);
        }
        if (!connected) {
            // The slash: a dark gap under it so it reads on top of the arcs, then the red line.
            netPaint.setStyle(Paint.Style.STROKE);
            netPaint.setStrokeWidth(4.5f);
            netPaint.setColor(blend(SMOKE, WHITE, f));
            c.drawLine(4.5f, 4.5f, 19.5f, 19.5f, netPaint);
            netPaint.setStrokeWidth(2.2f);
            netPaint.setColor(0xFFFF6B6B);
            c.drawLine(4.5f, 4.5f, 19.5f, 19.5f, netPaint);
        }
        c.restore();
    }

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key) && list.get(i).icon == shelf.get(i).icon;
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            if (zone == Z_SHELF && list.isEmpty()) zone = Z_STATUS;
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = PLATTER_PAD * k, gap = TILE_GAP * k;
        platterRect.set(MARGIN * k, PLATTER_TOP * k, CONTENT_R * k, PLATTER_BOTTOM * k);
        float full = platterRect.width() - 2 * pad;
        float tw = Math.min(TILE_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float x = platterRect.left + pad, y = TILE_TOP * k;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + TILE_H * k);
            x += tw + gap;
        }
        shelfHint = n == 0 ? "" : "Hold OK to move  ·  Apps: hold OK to pin";
    }

    /** The shelf's glass slab, its shadow and heading (redrawn only when the background changes). */
    private void drawPlatter(Canvas c) {
        float radius = PLATTER_RADIUS * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(0x66000000);
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        scene.liquid(c, platterRect, radius, SMOKE, 0.5f, 0.02f, 34 * k, 26 * k, 0.9f, 1);
        float x = platterRect.left + PLATTER_PAD * k, base = HEAD_BASE * k;
        c.drawText("Favorites", x - Ui.leftBearing(headPaint, "Favorites"), base, headPaint);
        rule.setColor(ACCENT);
        tmp.set(x, base + 12 * k, x + 40 * k, base + 15 * k);
        c.drawRoundRect(tmp, 1.5f * k, 1.5f * k, rule);
        if (!shelfHint.isEmpty()) c.drawText(shelfHint, platterRect.right - PLATTER_PAD * k, base - 2 * k, hintPaint);
        if (shelf.isEmpty()) {
            c.drawText("Open Apps and hold OK on an app to pin it here.", x, (TILE_TOP + 110) * k, emptyPaint);
        }
    }

    private void drawTiles(Canvas c) {
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * A favorite: a small glass card with the app's icon and name (the Still Home tile). Focused: it grows 12% from
     * the center and rises, its shadow deepens, the glass lifts toward the accent, the rim lights up and the accent
     * underline appears. The card itself has no glass shader (cheap to redraw on every frame of the spring).
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY() - (4 * fc + 6 * l) * k;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (6 + 12 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.2f + 0.3f * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        // Resting: a deep blue-grey card; focused: the lifted blue card of the original design.
        if (tileSheen == null || tileSheenH != bh) {
            tileSheen = new LinearGradient(0, -bh / 2, 0, bh / 2, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            tileSheenH = bh;
        }
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        fill.setShader(null);
        fill.setColor(blend(0xB1222F3D, 0xE04B698E, fc));
        c.drawRoundRect(tmp, r0, r0, fill);
        fill.setColor(blend(0x30101A26, 0x8038547A, fc));
        tmp.top = 0;
        c.drawRoundRect(tmp, r0, r0, fill);   // the lower half darker: the card has a floor
        tmp.top = -bh / 2;
        sheen.setShader(tileSheen);
        sheen.setAlpha(Math.round(255 * (0.7f + 0.3f * fc)));
        c.drawRoundRect(tmp, r0, r0, sheen);
        sheen.setAlpha(255);
        sheen.setShader(null);
        border.setColor(blend(0x48B6C6DA, 0xFF9CB5E6, fc));
        border.setStrokeWidth((1 + 0.5f * fc) * k / s);
        tmp.inset(0.5f * k / s, 0.5f * k / s);
        c.drawRoundRect(tmp, r0, r0, border);
        border.setStrokeWidth(2 * k);
        c.restore();
        // The icon, centered in the upper part; the name below; the accent underline when focused.
        if (it.icon != null) {
            float size = ICON * k * s;
            float ix = cell.centerX() - size / 2, iy = cell.top + (28 * k) * s;
            tmp.set(ix, iy, ix + size, iy + size);
            c.drawBitmap(it.icon, null, tmp, bitmapPaint);
        }
        tileLabel.setTextSize(20 * k * s);
        c.drawText(fitLabel(it, tileLabel, 20 * k, base.width() - 20 * k), cell.centerX(), cell.bottom - 30 * k * s, tileLabel);
        tileLabel.setTextSize(20 * k);
        if (fc > 0.01f) {
            rule.setColor(Ui.alpha(ACCENT, fc));
            tmp.set(cell.centerX() - 14 * k, cell.bottom - 13 * k, cell.centerX() + 14 * k, cell.bottom - 10 * k);
            c.drawRoundRect(tmp, 1.5f * k, 1.5f * k, rule);
        }
    }

    /** The label cut to `room` at the resting text size, measured once per tile width (ellipsizing is slow here). */
    private String fitLabel(HomeApps.Item it, Paint paint, float size, float room) {
        if (it.fit != null && it.fitFor == room) return it.fit;
        float was = paint.getTextSize();
        paint.setTextSize(size);
        fitPaint.set(paint);
        it.fit = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        it.fitFor = room;
        paint.setTextSize(was);
        return it.fit;
    }

    /** `image` stretched over `cell`, with corners of radius `r` (a shader on Android 8 to 11: no clip mask per frame). */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        weatherCard.set(left, CARDS_TOP * k, right, (CARDS_TOP + CARDS_H) * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, drawerRects.length, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, tileRects.length, drawerRects[drawerIndex].centerX());
        if (shelf.isEmpty()) zone = Z_STATUS;
    }

    /** Smoked glass for a drawer card or button. */
    private void drawerGlass(Canvas c, RectF r, float radius, float drop) {
        tmp.set(r);
        tmp.offset(0, drop);
        shadow.setColor(0x4D000000);
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        scene.glass(c, r, radius, SMOKE, 0.42f, 0, 0, 0.02f, 1, 1);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(off ? 0x6BFFFFFF : 0xF2FFFFFF, INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        float room = base.width() - 16 * k;
        String label = fitLabel(it, drawerLabel, 23 * k, room * 1.15f);   // a long name shrinks a little before it is cut
        drawerLabel.setTextSize(23 * k);
        float wide = drawerLabel.measureText(label);
        drawerLabel.setTextSize((wide > room ? Math.max(18 * k, 23 * k * room / wide) : 23 * k) * s);
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your city: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 10 * k);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, weatherKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (!Prefs.hasCity()) {
            c.drawText("Choose your city in Launcher settings → Weather.", x, r.top + 120 * k, cardDim);
            return;
        }
        if (!data.hasWeather()) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, 0xF2FFFFFF);
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(0x24FFFFFF);
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, 0xE6FFFFFF);
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
        String stale = HomeData.staleness(data.weatherAt, 30 * 60_000L);
        if (!stale.isEmpty()) {
            hintPaint.setColor(0x99FFFFFF);
            c.drawText(stale, r.right - 34 * k, r.top + 54 * k, hintPaint);
            hintPaint.setColor(0xB3C9D1DB);
        }
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, 0xB3FFFFFF);
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** What the weather card shows depends on the data, the settings, the language and the minute. */
    private String weatherKey() {
        return data.weatherAt + "|" + Prefs.version + "|" + Ui.language + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: the card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again is slow on an old TV.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, java.util.function.Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot();

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        if (!othersWanted) {
            handler.removeCallbacks(loadOthers);
            loadOthers.run();
        }
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xB3050B12);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText("All apps", w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        List<String> pinned = pinnedPackages();
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            boolean onShelf = others.get(i).pkg != null && pinned.contains(others.get(i).pkg);
            gridLabel.setColor(blend(onShelf ? 0xFFA8B0FB : 0x99FFFFFF, onShelf ? 0xFFC9CEFF : 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
        hintPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("Hold OK to pin or unpin from Favorites  ·  Back to close", w / 2f, h - 46 * k, hintPaint);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
    }

    /** 16:9 sheet card. Focused: it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            sheen.setShader(null);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        // Between the clock and the namaz card, under the pills.
        float toastTop = 150 * k - (1 - a) * 16 * k, cx = 900 * k;
        tmp.set(cx - tw / 2 - padX, toastTop, cx + tw / 2 + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(440 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final TvInputManager tim = tvInputs == null ? (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE) : null;
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = othersWanted ? HomeApps.others(c) : null;
            handler.post(() -> {
                long t0 = SystemClock.uptimeMillis();
                if (tim != null && tvInputs == null) {
                    tvInputs = tim;
                    if (running) tvInputs.registerCallback(inputCallback, handler);
                }
                setShelf(s);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) gridDirty = true;
                long ms = SystemClock.uptimeMillis() - t0;
                if (ms > 8) AppLog.w("lists applied in " + ms + " ms");
            });
        });
    }

    private List<String> pinnedPackages() {
        List<String> out = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.pkg != null) out.add(it.pkg);
        return out;
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here. Held (~0.5 s) on a shelf app: move mode;
                // held on a sheet card: pin or unpin. A timer, since not every remote repeats a held key.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canReorder() || canPin();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    } else if (okDown && e.isLongPress()) {
                        okHeld = true;   // held on something that has no hold action: the release opens nothing
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** OK held: move an app on the shelf, or pin/unpin one in the sheet. */
    private void hold() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (sheetOpen) {
            okHeld = true;
            togglePin();
        } else {
            startReorder();
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF && !shelf.isEmpty()) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        clickMove();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    zone = Z_STATUS;
                    statusIndex = shelf.isEmpty() ? 1 : nearest(statusRects, statusItems.size(), tileRects[shelfIndex].centerX());
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (shelf.isEmpty()) {
                        if (drawerItems.isEmpty()) return;
                        openDrawer();
                    } else {
                        zone = Z_SHELF;
                        shelfIndex = nearest(tileRects, tileRects.length, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS:
                if (dy < 0) zone = Z_DRAWER;
                else return;
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && Prefs.weather()) {
                    zone = WIDGETS;
                    widgetIndex = 0;
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        clickMove();
        if (Ui.debugFlag("keys")) AppLog.w("move " + dx + "," + dy + " -> zone " + zone + " shelf " + shelfIndex + " status " + statusIndex + " drawer " + drawerIndex);
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        clickMove();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_STATUS) {
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        clickOpen();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == NETWORK) {
            openNetworkSettings();
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    /** The TV's network page (Wi-Fi settings, then the wireless page, then Android settings). */
    private void openNetworkSettings() {
        for (String action : new String[] {android.provider.Settings.ACTION_WIFI_SETTINGS,
                android.provider.Settings.ACTION_WIRELESS_SETTINGS, android.provider.Settings.ACTION_SETTINGS}) {
            try {
                activity.startActivity(new android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (android.content.ActivityNotFoundException | SecurityException ignored) {
            }
        }
        showToast("Network settings are unavailable on this TV.");
    }

    // MARK: - Pinning (hold OK in the sheet)

    private boolean canPin() {
        return sheetOpen && gridIndex < others.size() && others.get(gridIndex).kind == HomeApps.APP && others.get(gridIndex).pkg != null;
    }

    private void togglePin() {
        if (!canPin()) return;
        HomeApps.Item it = others.get(gridIndex);
        List<String> order = new ArrayList<>(HomeApps.shelfPackages(getContext()));
        boolean pinned = order.contains(it.pkg);
        if (pinned) {
            order.remove(it.pkg);
        } else if (order.size() >= HomeApps.MAX_SHELF) {
            showToast("Favorites is full (" + HomeApps.MAX_SHELF + " apps). Unpin one first.");
            return;
        } else {
            order.add(it.pkg);
        }
        Prefs.setShelf(order);
        prefsSeen = Prefs.version;
        clickOpen();
        showToast(it.label + (pinned ? " unpinned from Favorites" : " pinned to Favorites"));
        gridDirty = true;
        reload();
    }

    // MARK: - Moving apps on the shelf (lifted from Free My TV)

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        clickOpen();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);
    }

    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        clickMove();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        clickMove();
    }

    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        clickOpen();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> { Prefs.setShelf(save); prefsSeen = Prefs.version; });
        });
    }

    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = 0; i < statusItems.size(); i++) out.add(a11yId(Z_STATUS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_STATUS: return label(statusItems, i);
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}", "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return weatherText();
            default: return "";
        }
    }

    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_STATUS:
                if (i >= statusItems.size()) return false;
                a11yRect.set(statusRects[i]);
                dy = status.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(weatherCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_STATUS: return a11yId(Z_STATUS, statusIndex);
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    private boolean talkBack() {
        android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            clickMove();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!drawerOpen || !Prefs.weather()) return;
                widgetIndex = 0;
                break;
            case Z_STATUS:
                if (i >= statusItems.size()) return;
                if (drawerOpen) closeDrawer();
                statusIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        clickMove();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (!data.hasWeather()) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
