package android.os;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BuildTest {

    @Test
    public void exposesSelectedCompatibilityAbiAndStableSdk() {
        assertEquals("armeabi-v7a", Build.CPU_ABI);
        assertEquals(28, Build.VERSION.SDK_INT);
        assertEquals("TVBox Desktop", Build.MODEL);
    }
}
