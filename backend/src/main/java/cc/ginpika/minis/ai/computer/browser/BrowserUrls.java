package cc.ginpika.minis.ai.computer.browser;

import java.net.InetAddress;
import java.net.URI;

/** Public web policy for backend-owned browsers; never applies to the user's extension-managed Chrome. */
public final class BrowserUrls {
    private BrowserUrls() {}
    public static String validate(String raw, boolean allowFakeIp) {
        String url = http(raw);
        try {
            URI uri = URI.create(url);
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                boolean fake = bytes.length == 4 && (bytes[0] & 255) == 198 && ((bytes[1] & 255) == 18 || (bytes[1] & 255) == 19);
                if (allowFakeIp && fake && !uri.getHost().matches("[0-9.]+")) continue;
                if (!publicAddress(address)) throw new IllegalArgumentException("隔离浏览器不能访问本机、内网或保留地址");
            }
            return url;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("无法解析网页地址或域名", error); }
    }
    public static String http(String raw) {
        URI uri = URI.create(raw == null ? "" : raw.strip());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("仅支持不含用户名密码的 HTTP / HTTPS 地址");
        return uri.normalize().toASCIIString();
    }
    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 16) return (b[0] & 0xe0) == 0x20;
        int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
        return a != 0 && a != 127 && a < 224 && a != 10
                && !(a == 100 && c >= 64 && c <= 127) && !(a == 169 && c == 254)
                && !(a == 172 && c >= 16 && c <= 31) && !(a == 192 && (c == 168 || c == 0 || c == 2))
                && !(a == 198 && (c == 18 || c == 19 || c == 51 && d == 100)) && !(a == 203 && c == 0 && d == 113);
    }
}
