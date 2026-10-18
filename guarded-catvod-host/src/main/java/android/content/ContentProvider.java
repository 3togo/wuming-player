package android.content;

import android.net.Uri;
import android.database.Cursor;

/** Linkage-only content provider. The desktop provider host does not expose content URIs. */
public abstract class ContentProvider {
    public void attachInfo(android.content.Context context, android.content.pm.ProviderInfo info) {
    }

    public abstract String getType(Uri uri);

    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    public boolean onCreate() {
        return false;
    }
}
