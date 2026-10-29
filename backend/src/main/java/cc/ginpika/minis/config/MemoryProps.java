package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆体系 v2 的开关与参数（prefix: minis.memory）。
 *
 * <p><b>默认开</b>（{@code enabled=true}）：记忆是产品能力，不是实验开关——默认就该工作。
 * 未配置模型时自动空转（不提炼、不注入），不产生调用也不报错；确需时可显式关闭。
 *
 * <p>记忆是旁路：无论提炼还是巩固，失败都只留日志，绝不拖垮对话与执行主流程。
 */
@ConfigurationProperties(prefix = "minis.memory")
public class MemoryProps {

    /** 总开关：关时记忆 v2 全链路（提炼 / 巩固 / 条目召回）短路。默认开 */
    private boolean enabled = true;

    /** Source-local zone; configure minis.memory.time-zone when the server and user zones differ. */
    private String timeZone = java.time.ZoneId.systemDefault().getId();
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String value) { timeZone = java.time.ZoneId.of(value).getId(); }

    /** 提炼与巩固用模型（llm_model.id）；空 = 沿用聊天默认模型 */
    private String extractModel = "";

    /** 默认注入的条目上限 */
    private int topK = 6;

    /** 相关性门控阈值：低于它不注入（对齐 Gemini「先判断有没有用」） */
    private double minScore = 0.35;

    /** 信息量门槛：用户输入短于此且不含「记住」类指令时，不作为提炼素材 */
    private int noiseMinChars = 6;

    /** 攒批阈值：同一会话攒够这么多轮对话，就合并提炼一次（大幅降低调用次数） */
    private int batchTurns = 4;
    /** Explicit continuation context has its own bounded token allowance. */
    private int continuationTokens = 1800;
    public int getContinuationTokens() { return continuationTokens; }
    public void setContinuationTokens(int tokens) { continuationTokens=Math.max(0,tokens); }

    /** 空闲阈值（毫秒）：会话静默这么久后，把缓冲里的对话合并提炼一次 */
    private long idleMs = 300_000L;

    /** 情景层（episode）保留条数上限，超出由巩固裁剪 */
    private int episodeMax = 50;

    /** 巩固节拍（毫秒）：后台合成 / 去重 / 过期的扫描周期 */
    private long consolidateIntervalMs = 3_600_000L;

    /** 单条记忆注入渲染长度上限（字符）：只截断上下文，不截断图谱正本，防止长条目撑爆注入预算（memory-v5 §6.3） */
    private int bodyMaxChars = 1200;

    /** 上下文注入的 token 预算（memory-v5 §6.3）：按作用域分桶，替代「按条数」硬编码 */
    private Budget budget = new Budget();

    /** 语义级巩固（memory-v5 §7.2 · P4）：同域内「新值取代旧值 / 矛盾合并」需一次 LLM，默认关以免意外成本 */
    private boolean semanticConsolidate = false;

    /** 语义巩固单轮扫描的条目上限（控 LLM 成本） */
    private int semanticMaxItems = 40;

    /** 作用域守门（memory-v6 §4.2 · 不变量 I1）：无作用域的写入拒绝与处理策略 */
    private Scope scope = new Scope();

    /** 记忆快照（memory-v6 §4.3）：本轮只读视图的构建参数 */
    private Snapshot snapshot = new Snapshot();

    /** 轮内写缓冲（memory-v6 §4.4）：本轮的写入推到轮末提交，不回改本轮注入 */
    private Staging staging = new Staging();

    /** 工作集（memory-v6 §13）：会话 / 任务级临时工作状态的保留与蒸馏策略 */
    private Work work = new Work();

    /** 召回权重（memory-v6 §8.2）：分值 = 余弦 × W_layer × W_domain × W_evidence */
    private Weights weights = new Weights();

    /** 提升层（memory-v6 §9.3）：L2 → L4 发布候选的生成参数 */
    private Promote promote = new Promote();

    public Promote getPromote() {
        return promote;
    }

    public void setPromote(Promote promote) {
        this.promote = promote;
    }

    /** 提升配置（minis.memory.promote.*） */
    public static class Promote {
        /** 是否自动放行（默认 false = 只产候选，落地需人/Agent 确认，见 I7） */
        private boolean auto = false;
        /** 置信度阈值：达到即视为值得沉淀 */
        private double minConfidence = 0.8d;
        /** 单轮扫描的条目上限（控 LLM / 写入成本） */
        private int maxPerScan = 50;

        public boolean isAuto() {
            return auto;
        }

        public void setAuto(boolean auto) {
            this.auto = auto;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }

        public int getMaxPerScan() {
            return maxPerScan;
        }

        public void setMaxPerScan(int maxPerScan) {
            this.maxPerScan = maxPerScan;
        }
    }

    public Work getWork() {
        return work;
    }

    public void setWork(Work work) {
        this.work = work;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    /** 工作集配置（minis.memory.work.*） */
    public static class Work {
        /** 工作项 TTL（毫秒）：到期不再注入；≤0 = 不过期 */
        private long ttlMs = 604_800_000L;
        /** 会话收尾是否自动蒸馏工作集进长期记忆 */
        private boolean distillOnClose = true;

        public long getTtlMs() {
            return ttlMs;
        }

        public void setTtlMs(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        public boolean isDistillOnClose() {
            return distillOnClose;
        }

        public void setDistillOnClose(boolean distillOnClose) {
            this.distillOnClose = distillOnClose;
        }
    }

    /** 召回权重（minis.memory.weights.*）：缺省值见各 getter，未配置项一律 1.0 */
    public static class Weights {
        /** 层权重：L1 共享 / L2 私有 / L3 任务 / L4 发布 */
        private java.util.Map<String, Double> layer = new java.util.LinkedHashMap<>();
        /** 域权重：USER_PROFILE / PROJECT / AGENT / TASK_EPISODIC */
        private java.util.Map<String, Double> domain = new java.util.LinkedHashMap<>();
        /** 证据权重：explicit / repeated / inferred */
        private java.util.Map<String, Double> evidence = new java.util.LinkedHashMap<>();

        public java.util.Map<String, Double> getLayer() {
            return layer;
        }

        public void setLayer(java.util.Map<String, Double> layer) {
            this.layer = layer;
        }

        public java.util.Map<String, Double> getDomain() {
            return domain;
        }

        public void setDomain(java.util.Map<String, Double> domain) {
            this.domain = domain;
        }

        public java.util.Map<String, Double> getEvidence() {
            return evidence;
        }

        public void setEvidence(java.util.Map<String, Double> evidence) {
            this.evidence = evidence;
        }

        /** 取权重：未配置 / 非正值一律回落到 1.0（配置缺省不影响可用性） */
        public static double of(java.util.Map<String, Double> map, String key, double fallback) {
            if (map == null || key == null) {
                return fallback;
            }
            Double v = map.get(key);
            return v == null || v <= 0 ? fallback : v;
        }
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    public Staging getStaging() {
        return staging;
    }

    public void setStaging(Staging staging) {
        this.staging = staging;
    }

    /** 快照配置（minis.memory.snapshot.*） */
    public static class Snapshot {
        /** 快照有效期（毫秒）：同轮内重复取用视为仍旧有效 */
        private long ttlMs = 120_000L;
        /** 是否把每轮命中的条目落 memory_snapshot（回放「这一轮模型看见了什么」；只存引用） */
        private boolean replay = true;
        /** 单轮召回候选上限（预算内再筛） */
        private int candidateLimit = 30;

        public long getTtlMs() {
            return ttlMs;
        }

        public void setTtlMs(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        public boolean isReplay() {
            return replay;
        }

        public void setReplay(boolean replay) {
            this.replay = replay;
        }

        public int getCandidateLimit() {
            return candidateLimit;
        }

        public void setCandidateLimit(int candidateLimit) {
            this.candidateLimit = candidateLimit;
        }
    }

    /** 轮内写缓冲配置（minis.memory.staging.*） */
    public static class Staging {
        /** true = 轮内写入统一到轮末提交（本轮注入不受影响，下一轮才可见） */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    /** 作用域守门配置（minis.memory.scope.*） */
    public static class Scope {
        /** true = 无作用域的写入直接抛错（开发/测试期默认，及早暴露契约缺失）；false = 只告警并丢弃 */
        private boolean enforce = true;

        public boolean isEnforce() {
            return enforce;
        }

        public void setEnforce(boolean enforce) {
            this.enforce = enforce;
        }
    }

    public boolean isSemanticConsolidate() {
        return semanticConsolidate;
    }

    public void setSemanticConsolidate(boolean semanticConsolidate) {
        this.semanticConsolidate = semanticConsolidate;
    }

    public int getSemanticMaxItems() {
        return semanticMaxItems;
    }

    public void setSemanticMaxItems(int semanticMaxItems) {
        this.semanticMaxItems = semanticMaxItems;
    }

    public int getBodyMaxChars() {
        return bodyMaxChars;
    }

    public void setBodyMaxChars(int bodyMaxChars) {
        this.bodyMaxChars = bodyMaxChars;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    /** 记忆注入 token 预算（配置项 minis.memory.budget.*）：**按层分桶**（memory-v6 §8.3） */
    public static class Budget {
        /** 总预算（token） */
        private int total = 1600;
        /** L1 共享权威桶（画像 + 项目事实 + 全局规则） */
        private int shared = 400;
        /** L2 Agent 私有桶 */
        private int agent = 300;
        /** L3 任务 / 工作集桶 */
        private int task = 300;
        /** L4 发布（交接 / 知识）桶 */
        private int publish = 200;
        /** 情景（episode）桶 */
        private int episode = 200;

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }

        public int getShared() {
            return shared;
        }

        public void setShared(int shared) {
            this.shared = shared;
        }

        public int getAgent() {
            return agent;
        }

        public void setAgent(int agent) {
            this.agent = agent;
        }

        public int getTask() {
            return task;
        }

        public void setTask(int task) {
            this.task = task;
        }

        public int getPublish() {
            return publish;
        }

        public void setPublish(int publish) {
            this.publish = publish;
        }

        public int getEpisode() {
            return episode;
        }

        public void setEpisode(int episode) {
            this.episode = episode;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExtractModel() {
        return extractModel;
    }

    public void setExtractModel(String extractModel) {
        this.extractModel = extractModel;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public int getNoiseMinChars() {
        return noiseMinChars;
    }

    public void setNoiseMinChars(int noiseMinChars) {
        this.noiseMinChars = noiseMinChars;
    }

    public int getBatchTurns() {
        return batchTurns;
    }

    public void setBatchTurns(int batchTurns) {
        this.batchTurns = batchTurns;
    }

    public long getIdleMs() {
        return idleMs;
    }

    public void setIdleMs(long idleMs) {
        this.idleMs = idleMs;
    }

    public int getEpisodeMax() {
        return episodeMax;
    }

    public void setEpisodeMax(int episodeMax) {
        this.episodeMax = episodeMax;
    }

    public long getConsolidateIntervalMs() {
        return consolidateIntervalMs;
    }

    public void setConsolidateIntervalMs(long consolidateIntervalMs) {
        this.consolidateIntervalMs = consolidateIntervalMs;
    }
}
