package com.wuming.tv.core.media;

import com.wuming.tv.core.media.MediaSourceDescriptor;

import com.wuming.tv.core.media.InputDecoderRegistry;

import com.wuming.tv.core.media.InputDecoder;

import com.wuming.tv.core.media.DecodeResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class InputDecoderTest {

    private final DirectHttpInputDecoder decoder = new DirectHttpInputDecoder();

    @Test
    public void directHttpIsDecodedLocallyAndCanBePersisted() {
        DecodeResult result = decoder.decode(" https://cdn.example/movie.m3u8#ignored ");

        assertEquals(DecodeResult.Status.DECODED, result.status());
        assertEquals("movie.m3u8", result.media().title());
        MediaSourceDescriptor source = result.media().sources().get(0);
        assertEquals("https://cdn.example/movie.m3u8", source.locator().get("url"));
        assertTrue(source.persistable());
        assertTrue(source.fingerprint().matches("sha256:[a-f0-9]{64}"));
    }

    @Test
    public void signedOrParameterizedUrlsRemainTransient() {
        DecodeResult result = decoder.decode("https://cdn.example/movie.m3u8?token=secret");

        assertEquals(DecodeResult.Status.DECODED, result.status());
        assertFalse(result.media().persistable());
        assertEquals("https://cdn.example/movie.m3u8?token=secret",
                result.media().sources().get(0).locator().get("url"));
    }

    @Test
    public void malformedMatchingInputStopsDecoderFallthrough() {
        InputDecoder fallback = input -> {
            throw new AssertionError("Malformed matched input must not fall through");
        };
        InputDecoderRegistry registry = new InputDecoderRegistry(List.of(decoder, fallback));

        assertEquals(DecodeResult.Status.INVALID,
                registry.decode("https://user:password@example.test/movie.mp4").status());
    }

    @Test
    public void unrelatedInputDoesNotMatch() {
        assertEquals(DecodeResult.Status.NO_MATCH, decoder.decode("ftp://example.test/a.mp4").status());
        assertEquals(DecodeResult.Status.NO_MATCH, decoder.decode(null).status());
    }
}
