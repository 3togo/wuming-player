package android.os;

import java.io.File;

/**
 * Linkage-only storage paths. Providers use this to locate a downloaded configuration file; the
 * guarded desktop host has no shared external storage, so every path maps to one private
 * per-process directory.
 */
public final class Environment {
    public static final String MEDIA_MOUNTED = "mounted";
    public static final String MEDIA_UNMOUNTED = "unmounted";
    public static final String MEDIA_REMOVED = "removed";
    public static final String DIRECTORY_DOWNLOADS = "Download";
    public static final String DIRECTORY_DOCUMENTS = "Documents";
    public static final String DIRECTORY_MOVIES = "Movies";
    public static final String DIRECTORY_MUSIC = "Music";
    public static final String DIRECTORY_PICTURES = "Pictures";
    public static final String DIRECTORY_DCIM = "DCIM";

    private Environment() {
    }

    public static File getExternalStorageDirectory() {
        return root();
    }

    public static File getExternalStoragePublicDirectory(String type) {
        return new File(root(), type == null ? "" : type);
    }

    public static File getExternalFilesDir(String type) {
        return new File(root(), "files");
    }

    public static File getDownloadCacheDirectory() {
        return new File(root(), "cache");
    }

    public static File getDataDirectory() {
        return root();
    }

    public static File getRootDirectory() {
        return root();
    }

    public static String getExternalStorageState() {
        return MEDIA_MOUNTED;
    }

    public static String getExternalStorageState(File path) {
        return MEDIA_MOUNTED;
    }

    public static boolean isExternalStorageEmulated() {
        return true;
    }

    public static boolean isExternalStorageRemovable() {
        return false;
    }

    private static File root() {
        File directory = new File(System.getProperty("java.io.tmpdir"), "tvbox-guarded-storage");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create guarded host storage directory: " + directory);
        }
        return directory;
    }
}
