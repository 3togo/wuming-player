package android.content.res;

/** Linkage-only XML resource parser. The desktop provider host does not parse Android resource XML. */
public class XmlResourceParser {
    public int next() {
        return 1; // END_DOCUMENT
    }

    public int getEventType() {
        return 1;
    }

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

    public String getText() {
        return null;
    }

    public void close() {
    }
}
