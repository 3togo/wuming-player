package android.provider;

import android.content.ContentResolver;

/** Linkage-only settings provider. The desktop host returns empty/default values. */
public class Settings {
    public static final class Secure {
        public static String getString(ContentResolver resolver, String name) {
            return null;
        }

        public static int getInt(ContentResolver resolver, String name, int def) {
            return def;
        }

        public static final String ANDROID_ID = "android_id";
    }

    public static final class System {
        public static String getString(ContentResolver resolver, String name) {
            return null;
        }

        public static int getInt(ContentResolver resolver, String name, int def) {
            return def;
        }
    }

    public static final class Global {
        public static String getString(ContentResolver resolver, String name) {
            return null;
        }

        public static int getInt(ContentResolver resolver, String name, int def) {
            return def;
        }
    }
}
