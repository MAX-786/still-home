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

    BackdropLayout(Context context, Palette palette, boolean ambient) {
        super(context);
        this.palette = palette;
        addView(new BackdropView(context, palette), match());

        wallpaper = new ImageView(context);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setVisibility(View.GONE);
        addView(wallpaper, match());

        View tint = new View(context);
        GradientDrawable veil = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                ambient
                    ? new int[] { 0xA800090F, 0xC500090F }
                    : new int[] { 0x2800090F, 0x9C00090F });
        tint.setBackground(veil);
        addView(tint, match());
        refreshWallpaper();
    }

    void refreshWallpaper() {
        WallpaperStore.loadInto(getContext(), wallpaper, palette);
    }

    private static LayoutParams match() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }
}
