package android.os;

/** Monotonic-clock subset used by guarded provider retry loops. */
public final class SystemClock {

    private SystemClock() {
    }

    public static long elapsedRealtime() {
        return System.nanoTime() / 1_000_000L;
    }

    public static long uptimeMillis() {
        return elapsedRealtime();
    }

    public static void sleep(long milliseconds) {
        if (milliseconds <= 0) return;
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
