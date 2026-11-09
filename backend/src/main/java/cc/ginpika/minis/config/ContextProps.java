package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文平面的注入开关（STEP 2，prefix: minis.context）。
 *
 * <p>知识检索默认<b>关</b>（行为变更，验证后再开）；记忆默认<b>开</b>——记忆是产品能力，
 * 默认就该工作，未配置模型时自动空转（不注入、不报错）。关时对应 provider 一律短路返回空。
 *
 * <p>开启后每轮进模型前会各多做一次 embedding 召回（知识 / 记忆），有网络与耗时成本。
 */
@ConfigurationProperties(prefix = "minis.context")
public class ContextProps {

    /** 知识检索注入：按本次用户输入做三路召回（关键词 + 向量 + 图谱），命中的 chunk 与图谱证据注入 system */
    private boolean injectKnowledge = false;

    /** 记忆注入：按本次用户输入召回相关记忆条目注入 system（用户是谁 / 在做什么 / 答应过什么）。默认开 */
    private boolean injectMemory = true;

    /** P1 单聊试点。关闭时沿用现有历史选取与诊断路径。 */
    private boolean ephemeralChat = false;

    /** 阶段续接试点，依赖单聊动态上下文。 */
    private boolean stageContinuation = false;

    /** 单聊待办确认试点，依赖单聊动态上下文。 */
    private boolean todoApprovals = false;

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

    public boolean isEphemeralChat() {
        return ephemeralChat;
    }

    public void setEphemeralChat(boolean ephemeralChat) {
        this.ephemeralChat = ephemeralChat;
    }

    public boolean isStageContinuation() {
        return stageContinuation;
    }

    public void setStageContinuation(boolean stageContinuation) {
        this.stageContinuation = stageContinuation;
    }

    public boolean isTodoApprovals() {
        return todoApprovals;
    }

    public void setTodoApprovals(boolean todoApprovals) {
        this.todoApprovals = todoApprovals;
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
