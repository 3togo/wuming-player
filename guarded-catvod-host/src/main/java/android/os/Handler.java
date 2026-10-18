package android.os;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Handler {
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "guarded-catvod-handler");
        thread.setDaemon(true);
        return thread;
    });

    public Handler() { }
    public Handler(Looper ignored) { }
    public boolean post(Runnable task) { EXECUTOR.execute(task); return true; }
    public boolean postDelayed(Runnable task, long delayMillis) {
        EXECUTOR.schedule(task, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        return true;
    }
    public void removeCallbacks(Runnable ignored) { }
}
