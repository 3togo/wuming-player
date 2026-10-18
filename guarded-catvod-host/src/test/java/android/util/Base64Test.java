package android.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class Base64Test {

    @Test
    public void decodesAndroidDefaultAndEncodesUrlSafeWithoutPadding() {
        assertEquals(
            "TVBox",
            new String(Base64.decode("VFZCb3g=\n", Base64.DEFAULT), StandardCharsets.UTF_8)
        );
        assertArrayEquals(
            "_-4".getBytes(StandardCharsets.US_ASCII),
            Base64.encode(new byte[]{(byte) 0xff, (byte) 0xee}, Base64.URL_SAFE | Base64.NO_PADDING)
        );
    }
}
