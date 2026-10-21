# TV launcher visual studies

Five original 1920 × 1080 PNGs explore a calm, remote-first launcher. The app names are examples. The abstract symbols are placeholders; the real launcher should load each installed app's Android icon and label.

| File | Direction | Remote behavior |
| --- | --- | --- |
| `01_horizon_shelf.png` | Large clock over a subdued lake; one frosted app shelf | Left/right through favorites, Up to All apps and settings |
| `02_stone_grid.png` | Side rail plus spacious 4 × 2 app grid | Left/right/up/down through apps; Left from first column to the rail |
| `03_night_strip.png` | Wallpaper-led home with the focused app named above a low strip | Left/right through apps; OK opens the focused app |
| `04_stillwater_screensaver.png` | Dimmed wallpaper, clock, date and return hint | Any remote button returns to Home |
| `05_wallpaper_picker.png` | Large preview, three built-in choices and photo import | Up/down through choices; OK applies a wallpaper |

**Horizon shelf** was the initial starting point. The finished home screen follows the owner's later visual reference and uses their dusk lake image. A single focus row is fast to scan from a sofa, shows the wallpaper, and requires little work per D-pad press. Extra favorites can scroll horizontally, and All apps shows the complete set.

## Visual system

- **Palette:** deep ink `#071A27`, slate `#294A56`, fog `#889994`, warm horizon `#C5AD91`, soft white `#F5F7F3`. Stone uses muted plum and blue; Night sea uses navy and moon ivory.
- **Type:** light clock numerals, medium app labels, clear title hierarchy. The mockups were rendered with macOS Avenir Next. Do not bundle that font in the Android app; use a small licensed font or the Android system sans face.
- **Layout:** 68–120 px safe margins, 23–34 px app/navigation labels, a visible 3 px focus outline with a translucent fill. The focus remains legible when the wallpaper changes.
- **Restraint:** one active focus surface, no feeds or autoplay, no decorative badges. The landscape is intentionally quiet so a chosen photo can carry the personality.

## Runtime translation

The PNGs are visual references, not proposed full-screen image assets. The Android UI should draw native text and controls, cache the chosen wallpaper at screen size, and use a tinted translucent panel or a pre-blurred static wallpaper crop for the glass effect. Avoid blurring the live screen on every focus move. Only the focus treatment needs a short animation. The screensaver clock can shift slightly over time so it does not sit on one set of pixels indefinitely.

Regenerate the studies on macOS with `python3 generate.py` from this directory (Pillow and NumPy required). Run `python3 export_wallpapers.py` to rebuild the three UI-free, optimized JPEG resources in `app/src/main/res/drawable-nodpi`.
