"""Render original, logo-free Android TV launcher design studies.

Requires Pillow and NumPy. The output files are 1920 x 1080 PNGs.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont


WIDTH = 1920
HEIGHT = 1080
HERE = Path(__file__).resolve().parent
FONT_PATH = "/System/Library/Fonts/Avenir Next.ttc"


def font(size: int, style: str = "regular") -> ImageFont.FreeTypeFont:
    indexes = {"bold": 0, "demi": 2, "medium": 5, "regular": 7, "light": 10}
    return ImageFont.truetype(FONT_PATH, size, index=indexes[style])


def text(
    image: Image.Image,
    xy: tuple[int, int],
    value: str,
    size: int,
    color: str | tuple[int, ...] = "#F5F5F2",
    style: str = "regular",
    anchor: str | None = None,
) -> None:
    ImageDraw.Draw(image).text(xy, value, font=font(size, style), fill=color, anchor=anchor)


def rounded_overlay(
    image: Image.Image,
    box: tuple[int, int, int, int],
    radius: int,
    fill: tuple[int, int, int, int],
    outline: tuple[int, int, int, int] | None = None,
    width: int = 1,
) -> None:
    layer = Image.new("RGBA", image.size)
    draw = ImageDraw.Draw(layer)
    draw.rounded_rectangle(box, radius, fill=fill, outline=outline, width=width)
    image.alpha_composite(layer)


def frosted_panel(
    image: Image.Image,
    box: tuple[int, int, int, int],
    radius: int,
    tint: tuple[int, int, int, int],
    blur: int = 26,
) -> None:
    mask = Image.new("L", image.size)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius, fill=220)
    image.paste(image.filter(ImageFilter.GaussianBlur(blur)), (0, 0), mask)
    rounded_overlay(image, box, radius, tint, outline=(255, 255, 255, 45), width=2)


def glow(
    image: Image.Image,
    xy: tuple[int, int],
    radius: int,
    rgba: tuple[int, int, int, int],
    blur: int,
) -> None:
    layer = Image.new("RGBA", image.size)
    x, y = xy
    ImageDraw.Draw(layer).ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba)
    image.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def gradient(stops: list[tuple[float, tuple[int, int, int]]], seed: int = 1) -> Image.Image:
    y = np.linspace(0, 1, HEIGHT, dtype=np.float32)
    rows = np.zeros((HEIGHT, 3), dtype=np.float32)
    for channel in range(3):
        rows[:, channel] = np.interp(y, [s[0] for s in stops], [s[1][channel] for s in stops])
    rgb = np.broadcast_to(rows[:, None, :], (HEIGHT, WIDTH, 3)).copy()
    rng = np.random.default_rng(seed)
    noise = rng.normal(0, 1.5, (HEIGHT, WIDTH, 1)).astype(np.float32)
    rgb = np.clip(rgb + noise, 0, 255).astype(np.uint8)
    return Image.fromarray(rgb, "RGB").convert("RGBA")


def darken_bottom(image: Image.Image, top: int, strength: int) -> None:
    alpha = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)
    for y in range(top, HEIGHT):
        fraction = (y - top) / max(1, HEIGHT - top)
        alpha[y, :] = int(strength * fraction)
    layer = Image.new("RGBA", image.size, (0, 0, 0, 210))
    layer.putalpha(Image.fromarray(alpha))
    image.alpha_composite(layer)


def dusk_lake() -> Image.Image:
    image = gradient(
        [
            (0.00, (18, 35, 48)),
            (0.40, (47, 74, 86)),
            (0.59, (126, 127, 119)),
            (0.72, (54, 73, 76)),
            (1.00, (13, 27, 37)),
        ],
        seed=12,
    )
    glow(image, (1330, 525), 365, (222, 179, 130, 78), 170)
    glow(image, (410, 230), 380, (52, 116, 141, 30), 150)
    mountain = Image.new("RGBA", image.size)
    d = ImageDraw.Draw(mountain)
    x = np.arange(-10, WIDTH + 11, 10)
    y_back = 618 + 36 * np.sin(x / 245) + 18 * np.sin(x / 93 + 1.7)
    d.polygon([(0, HEIGHT), *list(zip(x.tolist(), y_back.astype(int).tolist())), (WIDTH, HEIGHT)], fill=(65, 80, 80, 100))
    y_front = 670 + 47 * np.sin(x / 210 + 1.1) + 12 * np.sin(x / 63)
    d.polygon([(0, HEIGHT), *list(zip(x.tolist(), y_front.astype(int).tolist())), (WIDTH, HEIGHT)], fill=(19, 42, 51, 182))
    image.alpha_composite(mountain.filter(ImageFilter.GaussianBlur(20)))
    d = ImageDraw.Draw(image, "RGBA")
    for i in range(18):
        y = 705 + i * 17
        x0 = 430 + ((i * 71) % 410)
        x1 = min(1680, x0 + 310 + ((i * 109) % 340))
        d.line((x0, y, x1, y), fill=(168, 160, 137, max(3, 22 - i)), width=1)
    darken_bottom(image, 650, 115)
    return image


def folded_stone() -> Image.Image:
    image = gradient(
        [
            (0.00, (27, 30, 43)),
            (0.46, (45, 45, 58)),
            (0.74, (37, 41, 53)),
            (1.00, (22, 30, 42)),
        ],
        seed=35,
    )
    glow(image, (1500, 275), 310, (204, 157, 129, 72), 180)
    glow(image, (780, 900), 570, (80, 91, 140, 48), 260)
    layer = Image.new("RGBA", image.size)
    d = ImageDraw.Draw(layer)
    d.ellipse((1250, 50, 1900, 700), fill=(178, 143, 134, 53), outline=(231, 211, 189, 33), width=3)
    d.polygon([(775, 765), (1260, 300), (1720, 550), (WIDTH, 390), (WIDTH, HEIGHT), (835, HEIGHT)], fill=(88, 91, 111, 66))
    d.polygon([(960, HEIGHT), (1390, 355), (1730, 660), (WIDTH, 590), (WIDTH, HEIGHT)], fill=(22, 34, 51, 110))
    d.polygon([(1280, HEIGHT), (1740, 560), (WIDTH, 705), (WIDTH, HEIGHT)], fill=(107, 91, 107, 65))
    image.alpha_composite(layer.filter(ImageFilter.GaussianBlur(20)))
    d = ImageDraw.Draw(image, "RGBA")
    darken_bottom(image, 750, 60)
    return image


def night_sea() -> Image.Image:
    image = gradient(
        [
            (0.00, (16, 29, 45)),
            (0.42, (37, 54, 68)),
            (0.68, (60, 65, 76)),
            (1.00, (10, 24, 36)),
        ],
        seed=59,
    )
    glow(image, (1450, 280), 225, (182, 177, 159, 75), 135)
    layer = Image.new("RGBA", image.size)
    d = ImageDraw.Draw(layer)
    d.ellipse((1325, 128, 1575, 378), fill=(226, 218, 195, 104))
    image.alpha_composite(layer.filter(ImageFilter.GaussianBlur(28)))
    d = ImageDraw.Draw(image, "RGBA")
    d.ellipse((1375, 177, 1525, 327), fill=(225, 219, 206, 138))
    for i, (color, lift) in enumerate([((24, 43, 56, 95), 0), ((18, 37, 49, 155), 63), ((7, 25, 37, 200), 129)]):
        xs = np.arange(0, WIDTH + 25, 25)
        ys = 640 + lift + 24 * np.sin(xs / (170 + 25 * i) + i * 1.1) + 10 * np.sin(xs / 67 + i)
        d.polygon([*list(zip(xs.tolist(), ys.astype(int).tolist())), (WIDTH, HEIGHT), (0, HEIGHT)], fill=color)
    for i in range(14):
        y = 700 + i * 21
        x = 930 + ((i * 101) % 510)
        d.line((x, y, min(WIDTH - 100, x + 170 + i * 9), y), fill=(178, 183, 172, max(4, 25 - i)), width=1)
    darken_bottom(image, 655, 125)
    return image


APPS = ["Netflix", "YouTube", "Xiaomi TV+", "Prime Video", "Disney+", "Plex", "Photos", "Settings"]
ICON_COLORS = [
    ((116, 75, 86), (212, 174, 170)),
    ((129, 88, 91), (235, 190, 165)),
    ((94, 107, 105), (188, 209, 197)),
    ((62, 98, 119), (174, 206, 220)),
    ((85, 81, 117), (185, 183, 220)),
    ((105, 92, 100), (208, 184, 193)),
    ((108, 105, 84), (222, 209, 160)),
    ((72, 83, 100), (190, 198, 216)),
]


def app_icon(image: Image.Image, index: int, center: tuple[int, int], size: int) -> None:
    """Draw a neutral stand-in symbol, never a service's trademark icon."""
    cx, cy = center
    bg, ink = ICON_COLORS[index]
    x0, y0, x1, y1 = cx - size // 2, cy - size // 2, cx + size // 2, cy + size // 2
    rounded_overlay(image, (x0, y0, x1, y1), max(15, size // 4), (*bg, 240), outline=(255, 255, 255, 37), width=1)
    d = ImageDraw.Draw(image, "RGBA")
    line = max(3, size // 28)
    symbol = (*ink, 225)
    r = size * 0.25
    if index == 0:
        d.arc((cx - r, cy - r, cx + r, cy + r), 205, 535, fill=symbol, width=line)
        d.line((cx - r * 0.62, cy + r * 0.18, cx + r * 0.55, cy - r * 0.35), fill=symbol, width=line)
    elif index == 1:
        d.ellipse((cx - r * 0.68, cy - r * 0.68, cx + r * 0.68, cy + r * 0.68), outline=symbol, width=line)
        d.polygon([(cx - r * 0.13, cy - r * 0.29), (cx - r * 0.13, cy + r * 0.29), (cx + r * 0.38, cy)], fill=symbol)
    elif index == 2:
        d.rounded_rectangle((cx - r, cy - r * 0.72, cx + r, cy + r * 0.6), radius=size * 0.07, outline=symbol, width=line)
        d.line((cx - r * 0.38, cy + r * 0.95, cx + r * 0.38, cy + r * 0.95), fill=symbol, width=line)
    elif index == 3:
        d.arc((cx - r, cy - r * 0.8, cx + r, cy + r * 0.8), 20, 160, fill=symbol, width=line)
        d.line((cx - r * 0.82, cy - r * 0.1, cx + r * 0.82, cy - r * 0.1), fill=symbol, width=line)
    elif index == 4:
        d.ellipse((cx - r * 0.85, cy - r * 0.85, cx + r * 0.85, cy + r * 0.85), outline=symbol, width=line)
        d.ellipse((cx + r * 0.2, cy - r * 0.8, cx + r * 0.44, cy - r * 0.56), fill=symbol)
    elif index == 5:
        for dx in (-r * 0.44, r * 0.44):
            d.ellipse((cx + dx - r * 0.18, cy - r * 0.18, cx + dx + r * 0.18, cy + r * 0.18), fill=symbol)
        d.line((cx - r * 0.2, cy, cx + r * 0.2, cy), fill=symbol, width=line)
    elif index == 6:
        for angle in (0, 90, 180, 270):
            radians = math.radians(angle)
            px, py = cx + math.cos(radians) * r * 0.45, cy + math.sin(radians) * r * 0.45
            d.ellipse((px - r * 0.27, py - r * 0.27, px + r * 0.27, py + r * 0.27), fill=symbol)
        d.ellipse((cx - r * 0.18, cy - r * 0.18, cx + r * 0.18, cy + r * 0.18), fill=(*bg, 255))
    else:
        d.ellipse((cx - r * 0.78, cy - r * 0.78, cx + r * 0.78, cy + r * 0.78), outline=symbol, width=line)
        d.ellipse((cx - r * 0.18, cy - r * 0.18, cx + r * 0.18, cy + r * 0.18), fill=symbol)


def focus(image: Image.Image, box: tuple[int, int, int, int], radius: int = 28) -> None:
    # Focus is high contrast even on a user-selected wallpaper.
    halo = Image.new("RGBA", image.size)
    ImageDraw.Draw(halo).rounded_rectangle(box, radius, outline=(216, 230, 231, 103), width=9)
    image.alpha_composite(halo.filter(ImageFilter.GaussianBlur(17)))
    rounded_overlay(image, box, radius, (235, 241, 240, 40), (242, 246, 241, 238), width=3)


def small_settings(image: Image.Image, x: int, y: int) -> None:
    d = ImageDraw.Draw(image, "RGBA")
    d.ellipse((x - 17, y - 17, x + 17, y + 17), outline=(237, 242, 241, 170), width=3)
    d.ellipse((x - 5, y - 5, x + 5, y + 5), fill=(237, 242, 241, 170))


def rounded_preview(image: Image.Image, source: Image.Image, box: tuple[int, int, int, int], radius: int) -> None:
    x0, y0, x1, y1 = box
    preview = source.resize((x1 - x0, y1 - y0), Image.Resampling.LANCZOS)
    mask = Image.new("L", preview.size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, preview.width, preview.height), radius, fill=255)
    image.paste(preview, (x0, y0), mask)
    rounded_overlay(image, box, radius, (0, 0, 0, 0), outline=(237, 243, 239, 65), width=2)


def horizontal_shelf() -> Image.Image:
    image = dusk_lake()
    text(image, (96, 66), "Home", 29, "#E7EBE8", "demi")
    text(image, (1655, 70), "All apps", 26, "#E7EBE8", "medium")
    small_settings(image, 1810, 88)

    text(image, (89, 182), "8:41", 157, "#FAFAF4", "light")
    text(image, (525, 272), "PM", 30, "#ECF1EF", "medium")
    text(image, (99, 378), "Wednesday, 21 October", 34, "#EFF0EA", "regular")

    frosted_panel(image, (68, 707, 1852, 1010), 38, (17, 29, 39, 150), blur=35)
    text(image, (109, 735), "Apps", 30, "#F1F4F1", "demi")
    text(image, (1600, 742), "Edit order", 24, "#C8D0D0", "medium")
    start, gap, tile = 115, 19, 190
    for i, label in enumerate(APPS):
        x = start + i * (tile + gap)
        if i == 0:
            focus(image, (x - 6, 801, x + tile - 4, 978), 30)
        app_icon(image, i, (x + tile // 2 - 5, 875), 92)
        text(image, (x + tile // 2 - 5, 941), label, 24, "#F9FAF7" if i == 0 else "#D5DCDA", "medium", "mt")
    return image


def side_rail_grid() -> Image.Image:
    image = folded_stone()
    frosted_panel(image, (72, 70, 465, 1012), 38, (20, 25, 39, 154), blur=30)
    text(image, (113, 126), "8:41", 84, "#FCF9F4", "light")
    text(image, (120, 235), "Wed, 21 October", 26, "#D6D7D8", "regular")
    d = ImageDraw.Draw(image, "RGBA")
    d.line((117, 315, 419, 315), fill=(245, 240, 235, 43), width=2)
    menu = ["Home", "Apps", "Wallpapers", "Screensaver", "Preferences"]
    for i, item in enumerate(menu):
        y = 365 + i * 104
        if item == "Apps":
            rounded_overlay(image, (103, y - 18, 431, y + 66), 23, (235, 238, 240, 34))
            d = ImageDraw.Draw(image, "RGBA")
            d.rounded_rectangle((104, y - 7, 109, y + 55), 3, fill=(223, 220, 212, 210))
        text(image, (133, y), item, 34, "#FCFAF8" if item == "Apps" else "#C9CCD0", "demi" if item == "Apps" else "regular")
    text(image, (118, 933), "Hold OK to arrange apps", 22, "#AEB8BC", "regular")

    text(image, (572, 125), "Your apps", 57, "#FCFAF7", "medium")
    text(image, (579, 204), "Open an app", 26, "#D0D2D2", "regular")
    centers_x = [690, 990, 1290, 1590]
    centers_y = [420, 750]
    for i, label in enumerate(APPS):
        col, row = i % 4, i // 4
        x, y = centers_x[col], centers_y[row]
        if i == 1:
            focus(image, (x - 125, y - 143, x + 125, y + 146), 34)
        app_icon(image, i, (x, y - 29), 140)
        text(image, (x, y + 88), label, 30, "#FAFAF7" if i == 1 else "#E0E2E1", "medium", "mt")
    return image


def cinematic_strip() -> Image.Image:
    image = night_sea()
    text(image, (100, 69), "Wednesday, 21 October", 29, "#E0E9E9", "regular")
    text(image, (1634, 69), "8:41 PM", 29, "#E6EDED", "medium")
    small_settings(image, 1816, 88)

    text(image, (92, 330), "Xiaomi TV+", 91, "#F4F5EE", "medium")
    text(image, (99, 455), "Press OK to open", 30, "#C8D3D1", "regular")

    text(image, (99, 690), "Apps", 30, "#EEF2EF", "demi")
    d = ImageDraw.Draw(image, "RGBA")
    d.line((99, 745, 1820, 745), fill=(236, 243, 240, 72), width=2)
    start, gap, tile = 109, 22, 186
    for i, label in enumerate(APPS):
        x = start + i * (tile + gap)
        if i == 2:
            focus(image, (x - 6, 766, x + 181, 992), 30)
        app_icon(image, i, (x + 86, 851), 102)
        text(image, (x + 86, 930), label, 23, "#FFFFFF" if i == 2 else "#D2DDDC", "medium", "mt")
    return image


def screensaver() -> Image.Image:
    image = night_sea()
    veil = Image.new("RGBA", image.size, (2, 14, 25, 72))
    image.alpha_composite(veil)
    text(image, (112, 310), "8:41", 205, "#F5F5EE", "light")
    text(image, (124, 536), "PM", 44, "#E7ECE9", "medium")
    text(image, (124, 570), "Wednesday, 21 October", 38, "#D8E2DF", "regular")
    text(image, (126, 972), "Press any button to return", 25, "#AEBEBE", "regular")
    return image


def wallpaper_picker() -> Image.Image:
    image = folded_stone()
    frosted_panel(image, (68, 70, 1852, 1010), 42, (12, 22, 34, 184), blur=45)
    text(image, (120, 102), "Wallpapers", 57, "#F6F7F3", "medium")
    text(image, (125, 189), "Choose a background for Home and Screensaver", 27, "#C6D0CF", "regular")

    rounded_preview(image, dusk_lake(), (120, 278, 1177, 873), 30)
    rounded_overlay(image, (120, 750, 1177, 873), 30, (5, 21, 31, 150))
    text(image, (155, 781), "Dusk lake", 38, "#F5F7F4", "medium")
    text(image, (155, 833), "Slate blue and a warm horizon", 23, "#D5DFDE", "regular")

    text(image, (1250, 281), "Built in", 28, "#EDF1EC", "demi")
    sources = [dusk_lake(), folded_stone(), night_sea()]
    names = ["Dusk lake", "Stone", "Night sea"]
    for i, (source, name) in enumerate(zip(sources, names)):
        y = 335 + i * 150
        box = (1247, y, 1803, y + 132)
        rounded_preview(image, source, box, 23)
        rounded_overlay(image, box, 23, (4, 17, 29, 130))
        if i == 0:
            focus(image, box, 23)
        text(image, (1277, y + 40), name, 30, "#F5F7F4", "medium")

    rounded_overlay(image, (1247, 814, 1803, 919), 26, (225, 233, 226, 43), outline=(237, 244, 239, 90), width=2)
    text(image, (1278, 844), "Choose your photo", 29, "#F4F7F4", "medium")
    text(image, (1278, 883), "From TV storage or USB", 21, "#CAD4D2", "regular")
    text(image, (126, 928), "Press OK to use", 23, "#AFC2C2", "regular")
    return image


def main() -> None:
    HERE.mkdir(parents=True, exist_ok=True)
    variants = [
        ("01_horizon_shelf.png", horizontal_shelf),
        ("02_stone_grid.png", side_rail_grid),
        ("03_night_strip.png", cinematic_strip),
        ("04_stillwater_screensaver.png", screensaver),
        ("05_wallpaper_picker.png", wallpaper_picker),
    ]
    for name, render in variants:
        output = HERE / name
        render().convert("RGB").save(output, optimize=True)
        print(output)


if __name__ == "__main__":
    main()
