package cc.ginpika.minis.web.sso;

import com.fasterxml.jackson.databind.JsonNode;

/** hostid /api/sso/session 返回的用户（字段对齐 hostid 实码） */
public class SsoUser {

    private final String id;
    private final String username;
    private final String nickname;
    private final String email;
    private final String role;
    private final String avatarUrl;

    public SsoUser(String id, String username, String nickname, String email, String role, String avatarUrl) {
        this.id = id;
        this.username = username;
        this.nickname = nickname == null ? "" : nickname;
        this.email = email == null ? "" : email;
        this.role = role == null ? "" : role;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
    }

    public static SsoUser from(JsonNode u) {
        return new SsoUser(
                text(u, "id"),
                text(u, "username"),
                text(u, "nickname"),
                text(u, "email"),
                text(u, "role"),
                text(u, "avatarUrl"));
    }

    /** 本地开发（未配置 hostid）时使用的假用户 */
    public static SsoUser dev() {
        return new SsoUser("local-dev", "dev", "local-dev", "", "admin", "");
    }

    /**
     * 本机模式的本地用户（引导页配置、壳经环境变量注入）：id 仍是 {@code local-dev}
     * （前端据此隐藏登出），用户名 / 昵称取自定义名（空则回落 local-dev），头像为壳注入的地址。
     */
    public static SsoUser local(String name, String avatar) {
        String n = name == null || name.isBlank() ? "local-dev" : name.trim();
        return new SsoUser("local-dev", n, n, "", "admin", avatar == null ? "" : avatar);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname;
    }

    public String getEmail() {
        return email;
    }

    public String getRole() {
        return role;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }
}
