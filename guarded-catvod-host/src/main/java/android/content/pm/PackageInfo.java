package android.content.pm;

public class PackageInfo {
    public String packageName;
    public String versionName = "0.1";
    public int versionCode = 1;
    public Signature[] signatures = new Signature[]{new Signature(new byte[0])};
    public ApplicationInfo applicationInfo;
}
