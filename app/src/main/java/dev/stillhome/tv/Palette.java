package dev.stillhome.tv;

import android.graphics.Color;

/** Small, fixed palettes keep the default wallpaper cheap to draw. */
enum Palette {
    HORIZON("Horizon", 0xFF071A27, 0xFF101C26, 0xFF466774, 0xFFC5AD91),
    STONE("Stone", 0xFF1D1B29, 0xFF171C28, 0xFF66515C, 0xFFD7C1BF),
    NIGHT("Night sea", 0xFF081A29, 0xFF06121E, 0xFF3D596B, 0xFFE5DDD0);

    final String title;
    final int top;
    final int bottom;
    final int glow;
    final int accent;

    Palette(String title, int top, int bottom, int glow, int accent) {
        this.title = title;
        this.top = top;
        this.bottom = bottom;
        this.glow = glow;
        this.accent = accent;
    }

    int panel() {
        return Color.argb(205, 8, 20, 29);
    }

    int tile() {
        return Color.argb(105, 235, 243, 242);
    }

    int focusedTile() {
        return Color.argb(75, 235, 243, 242);
    }

    static Palette fromName(String name) {
        for (Palette palette : values()) {
            if (palette.name().equals(name)) {
                return palette;
            }
        }
        return HORIZON;
    }
}
