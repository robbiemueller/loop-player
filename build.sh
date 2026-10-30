#!/usr/bin/env bash
# Builds Loop Player without Gradle on macOS/Linux: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Needs the Android SDK (one platform + build-tools, e.g. from Android Studio) and a JDK 17+ on PATH.
# Untested so far (build.ps1 is the tested one); the steps are identical.
#   ./build.sh               build (creates local.keystore here on first run)
#   ./build.sh --install     build, install over ADB and start it
#   KEYSTORE=my.keystore STOREPASS=secret ./build.sh
set -euo pipefail
VERSION_CODE=2
VERSION_NAME=1.1.0
TARGET_SDK=35

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { [ -d "$HOME/Library/Android/sdk" ] && SDK="$HOME/Library/Android/sdk" || SDK="$HOME/Android/Sdk"; }
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
KS="${KEYSTORE:-$HERE/local.keystore}"
PASS="${STOREPASS:-loopplayer}"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$JAR" -d "$OUT/classes" \
  $(find "$HERE/src" -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 21 --target-sdk-version "$TARGET_SDK" --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
(cd "$OUT/dex" && jar uf "$OUT/base.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias loopplayer -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" -dname 'CN=Loop Player'
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --min-sdk-version 21 --out "$OUT/LoopPlayer.apk" "$OUT/aligned.apk"
echo "Built $OUT/LoopPlayer.apk"

if [ "${1:-}" = "--install" ]; then
  adb install -r "$OUT/LoopPlayer.apk"
  adb shell mkdir -p /sdcard/Movies/loop
  adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez reload true
fi
