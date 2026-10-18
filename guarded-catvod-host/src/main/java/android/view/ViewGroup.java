package android.view;

/** Linkage-only companion to {@link View}; guarded providers cannot construct a desktop UI. */
public class ViewGroup extends View implements ViewParent {
    public ViewGroup() {
    }

    public ViewGroup(android.content.Context context) {
        super(context);
    }

    public ViewGroup(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }

    public ViewGroup(android.content.Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public static class LayoutParams {
        public int width;
        public int height;

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public MarginLayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
