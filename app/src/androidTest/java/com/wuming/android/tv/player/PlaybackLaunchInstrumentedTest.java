package com.wuming.android.tv.player;

import static org.junit.Assert.*;

import android.content.Intent;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.ui.activity.VideoActivity;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlaybackLaunchInstrumentedTest {
    @Test public void inheritedUrlAndFileLaunchersUseTheFlavorActivity() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RecordingActivity activity = new RecordingActivity();
            String url = "https://example.test/8k.mp4";
            VideoActivity.start(activity, url);
            assertEquals(VideoActivity.class.getName(), activity.intent.getComponent().getClassName());
            assertEquals(SiteApi.PUSH, activity.intent.getStringExtra("key"));
            assertEquals(url, activity.intent.getStringExtra("id"));
            assertFalse(activity.intent.getBooleanExtra("collect", true));
            VideoActivity.file(activity, "/storage/emulated/0/8k.mp4");
            assertEquals("file:///storage/emulated/0/8k.mp4", activity.intent.getStringExtra("id"));
            assertEquals("8k.mp4", activity.intent.getStringExtra("name"));
            Intent last = activity.intent;
            VideoActivity.push(activity, null);
            VideoActivity.file(activity, "");
            assertSame(last, activity.intent);
        });
    }

    private static final class RecordingActivity extends FragmentActivity {
        Intent intent;
        @Override public String getPackageName() { return "com.wuming.android.tv"; }
        @Override public void startActivity(Intent intent) { this.intent = intent; }
    }
}
