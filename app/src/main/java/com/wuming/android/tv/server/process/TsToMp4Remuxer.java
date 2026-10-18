package com.wuming.android.tv.server.process;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.wuming.android.tv.App;
import com.wuming.android.tv.server.CastMediaAccess;
import com.wuming.android.tv.utils.AppLog;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/** Lossless TS-to-MP4 remuxing and segment downloading for cast relay. */
final class TsToMp4Remuxer {

    private static final String TAG = "CastMediaRelay";

    static final long MP4_REMUX_LIMIT = 32L * 1024 * 1024;
    private static final long MP4_CACHE_LIMIT = 64L * 1024 * 1024;
    private static final CastRemuxCache MP4_CACHE = new CastRemuxCache(MP4_CACHE_LIMIT, 8);

    private TsToMp4Remuxer() {
    }

    static CastRemuxCache.Lease obtainRemuxedMp4(CastMediaAccess.Relay relay, AggregatedMedia media) {
        CastRemuxCache.Key key = new CastRemuxCache.Key(relay.url(), relay.headers());
        try {
            return MP4_CACHE.open(key, () -> buildRemuxedMp4(media, key.headers()));
        } catch (IOException error) {
            AppLog.e(TAG, "TS to MP4 preparation failed; falling back to progressive TS", error);
            return null;
        }
    }

    private static File buildRemuxedMp4(AggregatedMedia media, Map<String, String> headers) throws IOException {
        File temp = null;
        File mp4 = null;
        try {
            temp = downloadSegments(media, headers);
            mp4 = File.createTempFile("cast-", ".mp4", relayContextDir());
            if (!remuxToMp4(temp, mp4)) return null;
            File result = mp4;
            mp4 = null;
            return result;
        } finally {
            if (temp != null) temp.delete();
            if (mp4 != null) mp4.delete();
        }
    }

    private static File relayContextDir() {
        File dir = App.get().getCacheDir();
        return dir != null ? dir : new File("/data/local/tmp");
    }

    private static File downloadSegments(AggregatedMedia media, Map<String, String> headers) throws IOException {
        long[] sizes = new long[media.segments.size()];
        for (int i = 0; i < sizes.length; i++) sizes[i] = media.starts[i + 1] - media.starts[i];
        return CastSegmentDownloader.download(relayContextDir(), sizes, MP4_REMUX_LIMIT,
                index -> RelayHttpClient.upstreamCall(media.segments.get(index), headers, null, null));
    }

    static boolean remuxToMp4(File tsFile, File mp4File) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        long videoSamples = 0, audioSamples = 0;
        try {
            extractor.setDataSource(tsFile.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            int videoIndex = -1;
            int audioIndex = -1;
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && videoIndex < 0) {
                    videoIndex = i;
                    videoFormat = format;
                } else if (mime.startsWith("audio/") && audioIndex < 0) {
                    audioIndex = i;
                    audioFormat = format;
                }
            }
            AppLog.i(TAG, "Remux: tsFile=" + tsFile.length() + " bytes, tracks=" + trackCount
                    + ", video=" + (videoIndex >= 0) + ", audio=" + (audioIndex >= 0));
            if (videoFormat == null) {
                AppLog.w(TAG, "Remux: no video track found in TS");
                return false;
            }
            muxer = new MediaMuxer(mp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideo = muxer.addTrack(videoFormat);
            int muxAudio = audioFormat == null ? -1 : muxer.addTrack(audioFormat);
            if (audioFormat != null) extractor.selectTrack(audioIndex);
            extractor.selectTrack(videoIndex);
            muxer.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocate(2 << 20);
            long[] lastPts = new long[trackCount];
            long[] ptsOffset = new long[trackCount];
            long configSamples = 0, skippedSamples = 0;
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            while (true) {
                if (Thread.currentThread().isInterrupted() || mp4File.length() > MP4_CACHE_LIMIT) return false;
                int track = extractor.getSampleTrackIndex();
                if (track < 0) break;
                int muxTrack = track == videoIndex ? muxVideo : muxAudio;
                if (muxTrack < 0) {
                    if (!extractor.advance()) break;
                    continue;
                }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                int extractorFlags = extractor.getSampleFlags();
                if (size == 0) {
                    configSamples++;
                    if (!extractor.advance()) break;
                    continue;
                }
                long pts = extractor.getSampleTime() + ptsOffset[track];
                if (pts <= lastPts[muxTrack]) {
                    ptsOffset[track] += lastPts[muxTrack] + 1000 - (extractor.getSampleTime() + ptsOffset[track]);
                    pts = lastPts[muxTrack] + 1000;
                    skippedSamples++;
                }
                lastPts[muxTrack] = pts;
                info.set(0, size, pts, muxerSampleFlags(extractorFlags));
                muxer.writeSampleData(muxTrack, buffer, info);
                if (muxTrack == muxVideo) videoSamples++;
                else audioSamples++;
                if (!extractor.advance()) break;
            }
            if (configSamples > 0 || skippedSamples > 0) {
                AppLog.i(TAG, "Remux: normalized " + skippedSamples + " backward-jumps, skipped "
                        + configSamples + " codec-config samples");
            }
            muxer.stop();
            boolean ok = !Thread.currentThread().isInterrupted()
                    && mp4File.length() > 0 && mp4File.length() <= MP4_CACHE_LIMIT;
            AppLog.i(TAG, "Remux: wrote videoSamples=" + videoSamples + " audioSamples=" + audioSamples
                    + ", mp4=" + mp4File.length() + " bytes, ok=" + ok);
            return ok;
        } catch (Exception e) {
            AppLog.e(TAG, "Remux error (videoSamples=" + videoSamples
                    + ", audioSamples=" + audioSamples + ")", e);
            return false;
        } finally {
            try { extractor.release(); }
            finally { if (muxer != null) muxer.release(); }
        }
    }

    static int muxerSampleFlags(int extractorFlags) {
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
            throw new IllegalArgumentException("Encrypted samples cannot be remuxed");
        }
        int muxerFlags = 0;
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            muxerFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            muxerFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
        }
        return muxerFlags;
    }
}
