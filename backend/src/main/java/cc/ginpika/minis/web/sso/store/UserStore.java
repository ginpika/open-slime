package cc.ginpika.minis.web.sso.store;

import cc.ginpika.minis.web.sso.SsoUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** app_user 镜像（hostid 用户懒同步；仅真实登录写入，dev 用户不落库） */
@Repository
public class UserStore {

    private static final String UPSERT =
            """
            INSERT INTO app_user(id, username, nickname, email, role, avatar_url, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              username = excluded.username,
              nickname = excluded.nickname,
              email = excluded.email,
              role = excluded.role,
              avatar_url = COALESCE(excluded.avatar_url, app_user.avatar_url)
            """;

    private final JdbcTemplate jdbc;

    public UserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(SsoUser user) {
        long now = System.currentTimeMillis();
        jdbc.update(UPSERT,
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getRole(),
                user.getAvatarUrl(),
                now);
    }
}
