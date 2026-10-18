package android.net.wifi;

/** Linkage-only Wi-Fi info. The desktop provider host is not associated with a Wi-Fi network. */
public class WifiInfo {
    public String getMacAddress() {
        return "02:00:00:00:00:00";
    }

    public String getSSID() {
        return null;
    }

    public String getBSSID() {
        return null;
    }

    public int getIpAddress() {
        return 0;
    }

    public int getRssi() {
        return 0;
    }

    public int getLinkSpeed() {
        return 0;
    }
}
