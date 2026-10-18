package android.widget;

import android.content.Context;
import android.util.AttributeSet;

/** Linkage-only scroll view. The desktop provider host does not render UI. */
public class ScrollView extends FrameLayout {
    public ScrollView(Context context) {
        super(context);
    }

    public ScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
