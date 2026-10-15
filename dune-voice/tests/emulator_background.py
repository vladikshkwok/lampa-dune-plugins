"""Lab-only microphone lifecycle check. Refuses physical-device serials."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        raise SystemExit("This check only runs on an emulator")
    package = "org.duckdns.dunevoice.phone"

    def adb(*command):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True, timeout=20, check=True)
        return result.stdout

    def prefs():
        tree = ET.fromstring(adb("shell", "cat", f"/data/data/{package}/shared_prefs/phone.xml"))
        return {node.attrib["name"]: node.attrib.get("value", node.text) for node in tree}

    def active_locks():
        power = adb("shell", "dumpsys", "power")
        match = re.search(r"(?m)^Wake Locks:.*$", power)
        assert match is not None
        return power[match.start():].split("\n\n", 1)[0]

    adb("shell", "input", "keyevent", "224")
    adb("shell", "pm", "grant", package, "android.permission.RECORD_AUDIO")
    adb("shell", "pm", "grant", package, "android.permission.POST_NOTIFICATIONS")
    adb("shell", "am", "start", "-n", package + "/.MainActivity")
    adb("shell", "am", "start-foreground-service", "-n", package + "/.ListeningService")
    try:
        for _ in range(30):
            if prefs().get("state", "").startswith("Слушаю «Эй, Дюна»"):
                break
            time.sleep(1)
        else:
            raise AssertionError("Model or microphone failed to start")
        adb("shell", "input", "keyevent", "3")
        adb("shell", "input", "keyevent", "223")
        time.sleep(2)
        service = adb("shell", "dumpsys", "activity", "services", package)
        assert "isForeground=true" in service and "types=00000080" in service
        assert "dunevoice:listening" in active_locks()
        audio = adb("shell", "dumpsys", "audio")
        recording = audio.split("RecordActivityMonitor dump time:", 1)[1].split("Events log:", 1)[0]
        assert "active? true" in recording and "pack:" + package in recording and "silenced:false" in recording
        assert prefs().get("listening") == "true"
        print("PASS: native microphone records unsilenced with screen off; microphone foreground service and wake lock active")
    finally:
        # Some platform builds report 'not running' while asynchronously
        # delivering the stop. Verify resources below rather than shell status.
        subprocess.run([args.adb, "-s", args.serial, "shell", "am", "stop-service", "-n", package + "/.ListeningService"], capture_output=True, timeout=20)
    time.sleep(1)
    assert "dunevoice:listening" not in active_locks()
    assert prefs().get("listening") == "false"
    print("PASS: stop releases microphone and wake lock")


if __name__ == "__main__":
    main()
