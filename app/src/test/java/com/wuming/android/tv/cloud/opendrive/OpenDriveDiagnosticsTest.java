package com.wuming.android.tv.cloud.opendrive;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import okhttp3.*;

public class OpenDriveDiagnosticsTest {
    @Test public void eventsOmitSecretsAndDoNotConsumeResponse() throws Exception {
        var events = new ArrayList<String>();
        var client = new OkHttpClient.Builder()
                .eventListenerFactory(OpenDriveDiagnostics.factory(events::add))
                .addInterceptor(chain -> new Response.Builder().request(chain.request())
                        .protocol(Protocol.HTTP_1_1).code(200).message("secret-message")
                        .body(ResponseBody.create("secret-body", MediaType.get("text/plain"))).build())
                .build();
        var request = new Request.Builder().url("https://secret-host.example/secret-path?token=secret-query")
                .header("Cookie", "secret-cookie").build();
        try (var response = client.newCall(request).execute()) {
            assertEquals("secret-body", response.body().string());
        }
        assertFalse(events.isEmpty());
        assertFalse(events.toString().contains("secret"));
        assertTrue(events.get(0).contains("provider=other"));
    }

    @Test public void failureMessagesAndProviderCodeStringsCannotLeak() throws Exception {
        var events = new ArrayList<String>();
        var client = new OkHttpClient.Builder().eventListenerFactory(OpenDriveDiagnostics.factory(events::add))
                .addInterceptor(chain -> { throw new IOException("https://secret.example/token"); }).build();
        assertThrows(IOException.class, () -> client.newCall(new Request.Builder()
                .url("https://api.aliyundrive.com/secret").build()).execute());
        assertTrue(events.stream().anyMatch(event -> event.contains("phase=failed")));
        assertFalse(events.toString().contains("secret"));
        assertEquals("provider=alipan phase=api code=non_numeric", OpenDriveDiagnostics.apiSummary(
                "api.aliyundrive.com", OpenDriveHttp.json("code", "secret", "message", "secret")));
        assertEquals("provider=uc phase=api code=41027", OpenDriveDiagnostics.apiSummary(
                "pc-api.uc.cn", OpenDriveHttp.json("code", 41027)));
    }

    @Test public void brokenDiagnosticSinkDoesNotBreakPlayback() throws Exception {
        var client = new OkHttpClient.Builder().eventListenerFactory(OpenDriveDiagnostics.factory(
                event -> { throw new IllegalStateException("sink unavailable"); }))
                .addInterceptor(chain -> new Response.Builder().request(chain.request())
                        .protocol(Protocol.HTTP_1_1).code(204).message("")
                        .body(ResponseBody.create("", null)).build()).build();
        try (var response = client.newCall(new Request.Builder().url("https://fixture.example").build()).execute()) {
            assertEquals(204, response.code());
        }
    }
}
