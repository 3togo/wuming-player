package android.os;

/** Linkage-only process utilities. */
public class Process {
    public static final int MY_PID = 0;
    public static final int MY_UID = 0;

    public static final int THREAD_PRIORITY_DEFAULT = 0;
    public static final int THREAD_PRIORITY_BACKGROUND = 10;

    public static int myPid() {
        return 0;
    }

    public static int myUid() {
        return 0;
    }

    public static int myTid() {
        return 0;
    }

    public static void setThreadPriority(int priority) {
    }
}
