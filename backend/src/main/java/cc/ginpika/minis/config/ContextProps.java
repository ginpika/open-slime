package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文平面的注入开关（STEP 2，prefix: minis.context）。
 *
 * <p>架构文档给上下文平面定的目标是「RAG-first：进模型前默认注入 top-k，而不是纯靠模型主动调工具」
 * 与「记忆默认注入，而不是等模型主动 recall」。这是<b>行为变更</b>，故默认全关：关时两路 provider
 * 一律短路返回空，请求体与抽取前逐字一致（满足「旧行为零变化」），验证通过后再逐项打开。
 *
 * <p>开启后每轮进模型前会各多做一次 embedding 召回（知识 / 记忆），有网络与耗时成本。
 */
@ConfigurationProperties(prefix = "minis.context")
public class ContextProps {

    /** 知识检索注入：按本次用户输入做三路召回（关键词 + 向量 + 图谱），命中的 chunk 与图谱证据注入 system */
    private boolean injectKnowledge = false;

    /** 记忆注入：按本次用户输入召回相关记忆（用户说过什么 / 我答应过什么）注入 system */
    private boolean injectMemory = false;

    /**
     * 贴临界裁历史（默认<b>开</b>）：组装请求体前估算 token，只有逼近「模型上下文窗口」时才降级——
     * 先从最早的轮次整条丢掉，仍超才对保留部分做截断。不到临界一行不动，普通会话完全不受影响。
     *
     * <p>裁的是「这次发给模型什么」，不动库里任何一行；按<b>轮</b>丢（用户提问 + 它的回答与工具往返
     * 一起走），工具调用与结果不会被拆散，协议保持闭合。
     */
    private boolean compressHistory = true;

    /**
     * 给模型输出预留的 token：<b>输入与输出共用同一个上下文窗口</b>，历史预算必须把它扣掉，
     * 否则会贴着窗口发请求、被供应商直接拒收。默认 8k，够常规回答与一段思考。
     */
    private int reserveOutputTokens = 8192;

    /** 降级时至少保留最近几轮原文（兜底，不因窗口小而把最近对话也丢空） */
    private int compressKeepTurns = 2;

    public boolean isInjectKnowledge() {
        return injectKnowledge;
    }

    public void setInjectKnowledge(boolean injectKnowledge) {
        this.injectKnowledge = injectKnowledge;
    }

    public boolean isInjectMemory() {
        return injectMemory;
    }

    public void setInjectMemory(boolean injectMemory) {
        this.injectMemory = injectMemory;
    }

    public boolean isCompressHistory() {
        return compressHistory;
    }

    public void setCompressHistory(boolean compressHistory) {
        this.compressHistory = compressHistory;
    }

    public int getReserveOutputTokens() {
        return reserveOutputTokens;
    }

    public void setReserveOutputTokens(int reserveOutputTokens) {
        this.reserveOutputTokens = reserveOutputTokens;
    }

    public int getCompressKeepTurns() {
        return compressKeepTurns;
    }

    public void setCompressKeepTurns(int compressKeepTurns) {
        this.compressKeepTurns = compressKeepTurns;
    }
}
