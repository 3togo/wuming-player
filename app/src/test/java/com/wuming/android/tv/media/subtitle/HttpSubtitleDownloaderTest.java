package com.wuming.android.tv.media.subtitle;

import static org.junit.Assert.*;

import com.wuming.tv.core.media.subtitle.SubtitleCandidate;
import com.wuming.tv.core.media.subtitle.SubtitleDownloadService;
import com.wuming.android.tv.testutil.BlockingHttpCall;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.InterruptedIOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class HttpSubtitleDownloaderTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void closedDownloaderNeverCreatesAnHttpCall() {
        HttpSubtitleDownloader downloader = new HttpSubtitleDownloader(new SubtitleDownloadService(),
                url -> { throw new AssertionError("Created a call after cancellation"); });
        downloader.close();
        assertThrows(InterruptedIOException.class, () -> downloader.download(candidate(), folder.getRoot().toPath()));
    }

    @Test public void closeCancelsOwnedCallAndLateResponseCannotWriteFiles() throws Exception {
        BlockingHttpCall call = new BlockingHttpCall("subtitle");
        call.release = new CountDownLatch(1);
        HttpSubtitleDownloader downloader = new HttpSubtitleDownloader(new SubtitleDownloadService(), url -> call);
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { downloader.download(candidate(), folder.getRoot().toPath()); }
            catch (Exception e) { error.set(e); }
        });
        worker.start();
        try {
            assertTrue(call.started.await(3, TimeUnit.SECONDS));
            downloader.close();
            assertTrue(call.cancelled);
        } finally {
            call.release.countDown();
            worker.join(3000);
        }
        assertFalse(worker.isAlive());
        assertTrue(error.get() instanceof InterruptedIOException);
        assertEquals(0, folder.getRoot().list().length);
    }

    private static SubtitleCandidate candidate() {
        return new SubtitleCandidate("test", "one", "one.srt", "en", "srt",
                URI.create("https://fixture.test/subtitle"), false, 50);
    }
}
