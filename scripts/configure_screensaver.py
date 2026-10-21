#!/usr/bin/env python3
"""Select Still Home's DreamService on a TV without a screensaver settings page."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys


STILL_DREAM = "dev.stillhome.tv/dev.stillhome.tv.StillDreamService"
DEFAULT_SERIAL = os.environ.get("STILL_HOME_TV_SERIAL", "192.168.1.7:5555")
DEFAULT_BACKUP = Path(__file__).resolve().parents[1] / ".local" / "screensaver-backup.json"
SETTINGS = ("screensaver_components", "screensaver_enabled")


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
    try:
        result = subprocess.run([adb, "-s", serial, *args], capture_output=True,
                                text=True, check=False)
    except OSError as error:
        raise RuntimeError(f"Could not start adb: {error}") from error
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"adb {args[0]} failed: {detail or f'exit code {result.returncode}'}")
    return result.stdout.strip()


def get_setting(adb: str, serial: str, key: str) -> str | None:
    result = run_adb(adb, serial, "shell", "settings", "get", "secure", key)
    return None if result == "null" else result


def set_setting(adb: str, serial: str, key: str, value: str | None) -> None:
    if value is None:
        run_adb(adb, serial, "shell", "settings", "delete", "secure", key)
    else:
        # A single quoted remote command preserves an original component list exactly.
        run_adb(adb, serial, "shell", f"settings put secure {key} {shlex.quote(value)}")


def read_backup(path: Path) -> dict[str, object]:
    try:
        backup = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Cannot read backup {path}: {error}") from error
    if (not isinstance(backup, dict) or backup.get("schema") != 2
            or not all(key in backup for key in SETTINGS)):
        raise RuntimeError(f"Unrecognized backup format: {path}")
    fingerprint = backup.get("device_fingerprint")
    if not isinstance(fingerprint, str) or len(fingerprint) != 64:
        raise RuntimeError(f"Invalid device identity in backup: {path}")
    for key in SETTINGS:
        value = backup[key]
        if value is not None and not isinstance(value, str):
            raise RuntimeError(f"Invalid {key} in backup: {path}")
    return backup


def device_fingerprint(adb: str, serial: str) -> str:
    android_id = get_setting(adb, serial, "android_id")
    if not android_id:
        raise RuntimeError("This TV did not expose a stable Android ID; no settings were changed")
    return hashlib.sha256(android_id.encode("utf-8")).hexdigest()


def assert_correct_tv(backup: dict[str, object], fingerprint: str) -> None:
    if backup["device_fingerprint"] != fingerprint:
        raise RuntimeError("The backup belongs to a different TV; no settings were changed")


def make_backup(path: Path, serial: str, fingerprint: str,
                state: dict[str, str | None]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    record: dict[str, object] = {
        "schema": 2,
        "saved_at_utc": datetime.now(timezone.utc).isoformat(),
        "serial_at_backup": serial,
        "device_fingerprint": fingerprint,
        **state,
    }
    try:
        with path.open("x", encoding="utf-8") as output:
            json.dump(record, output, indent=2)
            output.write("\n")
    except FileExistsError:
        # A concurrent or previous run has already preserved the original state.
        assert_correct_tv(read_backup(path), fingerprint)


def state_on_tv(adb: str, serial: str) -> dict[str, str | None]:
    return {key: get_setting(adb, serial, key) for key in SETTINGS}


def require_installed_dream(adb: str, serial: str) -> None:
    services = run_adb(adb, serial, "shell", "cmd", "package", "query-services",
                       "--brief", "-a", "android.service.dreams.DreamService")
    available = {line.strip() for line in services.splitlines()}
    if not ({STILL_DREAM, "dev.stillhome.tv/.StillDreamService"} & available):
        raise RuntimeError("Still Home DreamService is not installed on this TV")


def verify(adb: str, serial: str, expected: dict[str, str | None]) -> None:
    actual = state_on_tv(adb, serial)
    if actual != expected:
        raise RuntimeError(f"TV settings differ from expected values: {actual}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("status", "setup", "rollback"))
    parser.add_argument("--serial", default=DEFAULT_SERIAL, help="ADB address or serial")
    parser.add_argument("--adb", help="path to adb; usually found automatically")
    parser.add_argument("--backup", type=Path, default=DEFAULT_BACKUP,
                        help=f"local backup file (default: {DEFAULT_BACKUP})")
    parser.add_argument("--force", action="store_true",
                        help="restore original settings even if the TV has changed since setup")
    args = parser.parse_args()

    try:
        adb = adb_binary(args.adb)
        current = state_on_tv(adb, args.serial)
        if args.action == "status":
            print(f"TV: {args.serial}")
            print(f"Selected screensaver: {current['screensaver_components'] or '(none)'}")
            print(f"Enabled: {current['screensaver_enabled'] or '(unset)'}")
            print(f"Still Home selected: {current['screensaver_components'] == STILL_DREAM}")
            print(f"Rollback backup: {args.backup if args.backup.is_file() else '(none)'}")
            return 0

        fingerprint = device_fingerprint(adb, args.serial)
        if args.action == "setup":
            require_installed_dream(adb, args.serial)
            if args.backup.exists():
                assert_correct_tv(read_backup(args.backup), fingerprint)
            elif current["screensaver_components"] == STILL_DREAM:
                raise RuntimeError("Still Home is already selected but there is no original-state backup")
            else:
                make_backup(args.backup, args.serial, fingerprint, current)
                print(f"Original screensaver saved to {args.backup}")
            desired = {"screensaver_components": STILL_DREAM, "screensaver_enabled": "1"}
            for key, value in desired.items():
                set_setting(adb, args.serial, key, value)
            verify(adb, args.serial, desired)
            print("Still Home screensaver selected and enabled.")
            return 0

        if not args.backup.is_file():
            raise RuntimeError(f"No rollback backup at {args.backup}")
        backup = read_backup(args.backup)
        assert_correct_tv(backup, fingerprint)
        selected_by_helper = {"screensaver_components": STILL_DREAM,
                              "screensaver_enabled": "1"}
        if current != selected_by_helper and not args.force:
            raise RuntimeError("TV screensaver settings changed since setup; use --force to restore anyway")
        original = {key: backup[key] for key in SETTINGS}
        for key, value in original.items():
            set_setting(adb, args.serial, key, value)
        verify(adb, args.serial, original)
        args.backup.unlink()
        print("Original screensaver settings restored; rollback backup removed.")
        return 0
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Screensaver {args.action} failed: {error}", file=sys.stderr)
        if args.action == "setup":
            print("If setup was interrupted, run the rollback action using the saved backup.",
                  file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
