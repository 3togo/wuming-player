package android.widget;

import android.content.Context;
import android.util.AttributeSet;

/** Linkage-only scroll view. The desktop provider host does not render UI. */
public class HorizontalScrollView extends FrameLayout {
    public HorizontalScrollView(Context context) {
        super(context);
    }

    public HorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
