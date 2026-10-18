package android.widget;

import android.view.ViewGroup;

/** Linkage-only container type; layout and rendering are unsupported. */
public class FrameLayout extends ViewGroup {
    public FrameLayout() {
    }

    public FrameLayout(android.content.Context context) {
        super(context);
    }

    public FrameLayout(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }

    public FrameLayout(android.content.Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public static class LayoutParams extends ViewGroup.LayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
