package android.widget;

import android.content.Context;

/** Linkage-only toast. The desktop provider host does not render UI. */
public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    private CharSequence text;
    private int duration = LENGTH_SHORT;

    public static Toast makeText(Context context, CharSequence text, int duration) {
        Toast toast = new Toast();
        toast.text = text;
        toast.duration = duration;
        return toast;
    }

    public void setText(CharSequence text) {
        this.text = text;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public void show() {
    }

    public void cancel() {
    }
}
