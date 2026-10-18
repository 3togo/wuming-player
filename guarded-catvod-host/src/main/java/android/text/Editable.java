package android.text;

/** Linkage-only editable CharSequence. The desktop provider host does not render text. */
public class Editable implements CharSequence {
    private final StringBuilder value = new StringBuilder();

    public Editable append(CharSequence text) {
        value.append(text);
        return this;
    }

    public Editable append(char text) {
        value.append(text);
        return this;
    }

    public Editable clear() {
        value.setLength(0);
        return this;
    }

    @Override
    public int length() {
        return value.length();
    }

    @Override
    public char charAt(int index) {
        return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return value.subSequence(start, end);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
