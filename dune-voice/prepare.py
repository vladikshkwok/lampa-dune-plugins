"""Fetch pinned public build inputs; never download anything at app runtime."""
import argparse
import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=("tv", "phone"))
    args = parser.parse_args()
    out = ROOT / "build" / args.kind
    resources = out / "res"
    if resources.exists():
        shutil.rmtree(resources)
    shutil.copytree(ROOT / "res", resources)
    extra = ROOT / args.kind / "res"
    if extra.exists():
        shutil.copytree(extra, resources, dirs_exist_ok=True)
    assets = out / "assets"
    if assets.exists():
        shutil.rmtree(assets)
    assets.mkdir(exist_ok=True)
    for name in ("LICENSE", "THIRD_PARTY.md", "NOTICE", "JNA-LICENSE.txt"):
        shutil.copyfile(ROOT / name, assets / name)
    if args.kind == "tv":
        return
    cache = ROOT / ".cache"
    cache.mkdir(exist_ok=True)
    libs = out / "libs"
    for path in (libs, out / "native"):
        if path.exists():
            shutil.rmtree(path)
    assets.mkdir(exist_ok=True)
    libs.mkdir(exist_ok=True)
    inputs = json.loads((ROOT / "dependencies.json").read_text())
    for item in inputs:
        target = cache / item["file"]
        if not target.exists():
            partial = target.with_suffix(target.suffix + ".part")
            with urllib.request.urlopen(item["url"], timeout=60) as response, partial.open("wb") as output:
                shutil.copyfileobj(response, output)
            partial.replace(target)
        if hashlib.sha256(target.read_bytes()).hexdigest() != item["sha256"]:
            raise RuntimeError("Checksum mismatch: " + item["file"])
        if item["file"].endswith(".aar"):
            with zipfile.ZipFile(target) as archive:
                (libs / (item["file"] + ".jar")).write_bytes(archive.read("classes.jar"))
                for name in archive.namelist():
                    if name.startswith(("jni/arm64-v8a/", "jni/x86_64/")) and name.endswith(".so"):
                        destination = out / "native" / ("lib/" + name[4:])
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        destination.write_bytes(archive.read(name))
        else:
            shutil.copyfile(target, assets / "model-ru.zip")
    print("Phone dependencies verified; Russian model bundled")


if __name__ == "__main__":
    main()
