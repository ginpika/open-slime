package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 桌面壳（Tauri）相关开关（prefix: minis.desktop）。
 *
 * <p>只用在本机模式：壳把后端拉起来时置 {@code enabled=true}，并注入一枚每次启动都不同的
 * {@code local-token}。两者都缺省关闭/为空，Web 部署与本地开发（浏览器直连）行为不变。
 *
 * <p>壳在拉起进程时通过环境变量注入：{@code MINIS_DESKTOP=1}、{@code MINIS_LOCAL_TOKEN=<随机值>}，
 * 以及引导页配好的 {@code MINIS_LOCAL_USER_NAME} / {@code MINIS_LOCAL_USER_AVATAR}（本地用户），
 * 并由壳在页面脚本执行前把同一枚令牌写进 {@code window.__MINIS_LOCAL_TOKEN__}，前端请求统一带上。
 */
@ConfigurationProperties(prefix = "minis.desktop")
public class DesktopProps {

    /** 是否由桌面壳托管：开启后 /api/** 需带本机令牌（见 DesktopTokenFilter） */
    private boolean enabled = false;

    /** 壳注入的一次性本机令牌；空 = 不校验（未接入壳时保持直通） */
    private String localToken = "";

    /** 壳注入的本地用户名；本机模式下顶替 local-dev（空 = 用后端默认） */
    private String localUserName = "";

    /** 壳注入的本地用户头像（data:image/...;base64,...）；空 = 无头像 */
    private String localUserAvatar = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLocalToken() {
        return localToken;
    }

    public void setLocalToken(String localToken) {
        this.localToken = localToken == null ? "" : localToken;
    }

    public String getLocalUserName() {
        return localUserName;
    }

    public void setLocalUserName(String localUserName) {
        this.localUserName = localUserName == null ? "" : localUserName;
    }

    public String getLocalUserAvatar() {
        return localUserAvatar;
    }

    public void setLocalUserAvatar(String localUserAvatar) {
        this.localUserAvatar = localUserAvatar == null ? "" : localUserAvatar;
    }
}
