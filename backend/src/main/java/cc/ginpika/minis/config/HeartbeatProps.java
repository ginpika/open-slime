package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 潜意识模块配置（心跳扫描节拍与单拍派活上限）。
 *
 * <p>心跳改成「逐 Agent」之后，没有全局开关与全局节拍了：每个 Agent 自己那行（{@code heartbeat}）
 * 记着 {@code enabled} 与 {@code tick_ms}。这里只剩调度器扫描的固定周期（{@link #getScanMs()}）——
 * 调度器按它醒来一次，逐个看哪些 Agent 到点了，到点就派一拍。{@link #getTickMs()} 仅作播种默认节拍。
 */
@ConfigurationProperties(prefix = "minis.heartbeat")
public class HeartbeatProps {

    /** 心跳启动初值：默认关；手动触发接口（敲一次 / 单跑职责）与它无关，始终可用 */
    private boolean enabled = false;

    /** 默认心跳节拍（毫秒）：仅用于「播种某 Agent 心跳行」时的 tick_ms 初值 */
    private long tickMs = 60_000L;

    /** 调度器扫描周期（毫秒）：固定小值，逐个 Agent 判断是否到点 */
    private long scanMs = 5_000L;

    /** 单拍派活上限：主会话一拍里最多派出的子代理数（每次派活都是一轮完整的 LLM 往返） */
    private int maxAgentsPerBeat = 8;

    /** 启动后到第一次心跳扫描的延迟（毫秒）：给应用启动留出余量 */
    private long initialDelayMs = 15_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTickMs() {
        return tickMs;
    }

    public void setTickMs(long tickMs) {
        this.tickMs = tickMs;
    }

    public long getScanMs() {
        return scanMs;
    }

    public void setScanMs(long scanMs) {
        this.scanMs = scanMs;
    }

    public int getMaxAgentsPerBeat() {
        return maxAgentsPerBeat;
    }

    public void setMaxAgentsPerBeat(int maxAgentsPerBeat) {
        this.maxAgentsPerBeat = maxAgentsPerBeat;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }
}
