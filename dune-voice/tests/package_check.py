"""Check the distributable TV APK: licenses in, no personal configuration."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    for kind in ("tv",):
        apk = ROOT / "build" / kind / ("dune-voice-" + kind + ".apk")
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            assert "assets/LICENSE" in names and "assets/NOTICE" in names
            dex = b"".join(archive.read(name) for name in names if name.endswith(".dex"))
            for forbidden in (b"192.168.3.1", b"192.168.2.116", b"dune.vladikshk.duckdns.org"):
                assert forbidden not in dex, (kind, forbidden)
        print("PASS", kind, "release APK: licenses, no personal configuration")


if __name__ == "__main__":
    main()
