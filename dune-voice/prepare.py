"""Copy resources and license notices for the TV APK; nothing is downloaded."""
import argparse
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=("tv",))
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
    for name in ("LICENSE", "THIRD_PARTY.md", "NOTICE"):
        shutil.copyfile(ROOT / name, assets / name)


if __name__ == "__main__":
    main()
