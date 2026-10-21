#!/usr/bin/env python3
"""Send a personal wallpaper from this Mac to the installed Still Home debug app."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
import xml.etree.ElementTree as ET


PACKAGE = "dev.stillhome.tv"
HOME_ACTIVITY = f"{PACKAGE}/.HomeActivity"
IMPORT_EXTRA = f"{PACKAGE}.extra.IMPORTED_WALLPAPER"
MAX_IMAGE_BYTES = 15 * 1024 * 1024
MAX_IMPORTED_PHOTOS = 12
DEFAULT_SERIAL = os.environ.get("STILL_HOME_TV_SERIAL", "192.168.1.7:5555")


def adb_binary(override: str | None) -> str:
    if override:
        if Path(override).is_file() and os.access(override, os.X_OK):
            return override
        raise RuntimeError(f"adb is not executable: {override}")
    candidates = [override]
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(variable)
        if sdk:
            candidates.append(str(Path(sdk) / "platform-tools" / "adb"))
    candidates.extend((shutil.which("adb"), str(Path.home() / "Library/Android/sdk/platform-tools/adb")))
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise RuntimeError("adb was not found; install Android platform-tools or pass --adb PATH")


def run_adb(adb: str, serial: str, *args: str) -> str:
    command = [adb, "-s", serial, *args]
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False)
    except OSError as error:
        raise RuntimeError(f"Could not start adb: {error}") from error
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"adb {args[0]} failed: {detail or f'exit code {result.returncode}'}")
    return result.stdout.strip()


def image_extension(photo: Path) -> str:
    extension = photo.suffix.lower()
    if extension not in (".jpg", ".jpeg", ".png", ".webp"):
        raise ValueError("Choose a .jpg, .jpeg, .png, or .webp image")
    if not photo.is_file():
        raise ValueError(f"Image file does not exist: {photo}")
    size = photo.stat().st_size
    if not 0 < size <= MAX_IMAGE_BYTES:
        raise ValueError("Image must be between 1 byte and 15 MiB")
    with photo.open("rb") as source:
        signature = source.read(16)
        source.seek(max(0, size - 12))
        ending = source.read()
    actual = (
        ".jpg" if signature.startswith(b"\xff\xd8\xff") else
        ".png" if signature.startswith(b"\x89PNG\r\n\x1a\n") else
        ".webp" if signature.startswith(b"RIFF") and signature[8:12] == b"WEBP" else
        None
    )
    wanted = ".jpg" if extension == ".jpeg" else extension
    if actual != wanted:
        raise ValueError("The file contents do not match its image extension")
    if (wanted == ".jpg" and not ending.endswith(b"\xff\xd9")
            or wanted == ".png" and not ending.endswith(
                b"\x00\x00\x00\x00IEND\xae\x42\x60\x82")
            or wanted == ".webp" and int.from_bytes(signature[4:8], "little") + 8 != size):
        raise ValueError("The image file appears to be incomplete")
    sips = shutil.which("sips")
    if not sips:
        raise RuntimeError("macOS sips is required to validate the full image")
    with tempfile.TemporaryDirectory(prefix="still-home-image-check-") as folder:
        output = Path(folder) / "decoded.png"
        result = subprocess.run([sips, "-s", "format", "png", "--out", str(output),
                                 str(photo)], capture_output=True, text=True, check=False)
        if result.returncode or not output.is_file() or output.stat().st_size == 0:
            raise ValueError("macOS could not decode the complete image")
    return wanted


def imported_name(photo: Path, extension: str) -> str:
    # HomeActivity accepts ASCII names without path separators or "..".
    stem = unicodedata.normalize("NFKD", photo.stem).encode("ascii", "ignore").decode("ascii")
    stem = re.sub(r"[^A-Za-z0-9]+", "-", stem).strip("-").lower()[:50].rstrip("-")
    return f"{stem or 'photo'}-{secrets.token_hex(4)}{extension}"


def selected_wallpaper(adb: str, serial: str) -> str | None:
    try:
        preferences = run_adb(adb, serial, "shell", "run-as", PACKAGE,
                              "cat", "shared_prefs/still_home.xml")
        root = ET.fromstring(preferences)
    except (RuntimeError, ET.ParseError):
        return None
    entry = root.find("./string[@name='wallpaper_uri']")
    return entry.text if entry is not None else None


def wait_until_applied(adb: str, serial: str, filename: str) -> bool:
    expected_suffix = f"/files/wallpapers/{filename}"
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        selected = selected_wallpaper(adb, serial)
        if selected and selected.startswith("file://") and selected.endswith(expected_suffix):
            return True
        time.sleep(0.25)
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("photo", type=Path, help="local JPEG, PNG, or WebP image (up to 15 MiB)")
    parser.add_argument("--serial", default=DEFAULT_SERIAL, help="ADB device address or serial")
    parser.add_argument("--adb", help="path to adb; usually found automatically")
    args = parser.parse_args()

    try:
        photo = args.photo.expanduser()
        extension = image_extension(photo)
        adb = adb_binary(args.adb)
        filename = imported_name(photo, extension)
        remote_temp = f"/data/local/tmp/stillhome-{filename}"

        print(f"Sending {photo.name} to {args.serial}…", flush=True)
        run_adb(adb, args.serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "files/wallpapers")
        listed = run_adb(adb, args.serial, "shell", "run-as", PACKAGE,
                         "ls", "-1", "files/wallpapers")
        existing = [name for name in listed.splitlines()
                    if name.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))]
        if len(existing) >= MAX_IMPORTED_PHOTOS:
            raise RuntimeError("The TV already has 12 imported photos. Hold OK on one in Settings to remove it.")
        copied = False
        applied = False
        try:
            run_adb(adb, args.serial, "push", str(photo), remote_temp)
            copied = True
            run_adb(adb, args.serial, "shell", "run-as", PACKAGE, "cp", remote_temp,
                    f"files/wallpapers/{filename}")
            run_adb(adb, args.serial, "shell", "run-as", PACKAGE, "chmod", "600",
                    f"files/wallpapers/{filename}")
            run_adb(adb, args.serial, "shell", "am", "start", "-n", HOME_ACTIVITY,
                    "--es", IMPORT_EXTRA, filename)
            applied = wait_until_applied(adb, args.serial, filename)
            if not applied:
                raise RuntimeError("The TV did not select the transferred image within 15 seconds")
        except RuntimeError:
            if copied and not applied:
                current = selected_wallpaper(adb, args.serial)
                if not current or not current.endswith(f"/files/wallpapers/{filename}"):
                    try:
                        run_adb(adb, args.serial, "shell", "run-as", PACKAGE,
                                "rm", "-f", f"files/wallpapers/{filename}")
                    except RuntimeError as cleanup_error:
                        print(f"Imported file cleanup: {cleanup_error}", file=sys.stderr)
            raise
        finally:
            # The image remains only in Still Home's private storage after the copy.
            try:
                run_adb(adb, args.serial, "shell", "rm", "-f", remote_temp)
            except RuntimeError as error:
                print(f"Temporary file cleanup: {error}", file=sys.stderr)

        print(f"Wallpaper imported and applied: {filename}")
        return 0
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Wallpaper import failed: {error}", file=sys.stderr)
        if "run-as" in str(error) or "adb " in str(error):
            print("Check that the debug APK is installed and ADB authorization is active.",
                  file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
