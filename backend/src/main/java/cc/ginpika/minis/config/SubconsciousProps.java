package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 潜意识模块配置（HeartBeat 节拍与单拍派活上限）。
 *
 * <p>默认<b>不</b>常驻：启动后心跳处于关闭状态，需用户在潜意识页手动开启（{@code enabled=true}
 * 仅用于「启动即开始敲」的显式配置）。开关是运行时内存态，重启回到这里的默认值。
 */
@ConfigurationProperties(prefix = "minis.subconscious")
public class SubconsciousProps {

    /** 心跳启动初值：默认关；手动触发接口（敲一次 / 单跑子代理）与它无关，始终可用 */
    private boolean enabled = false;

    /** 心跳间隔（毫秒）：每敲一次唤醒主会话，由它决定这一拍派谁 */
    private long tickMs = 60_000L;

    /** 单拍派活上限：主会话一拍里最多派出的子代理数（每次派活都是一轮完整的 LLM 往返） */
    private int maxAgentsPerBeat = 8;

    /** 单个 sub-agent 的工具往返上限：超过后撤掉 tools 强制产出结论 */
    private int maxToolRounds = 3;

    /** 启动后到第一次心跳的延迟（毫秒）：给应用启动留出余量 */
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

    public int getMaxAgentsPerBeat() {
        return maxAgentsPerBeat;
    }

    public void setMaxAgentsPerBeat(int maxAgentsPerBeat) {
        this.maxAgentsPerBeat = maxAgentsPerBeat;
    }

    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    public void setMaxToolRounds(int maxToolRounds) {
        this.maxToolRounds = maxToolRounds;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }
}
