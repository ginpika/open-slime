package cc.ginpika.minis.web.sso;

import cc.ginpika.minis.config.DesktopProps;
import cc.ginpika.minis.config.SsoProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * SSO 认证拦截器。
 *
 * <ul>
 *   <li>未接入 hostid（server-url 空）→ 直通并注入本地用户（壳配过就用它，否则 dev）；</li>
 *   <li>页面/非 /api：未登录 302 → hostid /sso/login?redirect=当前地址；</li>
 *   <li>/api/**：未登录 401 + {error, loginUrl}（前端据此跳登录）。</li>
 * </ul>
 */
@Component
public class SsoInterceptor implements HandlerInterceptor {

    public static final String ATTR = "ssoUser";

    private static final Logger log = LoggerFactory.getLogger(SsoInterceptor.class);

    private final SsoProps props;
    private final DesktopProps desktopProps;
    private final SsoService ssoService;
    private final ObjectMapper objectMapper;

    public SsoInterceptor(SsoProps props, DesktopProps desktopProps, SsoService ssoService,
                          ObjectMapper objectMapper) {
        this.props = props;
        this.desktopProps = desktopProps;
        this.ssoService = ssoService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!ssoService.isEnabled()) {
            request.setAttribute(ATTR, localUser());
            return true;
        }

        String token = cookieToken(request);
        if (token == null || token.isBlank()) {
            return unauthorized(request, response);
        }
        var user = ssoService.session(token);
        if (user.isEmpty()) {
            return unauthorized(request, response);
        }
        request.setAttribute(ATTR, user.get());
        return true;
    }

    /** 未接入 hostid 时注入的用户：壳配过用户名或头像就用它，否则回退 dev */
    private SsoUser localUser() {
        String name = desktopProps.getLocalUserName();
        String avatar = desktopProps.getLocalUserAvatar();
        boolean hasName = name != null && !name.isBlank();
        boolean hasAvatar = avatar != null && !avatar.isBlank();
        if (!hasName && !hasAvatar) {
            return SsoUser.dev();
        }
        return SsoUser.local(name, avatar);
    }

    private boolean unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String loginUrl = ssoService.loginUrl(redirectTarget(request));
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    Map.of("error", "unauthorized", "loginUrl", loginUrl));
            return false;
        }
        if (isHtmlRequest(request)) {
            log.info("redirect to login: {} -> {}", uri, loginUrl);
            response.sendRedirect(loginUrl);
            return false;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Map.of("error", "unauthorized", "loginUrl", loginUrl));
        return false;
    }

    /**
     * 登录成功后的回跳目标：页面=当前 URL；API=来路页（Referer）或应用根。
     * 避免登录后落在某个 API 端点看到 JSON。
     */
    private String redirectTarget(HttpServletRequest request) {
        String qs = request.getQueryString();
        if (isHtmlRequest(request)) {
            String url = request.getRequestURL().toString();
            return qs == null ? url : url + "?" + qs;
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            return referer;
        }
        return request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443
                        ? "" : ":" + request.getServerPort())
                + "/";
    }

    private boolean isHtmlRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (props.getCookieName().equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
