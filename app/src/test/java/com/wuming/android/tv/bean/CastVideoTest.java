package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;

public class CastVideoTest {

    @Test
    public void basicConstructorDelegatesCorrectly() {
        Map<String, String> headers = Collections.singletonMap("Referer", "https://example.com");
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 5000, headers);

        assertEquals("Test", video.getName());
        assertEquals("http://example.com/video.mp4", video.getUrl());
        assertEquals(5000, video.getPosition());
        assertEquals(0, video.getDuration());
        assertEquals(headers, video.getHeaders());
        assertNull(video.getNextName());
        assertNull(video.getNextUrl());
        assertNull(video.getSiteKey());
        assertNull(video.getFlag());
        assertNull(video.getAudioTrackFormat());
    }

    @Test
    public void nextEpisodeConstructorDelegatesCorrectly() {
        Map<String, String> headers = Collections.emptyMap();
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 0, headers,
                "Next Ep", "http://example.com/next.mp4");

        assertEquals("Test", video.getName());
        assertEquals("http://example.com/video.mp4", video.getUrl());
        assertEquals(0, video.getPosition());
        assertEquals(0, video.getDuration());
        assertEquals("Next Ep", video.getNextName());
        assertEquals("http://example.com/next.mp4", video.getNextUrl());
        assertNull(video.getSiteKey());
        assertNull(video.getFlag());
        assertNull(video.getAudioTrackFormat());
    }

    @Test
    public void siteKeyFlagConstructorDelegatesCorrectly() {
        Map<String, String> headers = Collections.emptyMap();
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 0, headers,
                "Next Ep", "http://example.com/next.mp4", "siteKey1", "flag1");

        assertEquals("Test", video.getName());
        assertEquals("Next Ep", video.getNextName());
        assertEquals("siteKey1", video.getSiteKey());
        assertEquals("flag1", video.getFlag());
        assertNull(video.getAudioTrackFormat());
    }

    @Test
    public void durationConstructorDelegatesCorrectly() {
        Map<String, String> headers = Collections.emptyMap();
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 1000, 60000,
                headers, "Next Ep", "http://example.com/next.mp4", "siteKey1", "flag1");

        assertEquals(1000, video.getPosition());
        assertEquals(60000, video.getDuration());
        assertEquals("siteKey1", video.getSiteKey());
        assertEquals("flag1", video.getFlag());
        assertNull(video.getAudioTrackFormat());
    }

    @Test
    public void fullConstructorDelegatesCorrectly() {
        Map<String, String> headers = Collections.singletonMap("User-Agent", "Test");
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 5000, 120000,
                headers, "Next Ep", "http://example.com/next.mp4", "siteKey1", "flag1", "aac");

        assertEquals("Test", video.getName());
        assertEquals("http://example.com/video.mp4", video.getUrl());
        assertEquals(5000, video.getPosition());
        assertEquals(120000, video.getDuration());
        assertEquals(headers, video.getHeaders());
        assertEquals("Next Ep", video.getNextName());
        assertEquals("http://example.com/next.mp4", video.getNextUrl());
        assertEquals("siteKey1", video.getSiteKey());
        assertEquals("flag1", video.getFlag());
        assertEquals("aac", video.getAudioTrackFormat());
    }

    @Test
    public void compactConstructorClampsNegativePosition() {
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", -100, 0,
                Collections.emptyMap(), null, null, null, null, null);

        assertEquals(0, video.getPosition());
    }

    @Test
    public void compactConstructorClampsNegativeDuration() {
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 0, -500,
                Collections.emptyMap(), null, null, null, null, null);

        assertEquals(0, video.getDuration());
    }

    @Test
    public void compactConstructorClampsBothNegative() {
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", -1, -1,
                Collections.emptyMap(), null, null, null, null, null);

        assertEquals(0, video.getPosition());
        assertEquals(0, video.getDuration());
    }

    @Test
    public void compactConstructorKeepsPositiveValues() {
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 10000, 300000,
                Collections.emptyMap(), null, null, null, null, null);

        assertEquals(10000, video.getPosition());
        assertEquals(300000, video.getDuration());
    }

    @Test
    public void nullUrlRemainsNull() {
        CastVideo video = new CastVideo("Test", null, 0, 0,
                Collections.emptyMap(), null, null, null, null, null);

        assertNull(video.getUrl());
    }

    @Test
    public void plainUrlPassesThroughUnchanged() {
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 0, 0,
                Collections.emptyMap(), null, null, null, null, null);

        assertEquals("http://example.com/video.mp4", video.getUrl());
    }

    @Test
    public void httpsUrlPassesThroughUnchanged() {
        CastVideo video = new CastVideo("Test", "https://cdn.example.com/stream.m3u8", 0, 0,
                Collections.emptyMap(), null, null, null, null, null);

        assertEquals("https://cdn.example.com/stream.m3u8", video.getUrl());
    }

    @Test
    public void nullHeadersAreAllowed() {
        CastVideo video = new CastVideo("Test", "http://example.com/video.mp4", 0, null);

        assertNull(video.getHeaders());
    }

    @Test
    public void nullNameIsAllowed() {
        CastVideo video = new CastVideo(null, "http://example.com/video.mp4", 0, 0,
                Collections.emptyMap(), null, null, null, null, null);

        assertNull(video.getName());
    }

    @Test
    public void emptyNameIsAllowed() {
        CastVideo video = new CastVideo("", "http://example.com/video.mp4", 0, 0,
                Collections.emptyMap(), null, null, null, null, null);

        assertEquals("", video.getName());
    }
}