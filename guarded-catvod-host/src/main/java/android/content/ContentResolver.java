package android.content;

import android.net.Uri;
import android.database.Cursor;
import java.util.Map;

/** Linkage-only content resolver. The desktop provider host does not expose content URIs. */
public class ContentResolver {
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

    public String getType(Uri uri) {
        return null;
    }

    public void registerContentObserver(Uri uri, boolean notifyForDescendants, java.lang.Object observer) {
    }

    public void unregisterContentObserver(java.lang.Object observer) {
    }
}
