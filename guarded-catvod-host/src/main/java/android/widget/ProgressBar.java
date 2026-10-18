package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/** Linkage-only progress bar. The desktop provider host does not render UI. */
public class ProgressBar extends View {
    public ProgressBar(Context context) {
        super(context);
    }

    public ProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setProgress(int progress) {
    }

    public int getProgress() {
        return 0;
    }

    public void setIndeterminate(boolean indeterminate) {
    }
}
