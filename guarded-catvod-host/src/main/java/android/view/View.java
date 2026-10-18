package android.view;

/**
 * Linkage-only Android type for provider code whose non-UI path shares signatures with UI helpers.
 * Deliberately contains no UI behavior.
 */
public class View {
    public interface OnClickListener {
        void onClick(View view);
    }

    public interface OnFocusChangeListener {
        void onFocusChange(View view, boolean hasFocus);
    }

    public interface OnKeyListener {
        boolean onKey(View view, int keyCode, KeyEvent event);
    }

    public interface OnLongClickListener {
        boolean onLongClick(View view);
    }

    public interface OnTouchListener {
        boolean onTouch(View view, MotionEvent event);
    }

    public View() {
    }

    public View(android.content.Context context) {
    }

    public View(android.content.Context context, android.util.AttributeSet attrs) {
    }

    public View(android.content.Context context, android.util.AttributeSet attrs, int defStyleAttr) {
    }

    public View(android.content.Context context, android.util.AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    }
}
