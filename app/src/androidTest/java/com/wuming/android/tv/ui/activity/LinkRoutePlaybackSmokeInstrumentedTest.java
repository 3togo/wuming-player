package com.wuming.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.R;
import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.cloud.opendrive.OpenDriveRuntime;
import com.wuming.android.tv.cloud.opendrive.OpenDriveSpider;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.setting.AppSettings;
import com.wuming.android.tv.ui.base.BaseVideoActivity;
import com.wuming.android.tv.ui.base.PlaybackActivity;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;

/**
 * Stateful device smoke test for the OpenDrive route playback lifecycle.
 *
 * <p>This test intentionally waits beyond the delayed detail reload which previously started a
 * second resolver about 30 seconds after the first frame. A transport response alone is not a
 * pass: the activity must render video, remain error-free, and keep advancing playback.</p>
 */
@RunWith(AndroidJUnit4.class)
public class LinkRoutePlaybackSmokeInstrumentedTest {

    private static final String TAG = "LinkRouteSmoke";
    private static final long START_TIMEOUT_MILLIS = 45_000L;
    private static final long STABILITY_WINDOW_MILLIS = 35_000L;
    private static final long POLL_MILLIS = 250L;
    private static final long STABILITY_POLL_MILLIS = 1_000L;
    private static final long MINIMUM_PROGRESS_MILLIS = 2_000L;
    private static final long MAXIMUM_POSITION_REGRESSION_MILLIS = 5_000L;
    private static final long MAXIMUM_STALL_MILLIS = 15_000L;
    private static final long HISTORY_TIMEOUT_MILLIS = 5_000L;

    @BeforeClass
    public static void symlinkDatabaseForSpider() {
        android.content.Context ctx = ApplicationProvider.getApplicationContext();
        File customDb = new File(ctx.getFilesDir(), "TV/database/tv");
        File stdDbDir = ctx.getDatabasePath("tv").getParentFile();
        if (stdDbDir == null) return;
        if (!stdDbDir.exists()) stdDbDir.mkdirs();
        for (String suffix : new String[]{"", "-shm", "-wal"}) {
            File src = new File(customDb.getPath() + suffix);
            File dst = new File(stdDbDir, "tv" + suffix);
            if (src.exists() && !dst.exists()) {
                try {
                    com.github.catvod.utils.Path.copy(src, dst);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to copy " + src + " to " + dst, e);
                }
            }
        }
    }

