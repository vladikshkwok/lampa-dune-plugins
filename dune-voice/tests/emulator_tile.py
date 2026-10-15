"""Compatibility entry point for the consolidated emulator viewing/tile lifecycle probe."""
import argparse
import re
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        raise SystemExit("This check only runs on an emulator")
    package = "org.duckdns.dunevoice.phone"
    for permission in ("RECORD_AUDIO", "POST_NOTIFICATIONS"):
        subprocess.run([args.adb, "-s", args.serial, "shell", "pm", "grant", package, "android.permission." + permission], check=True, capture_output=True, timeout=20)
    subprocess.run([sys.executable, str(Path(__file__).with_name("instrument_probe.py")), "phone", "--viewing", "--adb", args.adb, "--serial", args.serial], check=True)


if __name__ == "__main__":
    main()
