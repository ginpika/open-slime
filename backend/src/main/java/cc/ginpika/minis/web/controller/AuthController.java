package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.config.SsoProps;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoService;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SsoService ssoService;
    private final SsoProps ssoProps;

    public AuthController(SsoService ssoService, SsoProps ssoProps) {
        this.ssoService = ssoService;
        this.ssoProps = ssoProps;
    }

    /** 当前登录用户（拦截器已注入 / 401 处理）；未接入 hostid 时返回 dev 用户 */
    @GetMapping("/me")
    public SsoUser me(HttpServletRequest request) {
        return (SsoUser) request.getAttribute(SsoInterceptor.ATTR);
    }

    /** 登出：服务端撤销 hostid 会话；前端随后可跳 hostid 登录页重登 */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        ssoService.revoke(cookieToken(request));
        return Map.of("success", true);
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (ssoProps.getCookieName().equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
