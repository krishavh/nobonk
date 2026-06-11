#!/bin/bash
# build_and_install.sh
# Builds the debug APK and installs it to any connected device (USB or wireless ADB)
# Run this from Terminal: bash build_and_install.sh

set -e
cd "$(dirname "$0")"

echo ""
echo "========================================"
echo "  PersonDetection — Build & Install"
echo "========================================"
echo ""

# Check ADB is reachable
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
if [ ! -f "$ADB" ]; then
    echo "❌  adb not found at $ADB"
    echo "    Make sure Android SDK is installed."
    exit 1
fi

echo "📱  Connected devices:"
"$ADB" devices -l
echo ""

DEVICE_COUNT=$("$ADB" devices | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "❌  No device found. Make sure:"
    echo "    • Wireless debugging is on"
    echo "    • You've paired this machine (adb pair <ip>:<port>)"
    echo "    • Then connected (adb connect <ip>:<port>)"
    exit 1
fi

echo "🔨  Building debug APK (this takes ~30s first time)..."
echo ""
./gradlew assembleDebug --quiet

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌  Build failed — APK not found at $APK_PATH"
    exit 1
fi

echo ""
echo "✅  Build succeeded!"
echo "📦  Installing APK to device..."
echo ""

"$ADB" install -r "$APK_PATH"

echo ""
echo "🚀  Done! App installed. Launching..."
"$ADB" shell am start -n "com.persondetection.android/.MainActivity"
echo ""
echo "========================================"
echo "  All done — check your phone!"
echo "========================================"
