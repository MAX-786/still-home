"""Export the original UI-free landscapes as small Android drawable resources."""

from pathlib import Path

from generate import dusk_lake, folded_stone, night_sea


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "app/src/main/res/drawable-nodpi"


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, render in [
        ("wallpaper_horizon.jpg", dusk_lake),
        ("wallpaper_stone.jpg", folded_stone),
        ("wallpaper_night.jpg", night_sea),
    ]:
        path = OUTPUT / name
        render().convert("RGB").save(
            path,
            format="JPEG",
            quality=82,
            optimize=True,
            progressive=True,
            subsampling=2,
        )
        print(f"{path}: {path.stat().st_size / 1024:.1f} KiB")


if __name__ == "__main__":
    main()
