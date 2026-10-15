"""Check distributable APK contents and ARM64 16KB native compatibility."""
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    for kind in ("tv", "phone"):
        apk = ROOT / "build" / kind / ("dune-voice-" + kind + ".apk")
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            assert "assets/LICENSE" in names and "assets/NOTICE" in names
            assert "assets/wake-test.pcm" not in names
            dex = b"".join(archive.read(name) for name in names if name.endswith(".dex"))
            for forbidden in (b"GatewayProbe", b"NativeProbe", b"DiscoveryProbe", b"ViewingProbe", b"ReplayProbe", b"ReplayFixture", b"192.168.3.1", b"192.168.2.116", b"dune.vladikshk.duckdns.org"):
                assert forbidden not in dex, (kind, forbidden)
            if kind == "phone":
                assert "assets/model-ru.zip" in names
                libs = [name for name in names if name.startswith("lib/arm64-v8a/") and name.endswith(".so")]
                assert any(name.endswith("libvosk.so") for name in libs)
                assert any(name.endswith("libjnidispatch.so") for name in libs)
                for name in libs:
                    elf = archive.read(name)
                    assert elf[:5] == b"\x7fELF\x02"
                    offset = struct.unpack_from("<Q", elf, 32)[0]
                    size, count = struct.unpack_from("<HH", elf, 54)
                    aligns = [struct.unpack_from("<Q", elf, offset + i * size + 48)[0] for i in range(count) if struct.unpack_from("<I", elf, offset + i * size)[0] == 1]
                    assert aligns and all(value >= 16384 for value in aligns), (name, aligns)
        print("PASS", kind, "release APK: licenses, no lab probes/personal configuration" + (", model and 16KB ARM64 libraries" if kind == "phone" else ""))


if __name__ == "__main__":
    main()
