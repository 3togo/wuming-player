package android.content;

import java.io.File;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal compatibility context. Add methods only when a supported provider proves they are needed. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final File dataDirectory;
    private final Map<String, SharedPreferences> preferences = new ConcurrentHashMap<>();

    public Context() {
        this(new File(System.getProperty("java.io.tmpdir"), "tvbox-guarded-context"));
    }

    public Context(File dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public Context getApplicationContext() {
        return this;
    }

    public File getFilesDir() {
        return child("files");
    }

    public File getCacheDir() {
        return child("cache");
    }

    public File getExternalCacheDir() {
        return child("external-cache");
    }

    public File getDatabasePath(String name) {
        return new File(child("databases"), name);
    }

    public ClassLoader getClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? Context.class.getClassLoader() : loader;
    }

    public String getPackageName() {
        return "com.wuming.tv.desktop.guardhost";
    }

    public PackageManager getPackageManager() {
        return new PackageManager(this);
    }

    public ApplicationInfo getApplicationInfo() {
        return getPackageManager().applicationInfo();
    }

    public File getDataDirectory() {
        return dataDirectory;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return preferences.computeIfAbsent(name == null ? "" : name, ignored -> new MemorySharedPreferences());
    }

    private File child(String name) {
        File directory = new File(dataDirectory, name);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create guarded host directory: " + directory);
        }
        return directory;
    }
}
