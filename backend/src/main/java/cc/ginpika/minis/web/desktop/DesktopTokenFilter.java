package cc.ginpika.minis.web.desktop;

import cc.ginpika.minis.config.DesktopProps;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 桌面壳的本机访问闸门：只有带上壳注入的一次性令牌，才允许调用 {@code /api/**}。
 *
 * <p>本机模式把后端只绑在回环地址上，但回环不等于安全——同机任何进程都能访问 127.0.0.1，
 * 而本后端的 terminal 工具可以执行任意命令（见 {@code TerminalService}）。所以壳每次启动
 * 生成一枚随机令牌，经环境变量注入（{@code MINIS_LOCAL_TOKEN}），由壳在页面脚本执行前写进
 * {@code window.__MINIS_LOCAL_TOKEN__}，前端统一在请求头 {@value #HEADER} 上带回来。
 *
 * <p>未开启桌面模式、或令牌为空时<b>完全直通</b>（Web 部署与本地开发不受影响）。
 * 静态资源与页面不校验：它们本身不含权限，而且壳必须先加载页面、拿到令牌后才有后续请求。
 */
@Component
public class DesktopTokenFilter extends OncePerRequestFilter {

    /** 壳与前端约定的令牌请求头 */
    public static final String HEADER = "X-Minis-Token";

    private static final String API_PREFIX = "/api/";

    private final DesktopProps props;

    public DesktopTokenFilter(DesktopProps props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled() || props.getLocalToken().isBlank()
                || !request.getRequestURI().startsWith(API_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if (props.getLocalToken().equals(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"invalid local token\"}");
    }
}
