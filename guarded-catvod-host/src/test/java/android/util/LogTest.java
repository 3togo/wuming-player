package android.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LogTest {

    @Test
    public void acceptsProviderDiagnosticsWithoutWritingOrFailing() {
        assertEquals(0, Log.d("provider", "message"));
        assertEquals(0, Log.e("provider", "message", new IllegalStateException("test")));
    }
}
