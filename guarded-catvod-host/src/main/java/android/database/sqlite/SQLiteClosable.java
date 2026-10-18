package android.database.sqlite;

/** Android closes databases through this base type; providers only ever call {@link #close()}. */
public abstract class SQLiteClosable {

    public void close() {
    }
}
