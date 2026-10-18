package com.wuming.android.tv.player;

import android.os.SystemClock;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.tv.core.playback.PlaybackDiagnostics;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test telemetry contains numbers and error categories only, never URLs, headers or messages. */
final class PlaybackSmokeProbe implements AnalyticsListener {
    final PlaybackDiagnostics diagnostics = new PlaybackDiagnostics(SystemClock.elapsedRealtime());
    private final JSONArray samples = new JSONArray();
    private final Map<String, Integer> errors = new LinkedHashMap<>();
    private final Map<String, Integer> discontinuities = new LinkedHashMap<>();
    private java.util.List<String> playerErrorCauses = java.util.List.of();
    private ExoPlayer attached;
    private int generation;
    private boolean hasItem;
    int width, height;
    boolean playing, uiError;
    String errorCode;
    long frames;

    /** Called only on the player's application/main thread. */
    synchronized void sample(ExoPlayer player) {
        if (attached != player) {
            if (attached != null) attached.removeAnalyticsListener(this);
            attached = player;
            hasItem = player.getCurrentMediaItem() != null;
            generation++;
            player.addAnalyticsListener(this);
        }
        width = player.getVideoSize().width;
        height = player.getVideoSize().height;
        playing = player.isPlaying();
        errorCode = player.getPlayerError() == null ? null : player.getPlayerError().getErrorCodeName();
        DecoderCounters counters = player.getVideoDecoderCounters();
        if (counters != null) counters.ensureUpdated();
        frames = counters == null ? 0 : counters.renderedOutputBufferCount;
        long now = SystemClock.elapsedRealtime();
        long position = player.getCurrentPosition();
        long buffered = player.getTotalBufferedDuration();
        int state = player.getPlaybackState();
        diagnostics.observe(new PlaybackDiagnostics.Sample(now, position, buffered, frames, state, playing, generation));
        try {
            if (samples.length() < 800) samples.put(new JSONObject().put("atMs", now)
                    .put("positionMs", position).put("bufferedMs", buffered).put("frames", frames)
                    .put("state", state).put("playing", playing).put("generation", generation)
                    .put("width", width).put("height", height).put("diagnosis", diagnostics.getDiagnosis()));
        } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }

    @Override public synchronized void onPlayerError(EventTime time, androidx.media3.common.PlaybackException error) {
        playerErrorCauses = com.wuming.tv.core.playback.FailureDiagnostics.causeTypes(error);
    }
    @Override public synchronized void onLoadError(EventTime time, LoadEventInfo info,
            MediaLoadData data, IOException error, boolean canceled) {
        String category = error.getClass().getSimpleName();
        errors.put(category, errors.getOrDefault(category, 0) + 1);
    }
    @Override public synchronized void onPositionDiscontinuity(EventTime time,
            Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        String category = Integer.toString(reason);
        discontinuities.put(category, discontinuities.getOrDefault(category, 0) + 1);
    }
    @Override public synchronized void onMediaItemTransition(EventTime time,
            androidx.media3.common.MediaItem item, int reason) {
        if (hasItem) generation++;
        hasItem = item != null;
    }

    synchronized void save(String result) throws Exception {
        String run = InstrumentationRegistry.getArguments().getString("smoke_run_id", "manual");
        if (!run.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid diagnostic id");
        File dir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "smoke");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Diagnostic directory unavailable");
        JSONObject report = new JSONObject().put("result", result).put("samples", samples)
                .put("startupMs", diagnostics.getStartupMillis()).put("observedProgressMs", diagnostics.getProgressMillis())
                .put("renderedFrames", diagnostics.getRenderedFrames()).put("rebufferCount", diagnostics.getRebufferCount())
                .put("rebufferMs", diagnostics.getRebufferMillis()).put("maxStallMs", diagnostics.getMaxStallMillis())
                .put("resetCount", diagnostics.getResetCount()).put("loadErrors", new JSONObject(errors))
                .put("discontinuities", new JSONObject(discontinuities)).put("errorCode", errorCode)
                .put("playerErrorCauses", new JSONArray(playerErrorCauses))
                .put("width", width).put("height", height).put("uiError", uiError);
        File temp = new File(dir, run + ".tmp");
        Files.write(temp.toPath(), report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.move(temp.toPath(), new File(dir, run + ".json").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
