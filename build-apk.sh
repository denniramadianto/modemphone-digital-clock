#!/bin/bash
# Build manual APK Jam Digital 7Seg tanpa Gradle.
# Pipeline: aapt2 -> kotlinc -> d8 -> zip -> zipalign -> apksigner
# Pemakaian: ./build-apk.sh [nama-output.apk]
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
export PATH="$HOME/workspace/jdk17/jdk-17.0.11+9/bin:$PATH"
SRC="$PROJ/app/src/main"
SDK="$HOME/workspace/android-sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
KOTLINC="$HOME/workspace/kotlinc/kotlinc/bin/kotlinc"
STDLIB="$HOME/workspace/kotlinc/kotlinc/lib/kotlin-stdlib.jar"
OUT_APK="${1:-$PROJ/app-release.apk}"
BUILD="$PROJ/.build-tmp"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/6] aapt2 compile (resources)..."
"$BT/aapt2" compile --dir "$SRC/res" -o "$BUILD/res.zip"

echo "[2/6] aapt2 link..."
"$BT/aapt2" link -o "$BUILD/app.apk" -I "$ANDROID_JAR" \
    --manifest "$SRC/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    "$BUILD/res.zip"

echo "[3/6] kotlinc..."
find "$SRC/java" -name "*.kt" > "$BUILD/sources.txt"
find "$BUILD/gen" -name "R.java" >> "$BUILD/sources.txt"
"$KOTLINC" -cp "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt" > "$BUILD/kotlinc.log" 2>&1
KOTLIN_EXIT=$?
grep -v "^warning:" "$BUILD/kotlinc.log" | head -20 || true
if [ $KOTLIN_EXIT -ne 0 ]; then echo "KOTLINC GAGAL"; exit 1; fi

echo "[4/6] d8 (dex)..."
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 \
    $(find "$BUILD/classes" -name "*.class") \
    "$STDLIB" \
    --output "$BUILD/dex"

echo "[5/6] tambah classes.dex + zipalign..."
(cd "$BUILD/dex" && zip -q -X ../app.apk classes.dex)
"$BT/zipalign" -f 4 "$BUILD/app.apk" "$BUILD/app-aligned.apk"

echo "[6/6] apksigner..."
"$BT/apksigner" sign --ks "$PROJ/release.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --out "$OUT_APK" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT_APK" | head -4

rm -rf "$BUILD"
echo "SELESAI: $OUT_APK ($(stat -c%s "$OUT_APK") byte)"
