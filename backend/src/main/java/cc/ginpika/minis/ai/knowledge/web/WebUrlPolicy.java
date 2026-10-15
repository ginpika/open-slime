package cc.ginpika.minis.ai.knowledge.web;

import java.net.InetAddress;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/** Validate navigations, redirects and browser subresources, not just the initial URL. */
@Component
public class WebUrlPolicy {
    @Value("${minis.web.allow-fake-ip:false}")
    private boolean allowFakeIp;
    public String validate(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("只允许不含用户名和密码的 HTTP / HTTPS 网页地址");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                boolean fakeIp = bytes.length == 4 && (bytes[0] & 255) == 198
                        && ((bytes[1] & 255) == 18 || (bytes[1] & 255) == 19);
                // Proxy DNS synthesizes this range; only named domains may use the opt-in.
                if (allowFakeIp && fakeIp && !uri.getHost().matches("[0-9.]+")) continue;
                if (!publicAddress(address)) {
                    throw new IllegalArgumentException(fakeIp ? "域名解析到代理 Fake-IP；请配置 MINIS_WEB_ALLOW_FAKE_IP=true，或使用真实 DNS"
                            : "浏览器不能访问本机、内网或保留地址");
                }
            }
            return uri.normalize().toASCIIString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析网页地址或域名", e);
        }
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 16) return (b[0] & 0xe0) == 0x20; // global unicast only
        int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
        return a != 0 && a != 127 && a < 224 && a != 10
                && !(a == 100 && c >= 64 && c <= 127)
                && !(a == 169 && c == 254) && !(a == 172 && c >= 16 && c <= 31)
                && !(a == 192 && (c == 168 || c == 0 || (c == 2)))
                && !(a == 198 && (c == 18 || c == 19 || (c == 51 && d == 100)))
                && !(a == 203 && c == 0 && d == 113);
    }
}
