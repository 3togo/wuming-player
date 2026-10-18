package android.net.wifi;

/** Linkage-only Wi-Fi manager. The desktop provider host is not associated with a Wi-Fi network. */
public class WifiManager {
    public static final String EXTRA_WIFI_STATE = "android.net.wifi.WIFI_STATE";
    public static final int WIFI_STATE_ENABLED = 3;

    public WifiInfo getConnectionInfo() {
        return new WifiInfo();
    }

    public boolean isWifiEnabled() {
        return false;
    }
}
