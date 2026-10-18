package com.wuming.android.tv.media.subtitle;

import com.wuming.tv.core.media.subtitle.SubtitleCandidate;
import com.wuming.tv.core.media.subtitle.SubtitleDownloadService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SubtitleDownloadServiceTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void extractsOnlySupportedFilesWithoutOverwriting() throws Exception {
        byte[] archive = zip(new Entry("folder/show.srt", "one"),
                new Entry("notes.txt", "ignored"), new Entry("show.ass", "two"));
        SubtitleCandidate candidate = candidate("archive", "zip", true);
        Path destination = temporary.newFolder("subs").toPath();
        SubtitleDownloadService service = new SubtitleDownloadService();

        List<Path> first = service.store(candidate, new ByteArrayInputStream(archive), destination);
        List<Path> second = service.store(candidate, new ByteArrayInputStream(archive), destination);

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertTrue(Files.exists(destination.resolve("show.srt")));
        assertTrue(Files.exists(destination.resolve("show-1.srt")));
    }

    @Test
    public void rejectsArchiveTraversal() throws Exception {
        byte[] archive = zip(new Entry("../escape.srt", "bad"));
        Path destination = temporary.newFolder("safe").toPath();

        assertThrows(IOException.class, () -> new SubtitleDownloadService().store(
                candidate("archive", "zip", true), new ByteArrayInputStream(archive), destination));
        try (var files = Files.list(destination)) { assertTrue(files.findAny().isEmpty()); }
    }

    @Test
    public void enforcesDownloadLimit() throws Exception {
        SubtitleDownloadService service = new SubtitleDownloadService(3, 20, 2);
        Path destination = temporary.newFolder("limited").toPath();

        assertThrows(IOException.class, () -> service.store(candidate("direct", "srt", false),
                new ByteArrayInputStream("four".getBytes(StandardCharsets.UTF_8)), destination));
    }

    @Test public void lateArchiveFailureRemovesOnlyFilesCreatedByThisImport() throws Exception {
        Path destination = temporary.newFolder("rollback").toPath();
        Path existing = Files.writeString(destination.resolve("good.srt"), "original");
        byte[] archive = zip(new Entry("good.srt", "new"), new Entry("../escape.srt", "bad"));
        assertThrows(IOException.class, () -> new SubtitleDownloadService().store(
                candidate("archive", "zip", true), new ByteArrayInputStream(archive), destination));
        assertEquals("original", Files.readString(existing));
        try (var files = Files.list(destination)) { assertEquals(List.of(existing), files.toList()); }
    }

    @Test public void skippedArchiveEntriesAlsoCountAgainstExpansionBudget() throws Exception {
        Path destination = temporary.newFolder("skipped").toPath();
        byte[] archive = zip(new Entry("good.srt", "abc"), new Entry("huge.bin", "x".repeat(4096)));
        assertThrows(IOException.class, () -> new SubtitleDownloadService(10000, 1024, 10).store(
                candidate("archive", "zip", true), new ByteArrayInputStream(archive), destination));
        try (var files = Files.list(destination)) { assertTrue(files.findAny().isEmpty()); }
    }

    @Test public void interruptedImportDoesNotCreateFiles() throws Exception {
        Path destination = temporary.newFolder("cancelled").toPath();
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> new SubtitleDownloadService().store(
                    candidate("direct", "srt", false), new ByteArrayInputStream(new byte[4]), destination));
        } finally { Thread.interrupted(); }
        try (var files = Files.list(destination)) { assertTrue(files.findAny().isEmpty()); }
    }

    @Test public void simultaneousDownloadsReserveDistinctFiles() throws Exception {
        Path destination = temporary.newFolder("concurrent").toPath();
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Path> operation = () -> {
            start.await();
            return new SubtitleDownloadService().store(candidate("same", "srt", false),
                    new ByteArrayInputStream("valid".getBytes(StandardCharsets.UTF_8)), destination).get(0);
        };
        try {
            var one = workers.submit(operation);
            var two = workers.submit(operation);
            start.countDown();
            Path first = one.get(3, java.util.concurrent.TimeUnit.SECONDS);
            Path second = two.get(3, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.Assert.assertNotEquals(first, second);
            assertEquals("valid", Files.readString(first));
            assertEquals("valid", Files.readString(second));
        } finally { workers.shutdownNow(); }
    }

    private static SubtitleCandidate candidate(String id, String format, boolean archive) {
        return new SubtitleCandidate("test", id, id + "." + format, "en", format,
                URI.create("https://example.com/" + id), archive, 50);
    }

    private static byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record Entry(String name, String content) {
    }
}
