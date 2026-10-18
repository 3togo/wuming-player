package android.os;

/** Minimal immutable device identity used by portable guarded providers. */
public final class Build {
    public static final String BRAND = "TVBox";
    public static final String MODEL = "TVBox Desktop";
    public static final String CPU_ABI = selectedAbi();
    public static final String[] SUPPORTED_ABIS = new String[]{CPU_ABI};

    private Build() {
    }

    private static String selectedAbi() {
        String configured = System.getProperty("tvbox.guarded.abi", "armeabi-v7a");
        return "arm64-v8a".equals(configured) ? "arm64-v8a" : "armeabi-v7a";
    }

    public static final class VERSION {
        public static final int SDK_INT = 28;
        public static final String RELEASE = "9";

        private VERSION() {
        }
    }
}
