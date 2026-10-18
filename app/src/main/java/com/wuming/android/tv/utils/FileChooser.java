package com.wuming.android.tv.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;

import com.wuming.android.tv.App;
import com.wuming.android.tv.ui.activity.FileActivity;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.Consumer;
import java.util.function.Function;
import androidx.lifecycle.LifecycleOwner;
import com.wuming.android.tv.R;
import java.util.List;

public class FileChooser {

    public static final String EXTRA_INITIAL_DIRECTORY = "initial_directory";
    public static final String EXTRA_EXTENSION_FILTER = "extension_filter";

    private final ActivityResultLauncher<Intent> launcher;

    public static FileChooser from(ActivityResultLauncher<Intent> launcher) {
        return new FileChooser(launcher);
    }

    public FileChooser(ActivityResultLauncher<Intent> launcher) {
        this.launcher = launcher;
    }

    public void show() {
        show("*/*", new String[]{"*/*"}, null, null);
    }

    public void show(File initialDirectory) {
        show("*/*", new String[]{"*/*"}, null, initialDirectory);
    }

    public void show(String mimeType) {
        show(mimeType, new String[]{"*/*"}, null, null);
    }

    public void show(String[] mimeTypes) {
        show("*/*", mimeTypes, null, null);
    }

    public void show(String mimeType, String[] mimeTypes) {
        show(mimeType, mimeTypes, null, null);
    }

    public void show(File initialDirectory, String extensionFilter) {
        show("*/*", new String[]{"*/*"}, extensionFilter, initialDirectory);
    }

    private void show(String mimeType, String[] mimeTypes, String extensionFilter, File initialDirectory) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(mimeType);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        List<ResolveInfo> resolveInfos = App.get().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (Util.isLeanback() || resolveInfos.isEmpty() || resolveInfos.get(0).activityInfo.packageName.contains("frameworkpackagestubs")) {
            Intent fileIntent = new Intent(App.get(), FileActivity.class);
            if (initialDirectory != null) {
                fileIntent.putExtra(EXTRA_INITIAL_DIRECTORY, initialDirectory.getAbsolutePath());
            }
            if (extensionFilter != null) {
                fileIntent.putExtra(EXTRA_EXTENSION_FILTER, extensionFilter);
            }
            launcher.launch(fileIntent);
        } else {
            launcher.launch(Intent.createChooser(intent, ""));
        }
    }

    public static boolean isValid(Context context, Uri uri) {
        try {
            return DocumentsContract.isDocumentUri(context, uri) || ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    public static AutoCloseable importFile(LifecycleOwner owner, Uri uri, Consumer<String> success) {
        return importFile(owner, uri, Function.identity(), success);
    }

    /** Resolution, copying and optional parsing all run on a worker; delivery belongs to the owner. */
    public static <T> AutoCloseable importFile(LifecycleOwner owner, Uri uri,
                                              Function<String, T> transform, Consumer<T> success) {
        return new FileImportRequest<>(owner, () -> resolve(App.get(), uri), transform, success,
                () -> Notify.show(R.string.file_import_failed));
    }

    static FileImportRequest.ImportedFile resolve(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("Missing URI");
        try {
            if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
                return readableFile(uri.getPath());
            }
            if (!ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Unsupported URI scheme");
            }
            // Document IDs are opaque. Let the provider resolve them; do not split or parse IDs.
            String path = getDataColumn(context, uri);
            if (path != null) {
                File candidate = new File(path);
                if (candidate.isFile() && candidate.canRead()) return new FileImportRequest.ImportedFile(candidate, false);
            }
            String name = getNameColumn(context, uri);
            File directory = new File(context.getCacheDir(), "imports");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create import directory");
            FileImportRequest.ImportedFile copied = null;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Unable to open document");
                copied = copyImport(input, directory, name);
            } catch (Exception error) {
                if (copied != null) copied.discard();
                throw new IOException("Unable to copy document", error);
            }
            return copied;
        } catch (RuntimeException error) {
            throw new IOException("Unable to read document", error);
        }
    }

    static FileImportRequest.ImportedFile readableFile(String path) throws IOException {
        if (path == null) throw new IOException("Missing file path");
        File file = new File(path); // Uri.getPath() is already decoded; preserve literal '+' and '%'.
        if (!file.isFile() || !file.canRead()) throw new IOException("File is not readable");
        return new FileImportRequest.ImportedFile(file, false);
    }

    static FileImportRequest.ImportedFile copyImport(InputStream input, File directory, String name) throws IOException {
        String suffix = ".bin";
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && name.substring(dot).matches("\\.[A-Za-z0-9]{1,12}")) suffix = name.substring(dot);
        }
        File file = File.createTempFile("import-", suffix, directory);
        boolean complete = false;
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import canceled");
                    output.write(buffer, 0, count);
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import canceled");
            }
            complete = true;
            return new FileImportRequest.ImportedFile(file, true);
        } finally {
            if (!complete) file.delete();
        }
    }

    private static String getDataColumn(Context context, Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DATA};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return cursor.getString(cursor.getColumnIndexOrThrow(projection[0]));
        } catch (Exception e) {
            return null;
        }
    }

    private static String getNameColumn(Context context, Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return cursor.getString(cursor.getColumnIndexOrThrow(projection[0]));
        } catch (Exception e) {
            return null;
        }
    }

    public static Uri getFilesUri() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            return MediaStore.Files.getContentUri("external");
        }
    }

}