    @Test
    public void openDriveRouteRemainsPlayable() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        int originalRoute = AppSettings.getLinkRoute();
        LaunchTarget target = null;
        History originalHistory = null;
        try {
            AppSettings.putLinkRoute(AppSettings.LINK_ROUTE_BUILTIN);
            target = findTarget(arguments);
            originalHistory = copy(UserLibrary.get().findHistory(target.historyKey()));
            primeHistory(target, originalHistory);
            long openDrivePosition = playAndVerify(target, AppSettings.LINK_ROUTE_BUILTIN,
                    "opendrive");
            waitForHistory(target, openDrivePosition, true);
        } finally {
            restoreHistory(target, originalHistory);
            AppSettings.putLinkRoute(originalRoute);
        }
    }

    private static LaunchTarget findTarget(Bundle arguments) throws Exception {
        String sourceName = arguments.getString("source_name", "优汐");
        String query = arguments.getString("query", "本色回归");
        String requestedVodId = arguments.getString("vod_id", "http://123.666291.xyz/&8176").trim();
        String requestedFlag = arguments.getString("flag_name", "优汐智1").trim();
        String requestedEpisode = arguments.getString("episode_name", "").trim();
        String providerId = arguments.getString("provider_id", "uc").trim();
        VodConfig.get().ensureLoaded();
        Site source = VodConfig.get().getSites().stream()
                .filter(item -> item.getName().contains(sourceName)
                        || sourceName.contains(item.getName()))
                .findFirst().orElse(null);
        Assume.assumeNotNull("Configured source was not found: " + sourceName, source);
        Assume.assumeTrue("OpenDrive provider is unavailable: " + providerId,
                OpenDriveRuntime.engine().provider(providerId).available());
        Assume.assumeTrue("OpenDrive provider is not logged in: " + providerId,
                OpenDriveRuntime.engine().provider(providerId).authenticated());

        Result search = SiteApi.searchContent(source, query, false, "1");
        Map<String, Vod> candidates = new LinkedHashMap<>();
        if (!requestedVodId.isEmpty()) {
            search.getList().stream()
                    .filter(item -> requestedVodId.equals(item.getVodId()))
                    .findFirst().ifPresent(item -> candidates.put(item.getVodId(), item));
        } else {
            for (History history : UserLibrary.get().findHistoryByName(query)) {
                if (!source.getKey().equals(history.getSiteKey())) continue;
                search.getList().stream()
                        .filter(item -> history.getVodId().equals(item.getVodId()))
                        .findFirst().ifPresent(item -> candidates.put(item.getVodId(), item));
            }
            for (Vod item : search.getList()) {
                if (item.getVodName().contains(query) || query.contains(item.getVodName())) {
                    candidates.putIfAbsent(item.getVodId(), item);
                }
            }
        }
        if (!requestedVodId.isEmpty() && candidates.isEmpty()) {
            throw new AssertionError("Configured video ID was not found: " + requestedVodId);
        }

        List<String> inspected = new ArrayList<>();
        for (Vod candidate : candidates.values()) {
            Vod detail = SiteApi.detailContent(source.getKey(), candidate.getVodId()).getVod();
            if (detail == null) continue;
            for (Flag flag : detail.getFlags()) {
                if (!requestedFlag.isEmpty() && !containsEither(flag.getFlag(), requestedFlag)) {
                    continue;
                }
                for (Episode episode : flag.getEpisodes()) {
                    if (!requestedEpisode.isEmpty()
                            && !containsEither(episode.getName(), requestedEpisode)) continue;
                    String episodeUrl = episode.getUrl();
                    String routedProvider = OpenDriveSpider.isRoutedId(episodeUrl)
                            ? OpenDriveSpider.routedProviderId(episodeUrl) : "";
                    inspected.add(candidate.getVodId() + "/" + flag.getFlag()
                            + "/" + episode.getName());
                    if (!providerId.equals(routedProvider)) continue;
                    Log.i(TAG, "phase=fixture,site=" + source.getKey() + ",vodId="
                            + candidate.getVodId() + ",flag=" + flag.getFlag()
                            + ",episode=" + episode.getName() + ",provider=" + providerId);
                    return new LaunchTarget(source.getKey(), candidate.getVodId(),
                            candidate.getVodName(), candidate.getVodPic(), flag.getFlag(),
                            episode.getName(), episode.getUrl(), providerId);
                }
            }
        }
        throw new AssertionError("No " + providerId + " playback fixture matched source="
                + sourceName + ", query=" + query + ", vodId=" + requestedVodId
                + ", flag=" + requestedFlag + ", episode=" + requestedEpisode
                + "; inspected=" + inspected);
    }

    private static long playAndVerify(LaunchTarget target, int route, String label)
            throws Exception {
        AppSettings.putLinkRoute(route);
        assertEquals("Route preference was not persisted", route, AppSettings.getLinkRoute());
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), VideoActivity.class)
                .putExtra("collect", false)
                .putExtra("name", target.name)
                .putExtra("pic", target.pic)
                .putExtra("key", target.siteKey)
                .putExtra("id", target.vodId);
        Log.i(TAG, "phase=launch,label=" + label + ",route=" + routeName(route));
        long launchAt = SystemClock.elapsedRealtime();
        long launchRxBytes = uidRxBytes();
        try (ActivityScenario<VideoActivity> scenario = ActivityScenario.launch(intent)) {
            try {
                PlaybackSample ready = waitForReady(scenario, target, label, route);
                logThroughput("startup", label, route, launchAt, launchRxBytes,
                        PlaybackSample.empty(), ready);
                long stableAt = SystemClock.elapsedRealtime();
                long stableRxBytes = uidRxBytes();
                long startPosition = ready.position;
                long maximumPosition = startPosition;
                long lastProgressPosition = startPosition;
                long lastProgressAt = SystemClock.elapsedRealtime();
                String sourceFingerprint = ready.sourceFingerprint;
                long deadline = SystemClock.elapsedRealtime() + STABILITY_WINDOW_MILLIS;
                PlaybackSample latest = ready;
                while (SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(STABILITY_POLL_MILLIS);
                    latest = sample(scenario);
                    logSample("stable", label, route, latest);
                    assertTrue(failureMessage(label, route, latest), latest.error.isBlank());
                    assertTrue(failureMessage(label, route, latest), latest.videoWidth > 0
                            && latest.videoHeight > 0);
                    assertSelection(target, route, latest);
                    assertEquals("Playback source was replaced after route switch for " + label,
                            sourceFingerprint, latest.sourceFingerprint);
                    assertTrue("Playback position regressed after route switch for " + label
                                    + ": maximum=" + maximumPosition + ", current="
                                    + latest.position,
                            latest.position + MAXIMUM_POSITION_REGRESSION_MILLIS
                                    >= maximumPosition);
                    maximumPosition = Math.max(maximumPosition, latest.position);
                    if (latest.position > lastProgressPosition) {
                        lastProgressPosition = latest.position;
                        lastProgressAt = SystemClock.elapsedRealtime();
                    }
                    assertTrue("Playback remained stalled too long after route switch for "
                                    + label + ": state=" + stateName(latest.state)
                                    + ", position=" + latest.position,
                            SystemClock.elapsedRealtime() - lastProgressAt
                                    <= MAXIMUM_STALL_MILLIS);
                }
                assertTrue("Playback position did not advance enough for " + label
                                + ": start=" + startPosition + ", maximum=" + maximumPosition,
                        maximumPosition - startPosition >= MINIMUM_PROGRESS_MILLIS);
                logThroughput("stable", label, route, stableAt, stableRxBytes, ready, latest);
                capture(label + "-passed");
                Log.i(TAG, "phase=passed,label=" + label + ",route=" + routeName(route)
                        + ",state=" + stateName(latest.state)
                        + ",video=" + latest.videoWidth + "x" + latest.videoHeight
                        + ",position=" + latest.position + ",bufferedPosition="
                        + latest.bufferedPosition + ",totalBuffered="
                        + latest.totalBufferedDuration + ",host=" + latest.host
                        + ",source=" + latest.sourceFingerprint + ",flag=" + latest.flag
                        + ",episode=" + latest.episode + ",episodeRoute="
                        + latest.episodeProvider);
                return maximumPosition;
            } catch (Exception | AssertionError error) {
                capture(label + "-failed");
                Log.e(TAG, "phase=failed,label=" + label + ",route=" + routeName(route), error);
                throw error;
            }
        }
    }

    private static PlaybackSample waitForReady(ActivityScenario<VideoActivity> scenario,
                                               LaunchTarget target, String label, int route) {
        long deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MILLIS;
        PlaybackSample latest = PlaybackSample.empty();
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MILLIS);
            latest = sample(scenario);
            logSample("startup", label, route, latest);
            assertTrue(failureMessage(label, route, latest), latest.error.isBlank());
            if (latest.state == Player.STATE_READY
                    && latest.videoWidth > 0 && latest.videoHeight > 0 && latest.playing) {
                assertSelection(target, route, latest);
                return latest;
            }
        }
        throw new AssertionError("Playback never reached READY with video for " + label
                + ": " + latest);
    }

    private static PlaybackSample sample(ActivityScenario<VideoActivity> scenario) {
        AtomicReference<PlaybackSample> result = new AtomicReference<>(PlaybackSample.empty());
        scenario.onActivity(activity -> {
            try {
                PlayerManager manager = player(activity);
                View errorView = activity.findViewById(R.id.error);
                String error = errorView instanceof TextView text
                        && errorView.getVisibility() == View.VISIBLE
                        ? text.getText().toString().trim() : "";
                if (manager == null || manager.getPlayer() == null) {
                    result.set(new PlaybackSample(Player.STATE_IDLE, 0, 0, 0L, 0L, 0L,
                            false, error, "", "", "", "", ""));
                    return;
                }
                VideoSize size = manager.getPlayer().getVideoSize();
                String currentUrl = manager.getUrl();
                HttpUrl parsed = currentUrl == null || currentUrl.isBlank()
                        ? null : HttpUrl.parse(currentUrl);
                Flag flag = selectedFlag(activity);
                Episode episode = selectedEpisode(activity);
                String episodeUrl = episode == null ? "" : episode.getUrl();
                result.set(new PlaybackSample(manager.getPlaybackState(), size.width, size.height,
                        manager.getPosition(), manager.getPlayer().getBufferedPosition(),
                        manager.getPlayer().getTotalBufferedDuration(),
                        manager.getPlayer().isPlaying(), error,
                        parsed == null ? "" : parsed.host(), fingerprint(currentUrl),
                        flag == null ? "" : flag.getFlag(),
                        episode == null ? "" : episode.getName(), routedProvider(episodeUrl)));
            } catch (Exception reflectionFailure) {
                Log.e(TAG, "Unable to sample player state", reflectionFailure);
                result.set(new PlaybackSample(Player.STATE_IDLE, 0, 0, 0L, 0L, 0L,
                        false, reflectionFailure.getClass().getSimpleName(), "", "", "",
                        "", ""));
            }
        });
        return result.get();
    }

    private static PlayerManager player(VideoActivity activity) throws Exception {
        Method method = PlaybackActivity.class.getDeclaredMethod("player");
        method.setAccessible(true);
        return (PlayerManager) method.invoke(activity);
    }

    private static Flag selectedFlag(VideoActivity activity) throws Exception {
        Method method = BaseVideoActivity.class.getDeclaredMethod("getFlag");
        method.setAccessible(true);
        return (Flag) method.invoke(activity);
    }

    private static Episode selectedEpisode(VideoActivity activity) throws Exception {
        Method method = BaseVideoActivity.class.getDeclaredMethod("getEpisode");
        method.setAccessible(true);
        return (Episode) method.invoke(activity);
    }

    private static void logSample(String phase, String label, int route, PlaybackSample sample) {
        Log.i(TAG, "phase=" + phase + ",label=" + label + ",route=" + routeName(route)
                + ",state=" + stateName(sample.state) + ",video=" + sample.videoWidth
                + "x" + sample.videoHeight + ",position=" + sample.position
                + ",bufferedPosition=" + sample.bufferedPosition
                + ",totalBuffered=" + sample.totalBufferedDuration
                + ",playing=" + sample.playing + ",error=" + (!sample.error.isBlank())
                + ",host=" + sample.host + ",source=" + sample.sourceFingerprint
                + ",flag=" + sample.flag + ",episode=" + sample.episode
                + ",episodeRoute=" + sample.episodeProvider);
    }

    private static void logThroughput(String window, String label, int route, long startedAt,
                                      long startedRxBytes, PlaybackSample start,
                                      PlaybackSample end) {
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - startedAt);
        long received = delta(uidRxBytes(), startedRxBytes);
        Log.i(TAG, "phase=throughput,window=" + window + ",label=" + label
                + ",route=" + routeName(route) + ",elapsedMs=" + elapsed
                + ",uidRxBytes=" + received + ",uidBps="
                + (received < 0L ? -1L : received * 1_000L / elapsed)
                + ",positionDelta=" + (end.position - start.position)
                + ",bufferedPositionDelta="
                + (end.bufferedPosition - start.bufferedPosition)
                + ",totalBufferedDelta="
                + (end.totalBufferedDuration - start.totalBufferedDuration)
                + ",endHost=" + end.host);
    }

    private static long uidRxBytes() {
        long value = TrafficStats.getUidRxBytes(Process.myUid());
        return value == TrafficStats.UNSUPPORTED ? -1L : value;
    }

    private static long delta(long current, long previous) {
        return current < 0L || previous < 0L ? -1L : Math.max(0L, current - previous);
    }

    private static String failureMessage(String label, int route, PlaybackSample sample) {
        return "Playback failed for " + label + " (" + routeName(route) + "): " + sample;
    }

    private static String routeName(int route) {
        return route == AppSettings.LINK_ROUTE_BUILTIN ? "opendrive" : "catvod";
    }

    private static String stateName(int state) {
        return switch (state) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> Integer.toString(state);
        };
    }

    private static void assertSelection(LaunchTarget target, int route, PlaybackSample sample) {
        assertEquals("Wrong playback flag after route switch", target.flag, sample.flag);
        assertEquals("Wrong playback episode after route switch", target.episode, sample.episode);
    }

    private static boolean containsEither(String first, String second) {
        return first.contains(second) || second.contains(first);
    }

    private static String routedProvider(String value) {
        if (!OpenDriveSpider.isRoutedId(value)) return "";
        for (String prefix : List.of("opendrive-deferred:", "opendrive-file:")) {
            if (!value.startsWith(prefix)) continue;
            int end = value.indexOf(':', prefix.length());
            return end <= prefix.length() ? "" : value.substring(prefix.length(), end);
        }
        return OpenDriveSpider.isCanonicalId(value) ? "canonical" : "";
    }

    private static void primeHistory(LaunchTarget target, History original) {
        History history = original == null ? new History() : copy(original);
        history.setKey(target.historyKey());
        history.setCid(VodConfig.getCid());
        history.setVodName(target.name);
        history.setVodPic(target.pic);
        history.setVodFlag(target.flag);
        history.setVodRemarks(target.episode);
        history.setEpisodeUrl(target.rawEpisodeUrl);
        history.setMediaUri(null);
        history.setPosition(0L);
        if (history.getCreateTime() <= 0L) history.setCreateTime(System.currentTimeMillis());
        UserLibrary.get().saveHistory(history);
    }

    private static History waitForHistory(LaunchTarget target, long minimumPosition,
                                          boolean requireMediaUri) {
        long deadline = SystemClock.elapsedRealtime() + HISTORY_TIMEOUT_MILLIS;
        History latest = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = UserLibrary.get().findHistory(target.historyKey());
            if (latest != null && target.flag.equals(latest.getVodFlag())
                    && target.episode.equals(latest.getVodRemarks())
                    && latest.getPosition() + MAXIMUM_POSITION_REGRESSION_MILLIS
                    >= minimumPosition
                    && (!requireMediaUri || latest.hasMediaUri())) return latest;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Playback history was not persisted for " + target
                + ": minimumPosition=" + minimumPosition + ", requireMediaUri="
                + requireMediaUri + ", history=" + latest);
    }

    private static void restoreHistory(LaunchTarget target, History original) {
        if (target == null) return;
        History current = UserLibrary.get().findHistory(target.historyKey());
        if (current != null) UserLibrary.get().deleteHistory(current);
        if (original != null) UserLibrary.get().saveHistory(original);
    }

    private static History copy(History history) {
        return history == null ? null : History.objectFrom(history.toString());
    }

    private static void capture(String name) {
        Bitmap bitmap = null;
        try {
            bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .takeScreenshot();
            File directory = new File(ApplicationProvider.getApplicationContext()
                    .getExternalFilesDir(null), "link-route-smoke");
            if (!directory.exists() && !directory.mkdirs()) return;
            File output = new File(directory, safeName(name) + ".png");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            Log.i(TAG, "phase=screenshot,path=" + output.getAbsolutePath());
        } catch (Exception error) {
            Log.w(TAG, "Unable to save smoke-test screenshot", error);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static String safeName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static String fingerprint(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                result.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record LaunchTarget(String siteKey, String vodId, String name, String pic,
                                String flag, String episode, String rawEpisodeUrl,
                                String providerId) {

        private String historyKey() {
            return siteKey + AppDatabase.SYMBOL + vodId + AppDatabase.SYMBOL
                    + VodConfig.getCid();
        }
    }

    private record PlaybackSample(int state, int videoWidth, int videoHeight, long position,
                                  long bufferedPosition, long totalBufferedDuration,
                                  boolean playing, String error, String host,
                                  String sourceFingerprint, String flag, String episode,
                                  String episodeProvider) {
        static PlaybackSample empty() {
            return new PlaybackSample(Player.STATE_IDLE, 0, 0, 0L, 0L, 0L, false, "", "",
                    "", "", "", "");
        }
    }
}
