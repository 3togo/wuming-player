package android.content;

/** Linkage-only clipboard manager. The desktop provider host does not bridge the system clipboard. */
public class ClipboardManager {
    public void setPrimaryClip(ClipData clip) {
    }

    public ClipData getPrimaryClip() {
        return null;
    }

    public CharSequence getPrimaryClipDescription() {
        return null;
    }

    public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener listener) {
    }

    public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener listener) {
    }

    public interface OnPrimaryClipChangedListener {
        void onPrimaryClipChanged();
    }
}
