/* wuming-player modification, 2026. GPL-3.0 under the root project license. */
package org.fourthline.cling.model.message.header;

import java.net.InetAddress;
import java.net.URL;
import java.util.List;

/** Reject callback redirection to third parties without performing DNS lookups. */
public final class CallbackAddressPolicy {
    private CallbackAddressPolicy() {}

    public static boolean allows(List<URL> callbacks, InetAddress source) {
        if (source == null || callbacks == null || callbacks.isEmpty() || callbacks.size() > 4) return false;
        String expected = source.getHostAddress();
        for (URL callback : callbacks) {
            if (callback == null || !"http".equals(callback.getProtocol()) || callback.getUserInfo() != null) return false;
            String host = callback.getHost();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            // Cling's default discovery is IPv4. Literal comparison deliberately rejects hostnames,
            // DNS rebinding and alternate numeric spellings; clients must use their source address.
            if (!host.equalsIgnoreCase(expected)) return false;
            int port = callback.getPort();
            if (port != -1 && (port < 1 || port > 65535)) return false;
        }
        return true;
    }
}
