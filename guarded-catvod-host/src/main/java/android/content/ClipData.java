package android.content;

/** Linkage-only clipboard payload. The desktop provider host does not bridge the system clipboard. */
public class ClipData {
    private final Item item;

    public ClipData(CharSequence label, Item item) {
        this.item = item;
    }

    public static ClipData newPlainText(CharSequence label, CharSequence text) {
        return new ClipData(label, new Item(text));
    }

    public Item getItemAt(int index) {
        return item;
    }

    public int getItemCount() {
        return item == null ? 0 : 1;
    }

    public CharSequence getDescription() {
        return null;
    }

    public static class Item {
        private final CharSequence text;
        private final String uri;

        public Item(CharSequence text) {
            this.text = text;
            this.uri = null;
        }

        public Item(String uri) {
            this.text = null;
            this.uri = uri;
        }

        public CharSequence getText() {
            return text;
        }

        public String getUri() {
            return uri;
        }
    }
}
