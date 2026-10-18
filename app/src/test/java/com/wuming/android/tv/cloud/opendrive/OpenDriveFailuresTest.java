package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.OpenDriveFailure;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpenDriveFailuresTest {
    @Test public void wrappedHttpErrorsKeepStatusAndDoNotInferAuthenticationFrom403() {
        var error = new IOException("secret", new OpenDriveHttp.StatusException(403));
        var failure = (OpenDriveFailure) OpenDriveFailures.describe("uc", error);
        assertEquals(OpenDriveFailure.Reason.ACCESS_DENIED, failure.reason());
        assertEquals(403, failure.httpStatus());
        assertSame(error, failure.getCause());
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test public void failedFallbackReportsFinalFailureInsteadOfSuppressedQuota() {
        var direct = new OpenDriveHttp.StatusException(404);
        direct.addSuppressed(new OpenDriveFailure("uc", OpenDriveFailure.Reason.QUOTA_EXHAUSTED));
        var failure = (OpenDriveFailure) OpenDriveFailures.describe("uc", direct);
        assertEquals(OpenDriveFailure.Reason.RESOURCE_UNAVAILABLE, failure.reason());
    }

    @Test public void measuredQuarkMissingFileRetainsCodesWithoutAddingRecovery() {
        var failure = (OpenDriveFailure) OpenDriveFailures.describe("quark",
                new QuarkNetwork.QuarkHttpException(404, "/secret", 21001, false));
        assertEquals(OpenDriveFailure.Reason.RESOURCE_UNAVAILABLE, failure.reason());
        assertEquals(21001, failure.apiCode());
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test public void typedTranscodeFailurePreservesQuarkCompatibility() {
        var error = new QuarkSmartQualityException();
        assertSame(error, OpenDriveFailures.describe("quark", error));
        assertTrue(QuarkSmartQualityException.matches(error.getMessage()));
        assertTrue(QuarkSmartQualityException.matches(
                "Quark smart quality unavailable: no accessible middle-quality stream"));
        assertFalse(QuarkSmartQualityException.matches(new OpenDriveFailure(
                "alipan", OpenDriveFailure.Reason.TRANSCODE_UNAVAILABLE).getMessage()));
    }

    @Test public void cancellationAndProgrammingErrorsAreNotReclassified() {
        var interrupted = new InterruptedException();
        assertSame(interrupted, OpenDriveFailures.describe("uc", interrupted));
        var wrapped = new IOException("cancelled save", interrupted);
        assertSame(wrapped, OpenDriveFailures.describe("uc", wrapped));
        var invalid = new IllegalArgumentException("bad handle");
        assertSame(invalid, OpenDriveFailures.describe("uc", invalid));
    }

    @Test public void http429IsClassifiedAsProviderError() {
        var error = new IOException("rate limited", new OpenDriveHttp.StatusException(429));
        var failure = (OpenDriveFailure) OpenDriveFailures.describe("quark", error);
        assertEquals(OpenDriveFailure.Reason.PROVIDER_ERROR, failure.reason());
        assertEquals(429, failure.httpStatus());
    }

    @Test public void baiduQuotaExceptionIsRecognizedAsOpenDriveFailure() {
        var quota = new BaiduOpenDriveProvider.BaiduQuotaException("insufficient space");
        assertSame(quota, OpenDriveFailures.describe("baidu", quota));
        assertEquals(OpenDriveFailure.Reason.QUOTA_EXHAUSTED, quota.reason());
        assertEquals("baidu", quota.provider());
    }
}
