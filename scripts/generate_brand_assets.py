"""Generate small original TV icon and banner assets; requires Pillow."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/res/drawable-nodpi"
OUTPUT.mkdir(parents=True, exist_ok=True)


def landscape(width: int, height: int) -> Image.Image:
    scale = 3
    w, h = width * scale, height * scale
    image = Image.new("RGB", (w, h))
    pixels = image.load()
    for y in range(h):
        t = y / max(1, h - 1)
        for x in range(w):
            side = x / max(1, w - 1)
            glow = max(0.0, 1.0 - abs(side - 0.62) * 1.5) * max(0.0, 1.0 - abs(t - 0.78) * 1.8)
            pixels[x, y] = (
                int(7 + 14 * t + 29 * glow),
                int(24 + 19 * t + 35 * glow),
                int(38 + 18 * t + 35 * glow),
            )

    moon = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(moon)
    radius = min(w, h) * 0.11
    cx, cy = w * 0.72, h * 0.30
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=(230, 220, 202, 215))
    halo = moon.filter(ImageFilter.GaussianBlur(radius * 0.65))
    image = Image.alpha_composite(image.convert("RGBA"), halo)
    image = Image.alpha_composite(image, moon)

    draw = ImageDraw.Draw(image)
    horizon = int(h * 0.73)
    draw.polygon([(0, horizon), (int(w * .20), int(h * .67)),
                  (int(w * .43), int(h * .74)), (int(w * .67), int(h * .69)),
                  (w, int(h * .76)), (w, h), (0, h)], fill=(8, 27, 38, 255))
    draw.polygon([(0, int(h * .85)), (int(w * .3), int(h * .80)),
                  (int(w * .56), int(h * .87)), (w, int(h * .78)),
                  (w, h), (0, h)], fill=(5, 18, 29, 255))
    return image.convert("RGB").resize((width, height), Image.Resampling.LANCZOS)


landscape(192, 192).save(OUTPUT / "app_icon.png", optimize=True)
landscape(320, 180).save(OUTPUT / "tv_banner.png", optimize=True)
