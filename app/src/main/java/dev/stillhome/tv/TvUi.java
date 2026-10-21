package dev.stillhome.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native view styling shared by every screen. */
final class TvUi {
    static final int WHITE = 0xFFF4F6F8;
    static final int MUTED = 0xFFC2C9D2;
    enum ActionIcon { GRID, DISPLAY, SLIDERS }

    private TvUi() { }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static void fullScreen(Activity activity) {
        activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    static TextView text(Context context, String value, float size, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static GradientDrawable rounded(Context context, int fill, int stroke, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        shape.setStroke(dp(context, 1), stroke);
        return shape;
    }

    static void focusSurface(View view, Palette palette, int normalFill, float radius) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        view.setBackground(focusStates(view.getContext(), normalFill,
                palette.focusedTile(), radius));
        view.setForeground(focusStates(view.getContext(), Color.TRANSPARENT,
                Color.TRANSPARENT, radius));
        view.setOnFocusChangeListener((focusedView, focused) -> {
            focusedView.animate().cancel();
            focusedView.animate().scaleX(focused ? 1.035f : 1f)
                    .scaleY(focused ? 1.035f : 1f).setDuration(130).start();
        });
    }

    static void favoriteCard(View tile, View underline) {
        Context context = tile.getContext();
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xB1293743, 0xB1192433 });
        normal.setCornerRadius(dp(context, 16));
        normal.setStroke(dp(context, 1), 0x48B6C6DA);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_focused }, focusedCard(context));
        states.addState(new int[0], normal);
        tile.setBackground(states);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setOnFocusChangeListener((view, hasFocus) -> {
            view.animate().cancel();
            view.animate().scaleX(hasFocus ? 1.16f : 1f)
                    .scaleY(hasFocus ? 1.16f : 1f)
                    .translationY(hasFocus ? -dp(context, 2) : 0f)
                    .setDuration(150).start();
            view.setElevation(hasFocus ? dp(context, 7) : 0f);
            underline.setAlpha(hasFocus ? 1f : 0f);
        });
    }

    static void appGridCard(View tile) {
        Context context = tile.getContext();
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xBC1C2C3D, 0xC0132334 });
        normal.setCornerRadius(dp(context, 16));
        normal.setStroke(dp(context, 1), 0x55B6C6DA);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_focused }, focusedCard(context));
        states.addState(new int[0], normal);
        tile.setBackground(states);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setOnFocusChangeListener((view, hasFocus) -> {
            view.animate().cancel();
            view.animate().scaleX(hasFocus ? 1.045f : 1f)
                    .scaleY(hasFocus ? 1.045f : 1f)
                    .setDuration(130).start();
            view.setElevation(hasFocus ? dp(context, 5) : 0f);
        });
    }

    private static StateListDrawable focusStates(Context context, int normalFill,
                                                 int focusedFill, float radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_focused },
                rounded(context, focusedFill, WHITE, radius));
        states.addState(new int[0], rounded(context, normalFill, 0x55FFFFFF, radius));
        return states;
    }

    private static Drawable focusedCard(Context context) {
        int radius = dp(context, 16);
        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xD04B698E, 0xD038547A });
        fill.setCornerRadius(radius);

        GradientDrawable edgeLight = new GradientDrawable();
        edgeLight.setCornerRadius(radius);
        edgeLight.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        edgeLight.setGradientCenter(0f, 0f);
        edgeLight.setGradientRadius(dp(context, 38));
        edgeLight.setColors(new int[] { 0x50B1C5E3, Color.TRANSPARENT });

        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(radius);
        outline.setStroke(dp(context, 1.5f), 0xFF9CB5E6);
        return new LayerDrawable(new Drawable[] { fill, edgeLight, outline });
    }

    static TextView action(Context context, Palette palette, String label) {
        TextView view = text(context, label, 16, WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 18), dp(context, 10),
                dp(context, 18), dp(context, 10));
        view.setMinHeight(dp(context, 44));
        glassButton(view, palette);
        return view;
    }

    static TextView iconAction(Context context, Palette palette, String label,
                               ActionIcon icon) {
        TextView view = text(context, label, 13, WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(dp(context, 9), dp(context, 8),
                dp(context, 9), dp(context, 8));
        view.setMinHeight(dp(context, 36));
        view.setCompoundDrawablePadding(dp(context, 7));
        ToolbarIconDrawable drawable = new ToolbarIconDrawable(icon);
        int iconSize = dp(context, 17);
        drawable.setBounds(0, 0, iconSize, iconSize);
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setContentDescription(label);
        glassButton(view, palette);
        return view;
    }

    private static void glassButton(View view, Palette palette) {
        Context context = view.getContext();
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        view.setBackground(new GlassSurfaceDrawable(context, view, palette, true));
        StateListDrawable focusRing = new StateListDrawable();
        focusRing.addState(new int[] { android.R.attr.state_focused },
                rounded(context, Color.TRANSPARENT, 0xFFD7E8FF, 18));
        focusRing.addState(new int[0],
                rounded(context, Color.TRANSPARENT, Color.TRANSPARENT, 18));
        view.setForeground(focusRing);
        view.setOnFocusChangeListener((focusedView, focused) -> {
            focusedView.animate().cancel();
            focusedView.animate().scaleX(focused ? 1.035f : 1f)
                    .scaleY(focused ? 1.035f : 1f).setDuration(130).start();
        });
    }

    private static final class ToolbarIconDrawable extends Drawable {
        private final ActionIcon icon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ToolbarIconDrawable(ActionIcon icon) {
            this.icon = icon;
            paint.setColor(WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            switch (icon) {
                case GRID:
                    canvas.drawRoundRect(3, 3, 10, 10, 1.3f, 1.3f, paint);
                    canvas.drawRoundRect(14, 3, 21, 10, 1.3f, 1.3f, paint);
                    canvas.drawRoundRect(3, 14, 10, 21, 1.3f, 1.3f, paint);
                    canvas.drawRoundRect(14, 14, 21, 21, 1.3f, 1.3f, paint);
                    break;
                case DISPLAY:
                    canvas.drawRoundRect(2.5f, 3.5f, 21.5f, 17.5f,
                            2.2f, 2.2f, paint);
                    canvas.drawLine(12, 17.5f, 12, 20.5f, paint);
                    canvas.drawLine(7.5f, 20.5f, 16.5f, 20.5f, paint);
                    break;
                case SLIDERS:
                    canvas.drawLine(3, 5, 21, 5, paint);
                    canvas.drawLine(3, 12, 21, 12, paint);
                    canvas.drawLine(3, 19, 21, 19, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(8, 5, 2.6f, paint);
                    canvas.drawCircle(16, 12, 2.6f, paint);
                    canvas.drawCircle(10, 19, 2.6f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    break;
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static void clipToBounds(View view) {
        view.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        view.setClipToOutline(true);
    }

    static void remoteLongClick(View view, View.OnLongClickListener action) {
        final boolean[] handled = { false };
        view.setOnLongClickListener(target -> {
            if (handled[0]) {
                return true;
            }
            handled[0] = action.onLongClick(target);
            return handled[0];
        });
        view.setOnKeyListener((target, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER
                    && keyCode != KeyEvent.KEYCODE_ENTER
                    && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean consumed = handled[0];
                handled[0] = false;
                return consumed;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    handled[0] = false;
                    return false;
                }
                if (event.isLongPress() || event.getRepeatCount() > 0) {
                    boolean consumed = target.performLongClick();
                    target.cancelLongPress();
                    return consumed;
                }
            }
            return false;
        });
        view.setOnTouchListener((target, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                handled[0] = false;
            }
            return false;
        });
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setClipChildren(false);
        layout.setClipToPadding(false);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setClipChildren(false);
        layout.setClipToPadding(false);
        return layout;
    }

    static View space(Context context) {
        return new View(context);
    }

    static void addVerticalSpace(LinearLayout parent, Context context, int dp) {
        parent.addView(space(context), new LinearLayout.LayoutParams(1, dp(context, dp)));
    }

    static int dimmed(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
