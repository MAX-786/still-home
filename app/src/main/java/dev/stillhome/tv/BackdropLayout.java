package dev.stillhome.tv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Shared backdrop for Home, settings, maintenance and the screensaver. */
final class BackdropLayout extends FrameLayout {
    private final ImageView wallpaper;
    private final Palette palette;
    private final View tint;
    private final boolean ambient;

    BackdropLayout(Context context, Palette palette, boolean ambient) {
        super(context);
        this.palette = palette;
        this.ambient = ambient;
        addView(new BackdropView(context, palette), match());

        wallpaper = new ImageView(context);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setVisibility(View.GONE);
        addView(wallpaper, match());

        tint = new View(context);
        addView(tint, match());
        refreshWallpaper();
    }

    void refreshWallpaper() {
        boolean cleanDusk = !ambient && getContext() instanceof HomeActivity
                && palette == Palette.NIGHT
                && LauncherPrefs.wallpaperUri(getContext()) == null;
        tint.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                ambient
                    ? new int[] { 0xA800090F, 0xC500090F }
                    : cleanDusk
                        ? new int[] { 0x0000090F, 0x0800090F }
                        : new int[] { 0x2800090F, 0x9C00090F }));
        WallpaperStore.loadInto(getContext(), wallpaper, palette);
    }

    private static LayoutParams match() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }
}
