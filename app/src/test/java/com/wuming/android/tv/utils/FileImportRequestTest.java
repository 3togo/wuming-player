package com.wuming.android.tv.utils;

import static org.junit.Assert.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class FileImportRequestTest {
    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void copyingAndParsingAreDeferredAndUiDeliveryReleasesLifecycle() throws Exception {
        Owner owner = new Owner();
        List<Runnable> worker = new ArrayList<>(), main = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        AtomicInteger parsed = new AtomicInteger();
        new FileImportRequest<>(owner,
                () -> FileChooser.copyImport(new ByteArrayInputStream("fixture".getBytes()), folder.getRoot(), "sub.srt"),
                path -> { parsed.incrementAndGet(); return path; }, delivered::add, () -> fail("Unexpected failure"),
                worker::add, main::add, main::remove);
        assertEquals(0, parsed.get());
        assertEquals(0, folder.getRoot().list().length);
        worker.remove(0).run();
        assertEquals(1, parsed.get());
        assertTrue(delivered.isEmpty());
        main.remove(0).run();
        assertEquals("fixture", Files.readString(new File(delivered.get(0)).toPath()));
        assertTrue(delivered.get(0).endsWith(".srt"));
        assertEquals(0, owner.lifecycle.getObserverCount());
        owner.destroy();
        assertTrue(new File(delivered.get(0)).exists());
    }

    @Test public void destroyedOwnerRemovesPendingDeliveryAndDeletesUndeliveredCopy() {
        Owner owner = new Owner();
        List<Runnable> worker = new ArrayList<>(), main = new ArrayList<>();
        new FileImportRequest<>(owner,
                () -> FileChooser.copyImport(new ByteArrayInputStream(new byte[3]), folder.getRoot(), "same.json"),
                path -> path, path -> fail("Stale callback"), () -> fail("Stale failure"),
                worker::add, main::add, main::remove);
        worker.remove(0).run();
        Runnable dequeued = main.get(0);
        owner.destroy();
        dequeued.run();
        assertTrue(main.isEmpty());
        assertEquals(0, folder.getRoot().list().length);
    }

    @Test public void cancellationBeforeExecutionNeverReadsProvider() {
        Owner owner = new Owner();
        List<Runnable> worker = new ArrayList<>(), main = new ArrayList<>();
        new FileImportRequest<>(owner, () -> { throw new AssertionError("Provider accessed after destruction"); },
                path -> path, path -> fail("Stale callback"), () -> fail("Stale failure"),
                worker::add, main::add, main::remove);
        owner.destroy();
        worker.remove(0).run();
        assertTrue(main.isEmpty());
    }

    @Test public void inaccessibleProviderReportsFailureAndReleasesObserver() {
        Owner owner = new Owner();
        List<Runnable> worker = new ArrayList<>(), main = new ArrayList<>();
        AtomicInteger errors = new AtomicInteger();
        new FileImportRequest<>(owner, () -> { throw new SecurityException("Revoked grant"); },
                path -> path, path -> fail("Unexpected success"), errors::incrementAndGet,
                worker::add, main::add, main::remove);
        worker.remove(0).run();
        main.remove(0).run();
        assertEquals(1, errors.get());
        assertEquals(0, owner.lifecycle.getObserverCount());
    }

    @Test public void duplicateAndUnsafeNamesCannotOverwriteOtherImports() throws Exception {
        FileImportRequest.ImportedFile one = FileChooser.copyImport(new ByteArrayInputStream(new byte[]{1}), folder.getRoot(), "../same.srt");
        FileImportRequest.ImportedFile two = FileChooser.copyImport(new ByteArrayInputStream(new byte[]{2}), folder.getRoot(), "../same.srt");
        assertNotEquals(one.file, two.file);
        assertEquals(folder.getRoot(), one.file.getParentFile());
        assertEquals(1, Files.readAllBytes(one.file.toPath())[0]);
        assertEquals(2, Files.readAllBytes(two.file.toPath())[0]);
    }

    @Test public void brokenAndInterruptedStreamsLeaveNoPartialFiles() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Disconnected"); }
        };
        assertThrows(IOException.class, () -> FileChooser.copyImport(broken, folder.getRoot(), "broken.json"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> FileChooser.copyImport(new ByteArrayInputStream(new byte[10]), folder.getRoot(), "cancel.json"));
        } finally { Thread.interrupted(); }
        assertEquals(0, folder.getRoot().list().length);
    }

    @Test public void borrowedFilePreservesLiteralPathAndSurvivesCanceledDelivery() throws Exception {
        File source = folder.newFile("literal+100%.srt");
        FileImportRequest.ImportedFile borrowed = FileChooser.readableFile(source.getAbsolutePath());
        assertEquals(source, borrowed.file);
        assertFalse(borrowed.owned);
        Owner owner = new Owner();
        List<Runnable> worker = new ArrayList<>(), main = new ArrayList<>();
        new FileImportRequest<>(owner, () -> borrowed, path -> path,
                path -> fail("Stale delivery"), () -> fail("Unexpected failure"), worker::add, main::add, main::remove);
        worker.remove(0).run();
        owner.destroy();
        assertTrue(source.exists());
    }

    @Test public void missingFileAndDirectoryAreRejected() {
        assertThrows(IOException.class, () -> FileChooser.readableFile(new File(folder.getRoot(), "missing").getPath()));
        assertThrows(IOException.class, () -> FileChooser.readableFile(folder.getRoot().getPath()));
    }

    private static class Owner implements LifecycleOwner {
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        Owner() { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE); }
        @Override public Lifecycle getLifecycle() { return lifecycle; }
        void destroy() { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); }
    }
}
