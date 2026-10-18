package com.wuming.android.tv.player.exo;

import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.tv.core.cloud.OpenDrivePlayback;
import com.wuming.tv.core.playback.PlaybackTransportPlan;
import com.github.catvod.net.OkHttp;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Protocol;

/** Same resolved original and headers, varying only bounded range scheduling. */
public final class AliRangeTransportComparison {
    public static void inspect(OpenDrivePlayback playback) throws Exception {
        var plan = PlaybackTransportPlan.parse(playback.url(), null, playback.headers());
        String previousDigest = null;
        for (int ranges : new int[]{8, 16}) {
            long window = ranges * 64L * 1024;
            var client = OkHttp.player().newBuilder().protocols(List.of(Protocol.HTTP_1_1))
                    .callTimeout(30, TimeUnit.SECONDS).addNetworkInterceptor(chain -> {
                        var response = chain.proceed(chain.request());
                        if (!response.isSuccessful()) {
                            Bundle failure = new Bundle();
                            failure.putInt("rangeHttpFailure", response.code());
                            String text = response.peekBody(2048).string();
                            var code = java.util.regex.Pattern.compile("<Code>([A-Za-z0-9_.-]+)</Code>").matcher(text);
                            if (code.find()) failure.putString("rangeProviderCode", code.group(1));
                            InstrumentationRegistry.getInstrumentation().sendStatus(0, failure);
                        }
                        return response;
                    }).build();
            var source = new ParallelRangeDataSource(
                    new OkHttpDataSource.Factory(client).setDefaultRequestProperties(plan.networkHeaders()),
                    plan.contentLength(), "ali-probe-" + ranges,
                    ParallelRangeDataSource.RangePolicy.buffered(window, ranges));
            long start = SystemClock.elapsedRealtime();
            long count = 0;
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            Bundle result = new Bundle();
            result.putString("rangeComparison", ranges + "x" + (window / ranges));
            try {
                source.open(new DataSpec(Uri.parse(playback.url())));
                byte[] buffer = new byte[16384];
                while (count < 1024 * 1024 && SystemClock.elapsedRealtime() - start < 30000) {
                    int read = source.read(buffer, 0, (int) Math.min(buffer.length, 1024 * 1024 - count));
                    if (read == C.RESULT_END_OF_INPUT) break;
                    digest.update(buffer, 0, read);
                    count += read;
                }
            } catch (Exception error) {
                result.putString("rangeError", error.getClass().getSimpleName());
            } finally { source.close(); }
            result.putLong("rangeBytes", count);
            result.putLong("rangeMs", SystemClock.elapsedRealtime() - start);
            if (count == 1024 * 1024) {
                String value = java.util.Base64.getEncoder().encodeToString(digest.digest());
                if (previousDigest != null) result.putBoolean("sameMediaBytes", previousDigest.equals(value));
                if (previousDigest != null) org.junit.Assert.assertEquals(previousDigest, value);
                previousDigest = value;
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        }
    }
}
