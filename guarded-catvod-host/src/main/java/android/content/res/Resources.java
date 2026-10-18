package android.content.res;

import android.graphics.drawable.Drawable;
import android.content.res.XmlResourceParser;

/** Linkage-only resources. The desktop provider host has no Android resource table. */
public class Resources {
    public String getString(int id) {
        return "";
    }

    public String getString(int id, Object... formatArgs) {
        return "";
    }

    public int getColor(int id) {
        return 0;
    }

    public Drawable getDrawable(int id) {
        return null;
    }

    public float getDimension(int id) {
        return 0f;
    }

    public int getIdentifier(String name, String defType, String defPackage) {
        return 0;
    }

    public XmlResourceParser getXml(int id) {
        return new XmlResourceParser();
    }

    public Configuration getConfiguration() {
        return new Configuration();
    }
}
