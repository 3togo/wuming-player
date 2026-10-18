package android.os;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SystemClockTest {

    @Test
    public void exposesMonotonicMilliseconds() {
        long before = SystemClock.elapsedRealtime();
        SystemClock.sleep(2);
        assertTrue(SystemClock.uptimeMillis() >= before);
    }
}
