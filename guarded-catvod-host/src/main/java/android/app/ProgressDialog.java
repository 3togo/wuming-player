package android.app;

/** Linkage-only dialog type; the desktop provider host cannot show dialogs. */
public class ProgressDialog extends Dialog {
    public ProgressDialog(android.content.Context context) {
        super();
    }

    public static ProgressDialog show(android.content.Context context, CharSequence title, CharSequence message) {
        return new ProgressDialog(context);
    }

    public void setMessage(CharSequence message) {
    }

    public void setTitle(CharSequence title) {
    }

    public void setCancelable(boolean cancelable) {
    }

    public void setProgressStyle(int style) {
    }

    public void setIndeterminate(boolean indeterminate) {
    }

    public void dismiss() {
    }
}
