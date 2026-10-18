package android.view.inputmethod;

import android.os.IBinder;
import android.view.View;

/** Linkage-only input method manager. The desktop provider host has no soft keyboard. */
public class InputMethodManager {
    private static InputMethodManager instance;

    public static InputMethodManager getInstance() {
        if (instance == null) instance = new InputMethodManager();
        return instance;
    }

    public boolean showSoftInput(View view, int flags) {
        return false;
    }

    public boolean hideSoftInputFromWindow(IBinder windowToken, int flags) {
        return false;
    }

    public void toggleSoftInput(int showFlags, int hideFlags) {
    }

    public void restartInput(View view) {
    }
}
