package android.util;

/** Linkage-only attribute set. The desktop provider host does not inflate Android views. */
public class AttributeSet {
    public int getAttributeCount() {
        return 0;
    }

    public String getAttributeName(int index) {
        return null;
    }

    public String getAttributeValue(int index) {
        return null;
    }

    public String getAttributeValue(String namespace, String name) {
        return null;
    }

    public int getAttributeResourceValue(int index, int defaultValue) {
        return defaultValue;
    }

    public int getAttributeResourceValue(String namespace, String name, int defaultValue) {
        return defaultValue;
    }
}
