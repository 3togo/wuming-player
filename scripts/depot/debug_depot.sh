#!/bin/bash
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."

echo "=== Depot Debug Script ==="
echo ""
echo "This script will help debug the depot display issue."
echo ""

echo "1. Checking depot.json file..."
if [ -f "app/src/main/assets/depot.json" ]; then
    echo "   ✓ depot.json exists"
    VOD_COUNT=$(grep -o '"vod"' app/src/main/assets/depot.json | wc -l)
    LIVE_COUNT=$(grep -o '"live"' app/src/main/assets/depot.json | wc -l)
    echo "   File size: $(wc -c < app/src/main/assets/depot.json) bytes"
    echo "   Lines: $(wc -l < app/src/main/assets/depot.json)"
    echo "   VOD array found: $VOD_COUNT time(s)"
    echo "   Live array found: $LIVE_COUNT time(s)"
else
    echo "   ✗ depot.json NOT FOUND"
fi

echo ""
echo "2. Building APK..."
./gradlew assembleDebug
if [ $? -eq 0 ]; then
    echo "   ✓ Build successful"
else
    echo "   ✗ Build failed"
    exit 1
fi

echo ""
echo "3. Finding APK..."
APK=$(find app/build/outputs/apk -name "*.apk" -type f | grep -v "unaligned" | head -1)
if [ -n "$APK" ]; then
    echo "   ✓ APK found: $APK"
    echo "   Size: $(du -h "$APK" | cut -f1)"
else
    echo "   ✗ APK not found"
    exit 1
fi

echo ""
echo "4. Checking if device is connected..."
DEVICE=$(adb devices | grep -v "List" | grep "device" | head -1)
if [ -n "$DEVICE" ]; then
    echo "   ✓ Device connected: $DEVICE"
    
    echo ""
    echo "5. Installing APK..."
    adb install -r "$APK"
    if [ $? -eq 0 ]; then
        echo "   ✓ APK installed"
    else
        echo "   ✗ Installation failed"
        exit 1
    fi
    
    echo ""
    echo "6. Starting logcat monitoring..."
    echo "   Please open the app and go to Settings"
    echo "   Press Ctrl+C to stop monitoring"
    echo ""
    echo "=== LOGCAT OUTPUT ==="
    adb logcat -c
    adb logcat -s SettingActivity:D DepotManager:D DepotUtil:D DepotDialog:D
else
    echo "   ✗ No device connected"
    echo ""
    echo "Please connect a device and run:"
    echo "  adb install -r $APK"
    echo "  adb logcat -s SettingActivity:D DepotManager:D DepotUtil:D DepotDialog:D"
fi
