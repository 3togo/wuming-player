#!/bin/bash
# Image Optimization Script for TVBoxOSC
# Converts PNG and JPG images to WebP format for smaller bundle size
#
# Prerequisites:
# - Install webp tools: sudo apt-get install webp
# - Or download from: https://developers.google.com/speed/webp/download

set -e

RES_DIR="app/src/main/res"
BACKUP_DIR="app/src/main/res_backup_$(date +%Y%m%d_%H%M%S)"

echo "=== Image Optimization Script ==="
echo "This script converts PNG and JPG images to WebP format"
echo ""

# Check if cwebp is available
if ! command -v cwebp &> /dev/null; then
    echo "ERROR: cwebp tool not found!"
    echo "Please install webp tools:"
    echo "  Ubuntu/Debian: sudo apt-get install webp"
    echo "  macOS: brew install webp"
    echo "  Or download from: https://developers.google.com/speed/webp/download"
    exit 1
fi

# Create backup
echo "Creating backup at $BACKUP_DIR..."
cp -r "$RES_DIR" "$BACKUP_DIR"

# Count images
total_images=$(find "$RES_DIR" -type f \( -name "*.png" -o -name "*.jpg" -o -name "*.jpeg" \) | wc -l)
echo "Found $total_images images to optimize"
echo ""

# Convert PNG to WebP
echo "Converting PNG images to WebP..."
find "$RES_DIR" -type f -name "*.png" | while read -r img; do
    webp="${img%.png}.webp"
    echo "Converting: $img"
    cwebp -q 90 "$img" -o "$webp"
    rm "$img"
done

# Convert JPG to WebP
echo "Converting JPG images to WebP..."
find "$RES_DIR" -type f \( -name "*.jpg" -o -name "*.jpeg" \) | while read -r img; do
    webp="${img%.*}.webp"
    echo "Converting: $img"
    cwebp -q 90 "$img" -o "$webp"
    rm "$img"
done

echo ""
echo "=== Optimization Complete ==="
echo "Original images backed up to: $BACKUP_DIR"
echo ""
echo "Next steps:"
echo "1. Test the app to ensure all images display correctly"
echo "2. If issues occur, restore from backup: cp -r $BACKUP_DIR/* $RES_DIR/"
echo "3. Once verified, delete backup: rm -rf $BACKUP_DIR"
