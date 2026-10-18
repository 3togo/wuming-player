package com.wuming.android.tv.cloud.opendrive;

import com.wuming.android.tv.utils.AppLog;
import com.wuming.tv.core.cloud.OpenDriveFailure;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpenDriveLogTest {

    private final List<RecordedEntry> entries = new ArrayList<>();

    @After
    public void tearDown() {
        OpenDriveLog.setSink(null);
    }

    @Test
    public void resolveFailureClassifiesOpenDriveFailureReason() {
        installSink();
        var failure = new OpenDriveFailure("quark", OpenDriveFailure.Reason.QUOTA_EXHAUSTED, 429, 23018L,
                new IOException("insufficient"));
        OpenDriveLog.resolveFailure("quark", "item-1", failure);
        assertEquals(1, entries.size());
        var entry = entries.get(0);
        assertEquals(AppLog.Level.WARN, entry.level);
        assertEquals("OpenDrive", entry.tag);
        assertTrue(entry.message.contains("provider=quark"));
        assertTrue(entry.message.contains("item=item-1"));
        assertTrue(entry.message.contains("reason=QUOTA_EXHAUSTED"));
        assertTrue(entry.message.contains("http=429"));
        assertTrue(entry.message.contains("api=23018"));
    }

    @Test
    public void resolveFailureMarksUnclassifiedErrors() {
        installSink();
        OpenDriveLog.resolveFailure("uc", "item-2", new IOException("network"));
        var entry = entries.get(0);
        assertTrue(entry.message.contains("reason=UNCLASSIFIED"));
        assertTrue(entry.message.contains("error=IOException"));
    }

    @Test
    public void fallbackLogsProviderItemFromToAndReason() {
        installSink();
        OpenDriveLog.fallback("quark", "item-3", "save-transcode", "share-download", "quota-exhausted");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.INFO, entry.level);
        assertTrue(entry.message.contains("provider=quark"));
        assertTrue(entry.message.contains("from=save-transcode"));
        assertTrue(entry.message.contains("to=share-download"));
        assertTrue(entry.message.contains("reason=quota-exhausted"));
    }

    @Test
    public void failoverLogsManifestSourceAndCount() {
        installSink();
        OpenDriveLog.failover("manifest-1", "source-fp", Set.of("source-fp", "source-fp-2"));
        var entry = entries.get(0);
        assertEquals(AppLog.Level.WARN, entry.level);
        assertTrue(entry.message.contains("manifest=manifest-1"));
        assertTrue(entry.message.contains("failedSource=source-fp"));
        assertTrue(entry.message.contains("totalFailed=2"));
    }

    @Test
    public void refreshLogsManifestAndReason() {
        installSink();
        OpenDriveLog.refresh("manifest-2", "url-expired");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.INFO, entry.level);
        assertTrue(entry.message.contains("manifest=manifest-2"));
        assertTrue(entry.message.contains("reason=url-expired"));
    }

    @Test
    public void resolveInvalidLogsProviderItemStatusAndMessage() {
        installSink();
        OpenDriveLog.resolveInvalid("baidu", "item-4", "preview_not_authorized", "Baidu did not authorize");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.WARN, entry.level);
        assertTrue(entry.message.contains("provider=baidu"));
        assertTrue(entry.message.contains("status=preview_not_authorized"));
        assertTrue(entry.message.contains("msg=Baidu did not authorize"));
    }

    @Test
    public void httpErrorLogsAllFields() {
        installSink();
        OpenDriveLog.httpError("uc", 403, 41027L, "StatusException", "RequestDeniedByCallback");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.WARN, entry.level);
        assertTrue(entry.message.contains("provider=uc"));
        assertTrue(entry.message.contains("http=403"));
        assertTrue(entry.message.contains("api=41027"));
        assertTrue(entry.message.contains("error=StatusException"));
    }

    @Test
    public void httpBodyTruncatesTo512Chars() {
        installSink();
        var longBody = "x".repeat(1000);
        OpenDriveLog.httpBody("quark", 500, longBody);
        var entry = entries.get(0);
        assertTrue(entry.message.contains("body=" + "x".repeat(512)));
        assertFalse(entry.message.contains("x".repeat(513)));
    }

    @Test
    public void catvodFallbackTriggeredHandlesNullProvider() {
        installSink();
        OpenDriveLog.catvodFallbackTriggered("source-key", null, "flag-1");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.INFO, entry.level);
        assertTrue(entry.message.contains("provider=unknown"));
        assertTrue(entry.message.contains("flag=flag-1"));
    }

    @Test
    public void catvodFallbackTriggeredWithProvider() {
        installSink();
        OpenDriveLog.catvodFallbackTriggered("source-key", "quark", "flag-2");
        var entry = entries.get(0);
        assertTrue(entry.message.contains("provider=quark"));
    }

    @Test
    public void catvodFallbackFailedLogsErrorClass() {
        installSink();
        OpenDriveLog.catvodFallbackFailed("source-key", new IOException("timeout"));
        var entry = entries.get(0);
        assertEquals(AppLog.Level.WARN, entry.level);
        assertTrue(entry.message.contains("error=IOException"));
        assertTrue(entry.message.contains("timeout"));
    }

    @Test
    public void historyFallbackTriggeredLogsFlagOnly() {
        installSink();
        OpenDriveLog.historyFallbackTriggered("flag-3", "https://secret.example/video");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.INFO, entry.level);
        assertTrue(entry.message.contains("flag=flag-3"));
        assertFalse(entry.message.contains("secret.example"));
    }

    @Test
    public void historyFallbackUnavailableLogsMessage() {
        installSink();
        OpenDriveLog.historyFallbackUnavailable();
        var entry = entries.get(0);
        assertEquals(AppLog.Level.INFO, entry.level);
        assertTrue(entry.message.contains("unavailable"));
    }

    @Test
    public void authExpiredLogsProvider() {
        installSink();
        OpenDriveLog.authExpired("uc");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.WARN, entry.level);
        assertTrue(entry.message.contains("provider=uc"));
    }

    @Test
    public void providerUnavailableLogsProviderAndReason() {
        installSink();
        OpenDriveLog.providerUnavailable("alipan", "not configured");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.WARN, entry.level);
        assertTrue(entry.message.contains("provider=alipan"));
        assertTrue(entry.message.contains("reason=not configured"));
    }

    @Test
    public void unexpectedLogsErrorClassAndMessageAndThrowable() {
        installSink();
        var error = new RuntimeException("boom");
        OpenDriveLog.unexpected("quark", "item-5", error);
        var entry = entries.get(0);
        assertEquals(AppLog.Level.ERROR, entry.level);
        assertTrue(entry.message.contains("error=RuntimeException"));
        assertTrue(entry.message.contains("boom"));
        assertSame(error, entry.error);
    }

    @Test
    public void providerDebugEmitsAtDebugLevel() {
        installSink();
        OpenDriveLog.providerDebug("quark", "resolved", "mode=transcode");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.DEBUG, entry.level);
        assertTrue(entry.message.contains("provider=quark"));
        assertTrue(entry.message.contains("event=resolved"));
        assertTrue(entry.message.contains("mode=transcode"));
    }

    @Test
    public void providerInfoEmitsAtInfoLevel() {
        installSink();
        OpenDriveLog.providerInfo("uc", "proxy-upstream", "status=200 elapsedMs=15");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.INFO, entry.level);
        assertTrue(entry.message.contains("provider=uc"));
        assertTrue(entry.message.contains("event=proxy-upstream"));
    }

    @Test
    public void providerWarnEmitsAtWarnLevel() {
        installSink();
        OpenDriveLog.providerWarn("baidu", "quota-check-failed", "error=IOException");
        var entry = entries.get(0);
        assertEquals(AppLog.Level.WARN, entry.level);
        assertTrue(entry.message.contains("provider=baidu"));
        assertTrue(entry.message.contains("event=quota-check-failed"));
    }

    @Test
    public void setNullSinkRestoresDefaultAppLogSink() {
        installSink();
        OpenDriveLog.providerInfo("quark", "event-1", "details");
        assertFalse(entries.isEmpty());
        entries.clear();

        OpenDriveLog.setSink(null);
        OpenDriveLog.providerInfo("quark", "event-2", "details");
        assertTrue(entries.isEmpty());
    }

    private void installSink() {
        OpenDriveLog.setSink((level, tag, message, error) ->
                entries.add(new RecordedEntry(level, tag, message, error)));
    }

    private record RecordedEntry(AppLog.Level level, String tag, String message, Throwable error) {
    }
}
