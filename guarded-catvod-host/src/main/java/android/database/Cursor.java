package android.database;

/** Row access surface used by providers which cache their configuration in Android SQLite. */
public interface Cursor {

    boolean moveToFirst();

    String getString(int index);

    int getInt(int index);

    void close();
}
