package com.wuming.android.tv.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

public class HttpResponseTextTest {
    @Test public void readsValidTextAtExactLimit() throws Exception {
        try (Response response = response("valid", 5, 200)) {
            assertEquals("valid", HttpResponseText.read(response, 5));
        }
    }
    @Test public void rejectsUnknownAndIncorrectLengthBeyondActualByteLimit() {
        for (long declared : new long[]{-1, 1, 100}) {
            try (Response response = response("too large", declared, 200)) {
                assertThrows(IOException.class, () -> HttpResponseText.read(response, 5));
            }
        }
    }
    @Test public void boundsBytesRatherThanCharacters() {
        try (Response response = response("字幕", -1, 200)) {
            assertThrows(IOException.class, () -> HttpResponseText.read(response, 4));
        }
    }
    @Test public void rejectsHttpErrorsAndCancelledReads() {
        try (Response response = response("error", 5, 500)) {
            assertThrows(IOException.class, () -> HttpResponseText.read(response, 100));
        }
        Thread.currentThread().interrupt();
        try (Response response = response("valid", 5, 200)) {
            assertThrows(InterruptedIOException.class, () -> HttpResponseText.read(response, 100));
        } finally { Thread.interrupted(); }
    }
    private static Response response(String body, long length, int code) {
        return new Response.Builder().request(new Request.Builder().url("https://fixture.test").build())
                .protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(ResponseBody.create(new Buffer().writeUtf8(body), MediaType.get("text/plain; charset=utf-8"), length)).build();
    }
}
