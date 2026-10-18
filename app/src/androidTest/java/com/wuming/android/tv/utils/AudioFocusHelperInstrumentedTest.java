package com.wuming.android.tv.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.AudioManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public class AudioFocusHelperInstrumentedTest {

    @Test
    public void abandoningAfterTransientLossReleasesRequest() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AudioFocusHelper helper = AudioFocusHelper.create(context);
        Field request = AudioFocusHelper.class.getDeclaredField("focusRequest");
        request.setAccessible(true);
        try {
            helper.requestFocus();
            assertNotNull(request.get(helper));
            helper.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);

            helper.abandonFocus();
            helper.abandonFocus();

            assertFalse(helper.hasFocus());
            assertNull(request.get(helper));
            helper.requestFocus();
            assertNotNull(request.get(helper));
        } finally {
            helper.abandonFocus();
        }
    }

    @Test
    public void transientLossDuckAndGainMaintainCorrectFocusState() {
        Context context = ApplicationProvider.getApplicationContext();
        AudioFocusHelper helper = AudioFocusHelper.create(context);

        assertFalse(helper.hasFocus());
        helper.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(helper.hasFocus());
        helper.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertTrue(helper.hasFocus());
        helper.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertFalse(helper.hasFocus());
        helper.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(helper.hasFocus());
        helper.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        assertFalse(helper.hasFocus());
    }
}
