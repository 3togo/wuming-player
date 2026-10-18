package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;

public class OpenDriveHttpTest {

    private static final int LIMIT = 5 * 1024 * 1024;

    @Test
    public void noContentSuccessRequiresExplicitOptIn() throws Exception {
        var request = new okhttp3.Request.Builder().url("https://fixture.example/trash").build();
        var client = responseClient(204, "");
        assertEquals(0, OpenDriveHttp.executeJsonResponse(request, client, true).body().size());
        org.junit.Assert.assertThrows(IOException.class,
                () -> OpenDriveHttp.executeJsonResponse(request, client, false));
    }

    @Test
    public void noContentOptInStillRejectsEmpty200AndHttpFailures() {
        var request = new okhttp3.Request.Builder().url("https://fixture.example/trash").build();
        org.junit.Assert.assertThrows(IOException.class,
                () -> OpenDriveHttp.executeJsonResponse(request, responseClient(200, ""), true));
        var error = org.junit.Assert.assertThrows(OpenDriveHttp.StatusException.class,
                () -> OpenDriveHttp.executeJsonResponse(request, responseClient(403, ""), true));
        assertEquals(403, error.code());
    }

    private static okhttp3.OkHttpClient responseClient(int status, String body) {
        // Application interceptor returns an offline response before any network I/O.
        return new okhttp3.OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(status).message("fixture").body(ResponseBody.create(body,
                                MediaType.parse("application/json"))).build()).build();
    }

    @Test
    public void readsUnknownLengthBodyWithinLimit() throws Exception {
        ResponseBody body = unknownLength("{\"ok\":true}");

        assertEquals("{\"ok\":true}", OpenDriveHttp.readLimited(body));
    }

    @Test
    public void rejectsUnknownLengthBodyBeforeReadingItUnbounded() throws Exception {
        Buffer data = new Buffer();
        data.write(new byte[LIMIT + 1]);
        ResponseBody body = unknownLength(data);

        try {
            OpenDriveHttp.readLimited(body);
            fail("Expected oversized chunked response to be rejected");
        } catch (IOException expected) {
            assertEquals("Cloud response is too large", expected.getMessage());
        }
        assertEquals(LIMIT + 1L, data.size());
    }

    @Test
    public void providerJsonSizeAboveFourGiBStaysLong() {
        assertEquals(5L * 1024L * 1024L * 1024L, OpenDriveHttp.number(
                JsonParser.parseString("{\"size\":5368709120}").getAsJsonObject(),
                "size"));
    }

    // --- array() ---

    @Test
    public void arrayReturnsArrayByKey() {
        JsonObject obj = JsonParser.parseString(
                "{\"list\":[1,2,3],\"other\":\"x\"}").getAsJsonObject();
        JsonArray result = OpenDriveHttp.array(obj, "list");
        assertEquals(3, result.size());
        assertEquals(1, result.get(0).getAsInt());
    }

    @Test
    public void arrayReturnsDataWhenKeyIsNull() {
        JsonObject obj = JsonParser.parseString(
                "{\"data\":[\"a\",\"b\"]}").getAsJsonObject();
        JsonArray result = OpenDriveHttp.array(obj, null);
        assertEquals(2, result.size());
        assertEquals("a", result.get(0).getAsString());
    }

    @Test
    public void arrayReturnsEmptyForMissingKey() {
        JsonObject obj = JsonParser.parseString("{\"foo\":1}").getAsJsonObject();
        assertEquals(0, OpenDriveHttp.array(obj, "missing").size());
    }

    @Test
    public void arrayReturnsEmptyForNonArrayValue() {
        JsonObject obj = JsonParser.parseString("{\"foo\":\"string\"}").getAsJsonObject();
        assertEquals(0, OpenDriveHttp.array(obj, "foo").size());
    }

    @Test
    public void arrayReturnsEmptyForNullObject() {
        assertEquals(0, OpenDriveHttp.array(null, "key").size());
    }

    // --- first() ---

    @Test
    public void firstReturnsFirstNonBlankValue() {
        JsonObject obj = JsonParser.parseString(
                "{\"a\":\"\",\"b\":\"found\",\"c\":\"ignored\"}").getAsJsonObject();
        assertEquals("found", OpenDriveHttp.first(obj, "a", "b", "c"));
    }

    @Test
    public void firstReturnsEmptyWhenAllBlank() {
        JsonObject obj = JsonParser.parseString(
                "{\"a\":\"\",\"b\":\"  \"}").getAsJsonObject();
        assertEquals("", OpenDriveHttp.first(obj, "a", "b"));
    }

    @Test
    public void firstReturnsEmptyForMissingKeys() {
        JsonObject obj = new JsonObject();
        assertEquals("", OpenDriveHttp.first(obj, "x", "y"));
    }

    @Test
    public void firstReturnsEmptyForNullObject() {
        assertEquals("", OpenDriveHttp.first(null, "key"));
    }

    // --- Consolidated helpers (string, number, bool, object) ---

    @Test
    public void stringReturnsValueForKey() {
        JsonObject obj = JsonParser.parseString("{\"name\":\"test\"}").getAsJsonObject();
        assertEquals("test", OpenDriveHttp.string(obj, "name"));
    }

    @Test
    public void stringReturnsEmptyForMissingKey() {
        JsonObject obj = new JsonObject();
        assertEquals("", OpenDriveHttp.string(obj, "missing"));
    }

    @Test
    public void stringReturnsEmptyForNullValue() {
        JsonObject obj = JsonParser.parseString("{\"name\":null}").getAsJsonObject();
        assertEquals("", OpenDriveHttp.string(obj, "name"));
    }

    @Test
    public void stringReturnsEmptyForNullObject() {
        assertEquals("", OpenDriveHttp.string(null, "key"));
    }

    @Test
    public void numberReturnsLongForKey() {
        JsonObject obj = JsonParser.parseString("{\"count\":42}").getAsJsonObject();
        assertEquals(42L, OpenDriveHttp.number(obj, "count"));
    }

    @Test
    public void numberReturnsZeroForMissingKey() {
        JsonObject obj = new JsonObject();
        assertEquals(0L, OpenDriveHttp.number(obj, "missing"));
    }

    @Test
    public void numberReturnsZeroForNullObject() {
        assertEquals(0L, OpenDriveHttp.number(null, "key"));
    }

    @Test
    public void boolReturnsTrueForKey() {
        JsonObject obj = JsonParser.parseString("{\"flag\":true}").getAsJsonObject();
        assertTrue(OpenDriveHttp.bool(obj, "flag"));
    }

    @Test
    public void boolReturnsFalseForMissingKey() {
        JsonObject obj = new JsonObject();
        assertFalse(OpenDriveHttp.bool(obj, "missing"));
    }

    @Test
    public void boolReturnsFalseForNullObject() {
        assertFalse(OpenDriveHttp.bool(null, "key"));
    }

    @Test
    public void objectReturnsObjectForKey() {
        JsonObject obj = JsonParser.parseString(
                "{\"data\":{\"inner\":1}}").getAsJsonObject();
        JsonObject result = OpenDriveHttp.object(obj, "data");
        assertEquals(1, result.size());
        assertTrue(result.has("inner"));
    }

    @Test
    public void objectReturnsEmptyForMissingKey() {
        JsonObject obj = new JsonObject();
        assertEquals(0, OpenDriveHttp.object(obj, "missing").size());
    }

    @Test
    public void objectReturnsEmptyForNullObject() {
        assertEquals(0, OpenDriveHttp.object(null, "key").size());
    }

    @Test
    public void objectReturnsSelfWhenKeyIsNull() {
        JsonObject obj = JsonParser.parseString("{\"x\":1}").getAsJsonObject();
        assertEquals(obj, OpenDriveHttp.object(obj, null));
    }

    // --- json() factory ---

    @Test
    public void jsonCreatesObjectFromStringEntries() {
        JsonObject result = OpenDriveHttp.json("name", "value", "count", 5);
        assertEquals("value", result.get("name").getAsString());
        assertEquals(5, result.get("count").getAsInt());
    }

    @Test
    public void jsonCreatesObjectFromBooleanEntries() {
        JsonObject result = OpenDriveHttp.json("flag", true, "other", false);
        assertTrue(result.get("flag").getAsBoolean());
        assertFalse(result.get("other").getAsBoolean());
    }

    @Test
    public void jsonCreatesObjectFromListEntries() {
        JsonObject result = OpenDriveHttp.json("items", java.util.List.of("a", "b"));
        assertTrue(result.get("items").isJsonArray());
        assertEquals(2, result.getAsJsonArray("items").size());
    }

    private static ResponseBody unknownLength(String value) {
        return unknownLength(new Buffer().writeUtf8(value));
    }

    private static ResponseBody unknownLength(Buffer data) {
        return new ResponseBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("application/json");
            }

            @Override
            public long contentLength() {
                return -1L;
            }

            @Override
            public okio.BufferedSource source() {
                return data;
            }
        };
    }
}
