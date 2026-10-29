package cc.ginpika.minis.coding.api;

import cc.ginpika.minis.coding.CodingFacade;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared HTTP access checks; controllers pass only the verified owner ID to use cases. */
final class CodingAccess {
    private CodingAccess() {}

    static String owner(HttpServletRequest request) {
        return user(request).getId();
    }

    static String administrator(HttpServletRequest request) {
        SsoUser user=user(request);
        if(!"admin".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仓库路径登记需要管理员身份");
        }
        return user.getId();
    }

    private static SsoUser user(HttpServletRequest request) {
        Object value=request.getAttribute(SsoInterceptor.ATTR);
        if(!(value instanceof SsoUser user) || user.getId()==null || user.getId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要登录");
        }
        if(!CodingFacade.allowsActorAt(user.getId(),request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"开发身份仅允许本机访问编程模块；远程部署请配置 SSO");
        }
        return user;
    }
}
