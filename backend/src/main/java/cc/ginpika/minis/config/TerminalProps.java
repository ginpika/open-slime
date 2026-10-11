package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 命令行终端模块配置（prefix: minis.terminal）。
 *
 * <p>这是「Agent 直接控制所在环境」这一能力面的全局护栏默认值。<b>没有全局总开关</b>：
 * 能力可用与否完全由逐目标开关（{@code term_target.enabled}）决定——想让模型用哪台环境
 * 就开哪台，而超时与输出上限由这里兜底（与 {@code heartbeat_plan} 逐份编制按需派出同一个取舍：
 * 编制才是开关，而不是一个总闸）。
 *
 * <p>目标清单（有哪些环境、凭证是什么）不在配置里，而在库表 {@code term_target}：
 * 那是会变的东西，由工作空间页维护；这里只放与具体目标无关的全局护栏。
 */
@ConfigurationProperties(prefix = "minis.terminal")
public class TerminalProps {

    /** 单条命令默认超时（毫秒）：目标未单独配置时用它 */
    private long defaultTimeoutMs = 60_000L;

    /** 超时上限（毫秒）：目标自己配的值也夹取到这里，防止「配成 0 等于不限」 */
    private long maxTimeoutMs = 600_000L;

    /** 回给模型的输出默认上限（字符，stdout / stderr 各自计） */
    private int maxOutputChars = 20_000;

    /** 输出上限的上限（字符）：目标自己配的值夹取到这里 */
    private int maxOutputCharsCap = 200_000;

    /** 审计表保留条数：超出后裁掉最旧的（每次执行顺带清理，不另起定时任务） */
    private int auditKeep = 1_000;

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getMaxTimeoutMs() {
        return maxTimeoutMs;
    }

    public void setMaxTimeoutMs(long maxTimeoutMs) {
        this.maxTimeoutMs = maxTimeoutMs;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public int getMaxOutputCharsCap() {
        return maxOutputCharsCap;
    }

    public void setMaxOutputCharsCap(int maxOutputCharsCap) {
        this.maxOutputCharsCap = maxOutputCharsCap;
    }

    public int getAuditKeep() {
        return auditKeep;
    }

    public void setAuditKeep(int auditKeep) {
        this.auditKeep = auditKeep;
    }
}
