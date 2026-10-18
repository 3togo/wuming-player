package android.content.pm;

/** Linkage-only provider metadata. */
public class ProviderInfo extends PackageItemInfo {
    public String authority;
    public boolean exported;
    public String[] pathPermissions;
}
