package cc.ginpika.minis.web.desktop;

import cc.ginpika.minis.config.DesktopProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 桌面壳的启动握手：把实际监听的端口按固定格式打一行到日志，壳读它来拼 base URL。
 *
 * <p>本机模式用 {@code --server.port=0} 让系统分配空闲端口（避免与用户机器上占着 18081 的
 * 进程打架），所以端口只有启动完才知道。壳监听这行 {@code MINIS_READY port=<端口>} 即可，
 * 不必去解析随机端口或落临时文件；非桌面模式不打印，不给正常日志添噪。
 */
@Component
public class DesktopReadyReporter implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DesktopReadyReporter.class);

    private final DesktopProps props;

    public DesktopReadyReporter(DesktopProps props) {
        this.props = props;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (!props.isEnabled()) {
            return;
        }
        log.info("MINIS_READY port={}", event.getWebServer().getPort());
    }
}
