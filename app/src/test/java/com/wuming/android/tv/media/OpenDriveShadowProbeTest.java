package com.wuming.android.tv.media;

import com.wuming.tv.core.media.OpenDriveShadowProbe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenDriveShadowProbeTest {

    private final OpenDriveShadowProbe probe = AndroidMediaPipelineFactory.shadowProbe();

    @Test
    public void directHttpCandidateIsByteEquivalentToCurrentPlayback() {
        String current = "https://cdn.example/live.m3u8?token=secret";

        OpenDriveShadowProbe.Observation observation = probe.inspect(current);

        assertEquals(OpenDriveShadowProbe.Status.RESOLVED, observation.status());
        assertEquals(current, observation.candidate().url());
        assertTrue(observation.byteEquivalent());
    }

    @Test
    public void unsupportedInputDoesNotProduceCandidatePlayback() {
        assertEquals(OpenDriveShadowProbe.Status.NO_MATCH,
                probe.inspect("opendrive-file:legacy").status());
    }
}
