package android.app;

import android.content.Context;
import java.io.File;

public class Application extends Context {
    public Application() { super(); }
    public Application(File dataDirectory) { super(dataDirectory); }
}
