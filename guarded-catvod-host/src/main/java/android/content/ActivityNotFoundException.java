package android.content;

/** Linkage-only exception type thrown by Android when an Intent cannot be resolved. */
public class ActivityNotFoundException extends RuntimeException {
    public ActivityNotFoundException() {
        super();
    }

    public ActivityNotFoundException(String message) {
        super(message);
    }
}
