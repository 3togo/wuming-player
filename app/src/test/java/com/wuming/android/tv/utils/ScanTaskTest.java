package com.wuming.android.tv.utils;

import static org.junit.Assert.*;

import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.testutil.BlockingHttpCall;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ScanTaskTest {
    @Test public void stopBeforeBootstrapPreventsAllProbesAndReleasesListener() throws Exception {
        Fixture f = new Fixture();
        f.scan.start();
        f.scan.stop();
        f.scan.start();
        f.drain();
        assertTrue(f.calls.isEmpty());
        var listener = ScanTask.class.getDeclaredField("listener");
        listener.setAccessible(true);
        assertNull(listener.get(f.scan));
    }

    @Test public void subnetUsesEightWorkersRatherThanQueueingEveryAddress() {
        Fixture f = new Fixture();
        f.scan.start();
        f.work.remove(0).run();
        assertEquals(8, f.work.size());
        f.drain();
        assertEquals(254, f.calls.size());
        assertEquals(254, f.found.size());
    }

    @Test public void replacementAndStopRemoveQueuedResultsAndRejectDequeuedOnes() {
        Fixture f = new Fixture();
        f.scan.start("http://one");
        f.work.remove(0).run();
        f.work.remove(0).run();
        Runnable stale = f.main.get(0);
        f.scan.start("http://two");
        assertTrue(f.main.isEmpty());
        stale.run();
        assertTrue(f.found.isEmpty());
        f.work.remove(0).run();
        f.work.remove(0).run();
        f.scan.stop();
        assertTrue(f.main.isEmpty());
    }

    @Test public void completedRunDoesNotRetainFuturesCallsOrDeliveryCallbacks() throws Exception {
        Fixture f = new Fixture();
        f.scan.start("http://one");
        f.drain();
        var current = ScanTask.class.getDeclaredField("current");
        current.setAccessible(true);
        Object run = current.get(f.scan);
        for (String name : List.of("tasks", "calls", "deliveries")) {
            var field = run.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertTrue(((java.util.Set<?>) field.get(run)).isEmpty());
        }
    }

    @Test public void stopCancelsOnlyItsOwnHttpCallAndRejectsLateResult() throws Exception {
        Fixture one = new Fixture(), two = new Fixture();
        one.block = new CountDownLatch(1);
        one.scan.start("http://one");
        one.work.remove(0).run();
        Thread thread = new Thread(one.work.remove(0));
        thread.start();
        try {
            assertTrue(one.created.await(3, TimeUnit.SECONDS));
            BlockingHttpCall call = one.calls.get(0);
            assertTrue(call.started.await(3, TimeUnit.SECONDS));
            two.scan.start("http://two");
            two.drain();
            one.scan.stop();
            assertTrue(call.cancelled);
            assertFalse(two.calls.get(0).cancelled);
        } finally {
            one.block.countDown();
            thread.join(3000);
        }
        assertFalse(thread.isAlive());
        assertTrue(one.main.isEmpty());
        assertEquals(1, two.found.size());
    }

    @Test public void invalidOrOversizedResponsesAreIgnored() {
        Fixture f = new Fixture();
        f.body = "x".repeat(65537);
        f.scan.start("http://huge");
        f.drain();
        assertTrue(f.found.isEmpty());
        f.body = "valid";
        f.code = 500;
        f.scan.start("http://error");
        f.drain();
        assertTrue(f.found.isEmpty());
    }

    @Test public void invalidAddressesDoNotCrashOrGenerateInvalidSubnets() {
        assertTrue(ScanTask.addresses(null).isEmpty());
        assertTrue(ScanTask.addresses("http://[::1]:9978").isEmpty());
        assertTrue(ScanTask.addresses("http://999.1.2.3:9978").isEmpty());
        Fixture f = new Fixture();
        f.scan.start(null);
        assertTrue(f.work.isEmpty());
    }

    private static class Fixture {
        final List<Runnable> work = new ArrayList<>(), main = new ArrayList<>();
        final List<BlockingHttpCall> calls = new ArrayList<>();
        final List<Device> found = new ArrayList<>();
        final CountDownLatch created = new CountDownLatch(1);
        volatile CountDownLatch block;
        String body = "valid";
        int code = 200;
        final ScanTask scan = new ScanTask(found::add, work::add, main::add, main::remove,
                () -> "http://192.168.1.1:9978", url -> {
                    BlockingHttpCall call = new BlockingHttpCall(body);
                    call.code = code;
                    call.release = block;
                    calls.add(call);
                    created.countDown();
                    return call;
                }, text -> new Device());
        void drain() {
            while (!work.isEmpty()) work.remove(0).run();
            while (!main.isEmpty()) main.remove(0).run();
        }
    }
}
