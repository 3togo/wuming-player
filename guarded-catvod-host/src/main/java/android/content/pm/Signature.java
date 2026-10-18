package android.content.pm;

public class Signature {
    private final byte[] value;
    public Signature(byte[] value) { this.value = value == null ? new byte[0] : value.clone(); }
    public byte[] toByteArray() { return value.clone(); }
    public String toCharsString() { return ""; }
}
