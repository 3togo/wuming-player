package android.util;

import java.util.Arrays;

/** Android Base64 API backed by the JDK codec. */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {
    }

    public static byte[] decode(String value, int flags) {
        return decoder(flags).decode(value);
    }

    public static byte[] decode(byte[] value, int flags) {
        return decoder(flags).decode(value);
    }

    public static byte[] decode(byte[] value, int offset, int length, int flags) {
        return decode(Arrays.copyOfRange(value, offset, offset + length), flags);
    }

    public static byte[] encode(byte[] value, int flags) {
        return encoder(flags).encode(value);
    }

    public static byte[] encode(byte[] value, int offset, int length, int flags) {
        return encode(Arrays.copyOfRange(value, offset, offset + length), flags);
    }

    public static String encodeToString(byte[] value, int flags) {
        return new String(encode(value, flags), java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static String encodeToString(byte[] value, int offset, int length, int flags) {
        return encodeToString(Arrays.copyOfRange(value, offset, offset + length), flags);
    }

    private static java.util.Base64.Decoder decoder(int flags) {
        return (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlDecoder()
            : java.util.Base64.getMimeDecoder();
    }

    private static java.util.Base64.Encoder encoder(int flags) {
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlEncoder()
            : java.util.Base64.getEncoder();
        return (flags & NO_PADDING) != 0 ? encoder.withoutPadding() : encoder;
    }
}
