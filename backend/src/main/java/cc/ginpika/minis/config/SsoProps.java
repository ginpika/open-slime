package cc.ginpika.minis.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** hostid cookie 域 SSO（prefix: minis.sso） */
@ConfigurationProperties(prefix = "minis.sso")
public class SsoProps {

    /** hostid 地址（生产 URL 由使用者填入，§9-3 拍板：M0 直连生产 hostid） */
    private String serverUrl = "";

    /** hostid 会话 cookie 名（同主域 .example.com 共享） */
    private String cookieName = "sso_token";

    /** 服务端校验结果缓存 TTL */
    private Duration tokenTtl = Duration.ofMinutes(5);

    /** 管理端点（/api/admin/**）授权用户名白名单；role=admin 始终放行，空表则仅 dev 直通可用 */
    private List<String> adminUsernames = List.of();

    public List<String> getAdminUsernames() {
        return adminUsernames;
    }

    public void setAdminUsernames(List<String> adminUsernames) {
        this.adminUsernames = adminUsernames == null ? List.of() : adminUsernames;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }
}
