package android.content.pm;

import android.content.Context;

public class PackageManager {
    public static final int GET_SIGNATURES = 0x40;
    private final Context context;

    public PackageManager(Context context) { this.context = context; }

    public ApplicationInfo applicationInfo() {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = context.getPackageName();
        info.dataDir = context.getDataDirectory().getAbsolutePath();
        info.nativeLibraryDir = new java.io.File(context.getDataDirectory(), "lib").getAbsolutePath();
        info.sourceDir = "";
        return info;
    }

    public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
        if (!context.getPackageName().equals(packageName)) throw new NameNotFoundException(packageName);
        return applicationInfo();
    }

    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        if (!context.getPackageName().equals(packageName)) throw new NameNotFoundException(packageName);
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = applicationInfo();
        return info;
    }

    public static class NameNotFoundException extends Exception {
        public NameNotFoundException() { }
        public NameNotFoundException(String message) { super(message); }
    }
}
