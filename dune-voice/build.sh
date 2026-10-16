#!/bin/sh
# Standalone Android APKs, no Gradle. Run on Windows Git Bash with SDK/JDK.
set -eu
HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
KIND="${1:-tv}"
case "$KIND" in tv|test) ;; *) echo 'usage: build.sh tv|test' >&2; exit 2;; esac
SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME}/Android/Sdk}"
BT="$SDK/build-tools/${DUNE_BUILD_TOOLS:-36.0.0}"
JAR="$SDK/platforms/${DUNE_PLATFORM:-android-36}/android.jar"
win() { cygpath -m "$1" 2>/dev/null || printf '%s\n' "$1"; }
tool() { for p in "$1.exe" "$1.bat" "$1"; do if [ -f "$p" ]; then printf '%s\n' "$p"; return; fi; done; echo "missing tool: $1" >&2; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
if [ "$KIND" = test ]; then
    OUT="$HERE/build/tests"; mkdir -p "$OUT"
    find "$HERE/core" "$HERE/tests" -name '*.java' | while read -r f; do win "$f"; done > "$OUT/sources.txt"
    "$JAVAC" -encoding UTF-8 --release 11 -d "$(win "$OUT")" "@$(win "$OUT/sources.txt")"
    "$JAVA" -cp "$(win "$OUT")" CoreTest
    exit
fi
OUT="$HERE/build/$KIND"
rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
[ -f "$JAR" ] || { echo "missing Android SDK: $JAR" >&2; exit 1; }
python "$(win "$HERE/prepare.py")" "$KIND"
AAPT2="$(tool "$BT/aapt2")"; D8="$(tool "$BT/d8")"
ZIPALIGN="$(tool "$BT/zipalign")"; APKSIGNER="$(tool "$BT/apksigner")"
VC=$(date +%s)
VN="0.1.$(git -C "$HERE" rev-parse --short HEAD)"
TARGET=30
MANIFEST="$HERE/$KIND/AndroidManifest.xml"
"$AAPT2" compile --dir "$(win "$OUT/res")" -o "$(win "$OUT/res.zip")"
ASSETS="$(win "$OUT/assets")"
"$AAPT2" link -o "$(win "$OUT/base.apk")" -I "$(win "$JAR")" --manifest "$(win "$MANIFEST")" \
    --java "$(win "$OUT/gen")" --min-sdk-version 26 --target-sdk-version "$TARGET" --version-code "$VC" --version-name "$VN" -A "$ASSETS" "$(win "$OUT/res.zip")"
CLASSPATH="$(win "$JAR")"
find "$HERE/core" "$HERE/android" "$HERE/$KIND/src" "$OUT/gen" -name '*.java' | while read -r f; do win "$f"; done > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 --release 11 -Xlint:all -Xlint:-deprecation -cp "$CLASSPATH" -d "$(win "$OUT/classes")" "@$(win "$OUT/sources.txt")"
find "$OUT/classes" -name '*.class' | while read -r f; do win "$f"; done > "$OUT/classes.txt"
"$D8" --release --min-api 26 --lib "$(win "$JAR")" --output "$(win "$OUT/dex")" "@$(win "$OUT/classes.txt")"
python - "$(win "$OUT")" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'base.apk','a',zipfile.ZIP_DEFLATED) as archive:
    for dex in (root/'dex').glob('*.dex'): archive.write(dex,dex.name)
PY
"$ZIPALIGN" -f -P 16 4 "$(win "$OUT/base.apk")" "$(win "$OUT/aligned.apk")"
KS="${DUNE_VOICE_KEYSTORE:-$HOME/.android/dune-voice.jks}"
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    python -c 'import secrets; print(secrets.token_urlsafe(32))' > "$KS.pass"
    chmod 600 "$KS.pass" 2>/dev/null || true
    "$KEYTOOL" -genkeypair -keystore "$(win "$KS")" -storetype PKCS12 -storepass:file "$(win "$KS.pass")" -alias dune-voice -keyalg RSA -keysize 3072 -validity 36500 -dname 'CN=Dune Voice, O=vladikshk'
fi
"$APKSIGNER" sign --ks "$(win "$KS")" --ks-pass "file:$(win "$KS.pass")" --out "$(win "$OUT/dune-voice-$KIND.apk")" "$(win "$OUT/aligned.apk")"
"$APKSIGNER" verify "$(win "$OUT/dune-voice-$KIND.apk")"
echo "PASS built $KIND APK: $OUT/dune-voice-$KIND.apk"
