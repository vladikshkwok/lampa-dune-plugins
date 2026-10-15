"""Exercise the actual SystemUI tile; changes only a disposable rooted emulator."""
import argparse
import re
import shlex
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
    component = package + "/.ListeningTile"
    path = "/data/data/" + package + "/shared_prefs/phone.xml"

    def adb(*command, content=None):
        if command[0] == "shell":
            command = ("shell", shlex.join(command[1:]))
        result = subprocess.run([args.adb, "-s", args.serial, *command], input=content, capture_output=True, text=True, timeout=25, check=True)
        return result.stdout

    def prefs():
        return {node.attrib["name"]: node.attrib.get("value", node.text) for node in ET.fromstring(adb("shell", "cat", path))}

    def wait_for(test, message, seconds=20):
        for _ in range(seconds * 2):
            if test():
                return
            time.sleep(0.5)
        raise AssertionError(message)

    assert adb("shell", "id", "-u").strip() == "0", "Requires disposable adb-root emulator"
    adb("shell", "am", "force-stop", package)
    original = adb("shell", "cat", path)
    owner = adb("shell", "stat", "-c", "%u:%g", path).strip()
    original_tiles = adb("shell", "settings", "get", "secure", "sysui_qs_tiles").strip()
    root = ET.fromstring(original)
    for node in list(root):
        if node.attrib["name"] in ("token", "address", "listening", "state"):
            root.remove(node)
    ET.SubElement(root, "string", name="token").text = "A" * 43
    ET.SubElement(root, "string", name="address").text = "10.0.2.2"
    ET.SubElement(root, "boolean", name="listening", value="false")
    ET.SubElement(root, "string", name="state").text = "Прослушивание выключено"
    try:
        adb("shell", "sh", "-c", "cat > " + path, content=ET.tostring(root, encoding="unicode"))
        adb("shell", "chown", owner, path)
        for permission in ("RECORD_AUDIO", "POST_NOTIFICATIONS"):
            adb("shell", "pm", "grant", package, "android.permission." + permission)
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "start", "-n", package + "/.MainActivity")
        adb("shell", "input", "keyevent", "3")
        adb("shell", "cmd", "statusbar", "add-tile", component)
        spec = "custom(" + component + ")"
        adb("shell", "settings", "put", "secure", "sysui_qs_tiles", spec + "," + original_tiles.replace(spec + ",", ""))
        adb("shell", "cmd", "statusbar", "expand-settings")
        time.sleep(2)
        adb("shell", "cmd", "statusbar", "click-tile", component)
        wait_for(lambda: prefs().get("state", "").startswith("Слушаю «Эй, Дюна»"), "Tile failed to start microphone")
        adb("shell", "cmd", "statusbar", "expand-settings")
        time.sleep(1)
        status = adb("shell", "dumpsys", "activity", "services", package)
        assert "isForeground=true" in status and "types=00000080" in status
        audio = adb("shell", "dumpsys", "audio").split("RecordActivityMonitor dump time:", 1)[1].split("Events log:", 1)[0]
        assert "active? true" in audio and "pack:" + package in audio and "silenced:false" in audio, audio[-2500:]
        print("PASS: actual SystemUI tile starts microphone FGS with unsilenced audio")
        adb("shell", "cmd", "statusbar", "click-tile", component)
        wait_for(lambda: prefs().get("listening") == "false", "Tile failed to stop")
        wait_for(lambda: "isForeground=true" not in adb("shell", "dumpsys", "activity", "services", package), "Microphone FGS remained active")
        power = adb("shell", "dumpsys", "power")
        locks = power[re.search(r"(?m)^Wake Locks:.*$", power).start():].split("\n\n", 1)[0]
        assert "dunevoice:listening" not in locks
        print("PASS: second tile tap stops microphone and releases wake lock")
    finally:
        adb("shell", "am", "force-stop", package)
        adb("shell", "sh", "-c", "cat > " + path, content=original)
        adb("shell", "chown", owner, path)
        adb("shell", "cmd", "statusbar", "remove-tile", component)
        if original_tiles == "null":
            adb("shell", "settings", "delete", "secure", "sysui_qs_tiles")
        else:
            adb("shell", "settings", "put", "secure", "sysui_qs_tiles", original_tiles)
        adb("shell", "cmd", "statusbar", "collapse")


if __name__ == "__main__":
    main()
