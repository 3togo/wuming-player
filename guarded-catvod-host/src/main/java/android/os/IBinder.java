package android.os;

/** Linkage-only Binder interface. */
public interface IBinder {
    void pingBinder();

    boolean isBinderAlive();

    boolean linkToDeath(DeathRecipient recipient, int flags);

    interface DeathRecipient {
        void binderDied();
    }
}
