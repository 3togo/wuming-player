package android.preference;

import android.content.Context;
import android.content.SharedPreferences;

/** Linkage-only preference manager. Backed by the host's in-memory shared preferences. */
public class PreferenceManager {
    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return new android.content.MemorySharedPreferences();
    }

    public SharedPreferences getSharedPreferences() {
        return new android.content.MemorySharedPreferences();
    }
}
