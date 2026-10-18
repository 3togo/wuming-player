#!/bin/bash
# Generate AV1 / HEVC HDR10 and SDR test clips for HDR/AV1 playback validation
# Requires ffmpeg built with libsvtav1, libx265

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
OUTDIR="./hdr_test_media"
mkdir -p "${OUTDIR}"

# Common test parameters
DURATION=10
W_4K=3840
H_4K=2160
W_8K=7680
H_8K=4320
FPS=24

# 1. SDR baseline 4K HEVC (reference)
echo "Generating 4K SDR HEVC baseline clip"
ffmpeg -filter_complex "testsrc2=duration=${DURATION}:size=${W_4K}x${H_4K}:rate=${FPS}" \
-c:v libx265 -preset medium -crf 22 -pix_fmt yuv420p \
-t ${DURATION} -y "${OUTDIR}/4k_sdr_hevc.mp4"

# 2. 4K HDR10 HEVC (PQ 10bit)
echo "Generating 4K HDR10 HEVC clip"
ffmpeg -filter_complex "testsrc2=duration=${DURATION}:size=${W_4K}x${H_4K}:rate=${FPS},format=yuv420p10le" \
-c:v libx265 -preset medium -crf 22 \
-x265-params "transfer=st2084:colorprim=bt2020:colormatrix=bt2020nc:hdr10=1:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)" \
-pix_fmt yuv420p10le -t ${DURATION} -y "${OUTDIR}/4k_hdr10_hevc.mp4"

#3. 4K AV1 Main10 HDR10 (PQ 10bit)
echo "Generating 4K AV1 Main10 HDR10 clip"
ffmpeg -filter_complex "testsrc2=duration=${DURATION}:size=${W_4K}x${H_4K}:rate=${FPS},format=yuv420p10le" \
-c:v libsvtav1 -preset 6 -crf 28 -pix_fmt yuv420p10le \
-av1-params "color_primaries=bt2020:transfer_characteristics=smpte2084:matrix_coefficients=bt2020nc:high_bitdepth=1" \
-t ${DURATION} -y "${OUTDIR}/4k_hdr10_av1.mp4"

#4. 8K AV1 Main10 HDR10 (level 6.2)
echo "Generating 8K AV1 Main10 HDR10 clip"
ffmpeg -filter_complex "testsrc2=duration=${DURATION}:size=${W_8K}x${H_8K}:rate=${FPS},format=yuv420p10le" \
-c:v libsvtav1 -preset 6 -crf 32 -pix_fmt yuv420p10le \
-av1-params "color_primaries=bt2020:transfer_characteristics=smpte2084:matrix_coefficients=bt2020nc:high_bitdepth=1" \
-t ${DURATION} -y "${OUTDIR}/8k_hdr10_av1.mp4"

echo "All test media generated in ${OUTDIR}"
ls -lh "${OUTDIR}"
