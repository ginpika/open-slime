package cc.ginpika.minis.ai.terminal.dto;

import java.util.List;

/** 命令行终端模块的请求/响应对（工作空间页 ↔ {@code web.controller.TerminalController}） */
public final class TerminalDtos {

    /**
     * 一个终端目标（设置页展示用）。
     *
     * <p>凭证（sshPassword / sshPrivateKey / sshPassphrase）原样回给前端并在页面上打码显示，
     * 与 {@code SettingsDtos.ModelView} 直接回 apiKey 是同一个取舍：单用户私域部署，
     * 页面本来就要能改它们；不回显反倒会让人以为「保存后凭证丢了」。
     */
    public record TargetView(String id, String name, String kind, boolean enabled, String workdir,
                             Long timeoutMs, Integer maxOutput, List<String> allowPatterns,
                             List<String> denyPatterns, String dockerHost, String image,
                             String container, String sshHost, Integer sshPort, String sshUser,
                             String sshAuth, String sshPassword, String sshPrivateKey,
                             String sshPassphrase, String hostKeyCheck, String note) {
    }

    /** 新建 / 整体覆盖一个目标（PUT 时 id 来自路径） */
    public record TargetRequest(String name, String kind, Boolean enabled, String workdir,
                                Long timeoutMs, Integer maxOutput, List<String> allowPatterns,
                                List<String> denyPatterns, String dockerHost, String image,
                                String container, String sshHost, Integer sshPort, String sshUser,
                                String sshAuth, String sshPassword, String sshPrivateKey,
                                String sshPassphrase, String hostKeyCheck, String note) {
    }

    /** 工作空间页的整页视图：全部目标（没有全局总开关，逐目标开关即唯一闸门） */
    public record TerminalView(List<TargetView> targets) {
    }

    /** 连通性测试结果（设置页「测试」按钮）：detail=成功摘要，error=失败原因 */
    public record ProbeView(boolean ok, long elapsedMs, String detail, String error) {
    }

    /** 设置页手动试跑一条命令 */
    public record RunRequest(String command) {
    }

    /** 一次命令执行的审计视图（设置页「最近执行」列表） */
    public record CommandView(long id, String targetName, String command, String workdir,
                              Integer exitCode, long durationMs, String stdout, String stderr,
                              boolean truncated, boolean timedOut, boolean ok, String source,
                              long createdAt) {
    }

    private TerminalDtos() {
    }
}
