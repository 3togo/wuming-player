#!/bin/bash

# Leanback Lite Build Script
# Builds all leanback_lite variants

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."

echo "========================================="
echo "Building Leanback Lite Variants"
echo "========================================="

# Clean project
echo "Cleaning project..."
./gradlew clean

# Build debug variants
echo "Building debug variants..."
./gradlew assembleLeanback_liteArm64_v8aDebug
./gradlew assembleLeanback_liteArmeabi_v7aDebug

# Build release variants
echo "Building release variants..."
./gradlew assembleLeanback_liteArm64_v8aRelease
./gradlew assembleLeanback_liteArmeabi_v7aRelease

echo "========================================="
echo "Build Complete!"
echo "========================================="

# List generated APKs
echo "Generated APKs:"
find app/build/outputs/apk -name "*.apk" -type f | grep leanback_lite

echo ""
echo "APK sizes:"
find app/build/outputs/apk -name "*.apk" -type f | grep leanback_lite | while read apk; do
    size=$(du -h "$apk" | cut -f1)
    echo "$size - $(basename $apk)"
done
