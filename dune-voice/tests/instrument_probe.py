"""Run a lab-only APK instrumentation and check its actual result code."""
import argparse
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=("tv", "phone"))
    parser.add_argument("--adb")
    parser.add_argument("--nas")
    parser.add_argument("--container", default="dune-remote")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--discovery", action="store_true")
    parser.add_argument("--viewing", action="store_true")
    parser.add_argument("--replay", action="store_true")
    args = parser.parse_args()
    package = "org.duckdns.dunevoice." + args.kind
    probe = "GatewayProbe" if args.kind == "tv" else "NativeProbe"
    command = ["adb", "-s", args.serial, "shell", "am", "instrument", "-w", package + "/." + probe]
    if args.discovery:
        if args.kind != "phone":
            parser.error("discovery probe is phone-only")
        command[-1:-1] = ["-e", "discovery", "true"]
    if args.viewing:
        if args.kind != "phone" or not args.serial.startswith("emulator-"):
            parser.error("viewing lifecycle probe is emulator-only")
        command[-1:-1] = ["-e", "viewing", "true"]
    if args.replay:
        if args.kind != "phone" or not args.serial.startswith("emulator-"):
            parser.error("audio replay probe is emulator-only")
        command[-1:-1] = ["-e", "replay", "true"]
    if args.nas:
        command = ["ssh", "-o", "BatchMode=yes", args.nas, shlex.join(["docker", "exec", args.container, *command])]
    elif args.adb:
        command[0] = args.adb
    else:
        parser.error("provide --adb or --nas")
    result = subprocess.run(command, capture_output=True, text=True, timeout=180 if args.viewing else 90)
    if result.returncode or "INSTRUMENTATION_CODE: -1" not in result.stdout or "INSTRUMENTATION_RESULT: failure=" in result.stdout:
        raise RuntimeError((result.stdout + result.stderr)[-3000:])
    print(next(line for line in result.stdout.splitlines() if line.startswith("INSTRUMENTATION_RESULT: result=")))


if __name__ == "__main__":
    main()
