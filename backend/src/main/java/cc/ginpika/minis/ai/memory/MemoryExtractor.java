package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.ChatMessage;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.config.MemoryProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆写入的提炼器（记忆 v5）：把「一轮对话」提炼成条目，并按<b>归属决定律</b>落库。
 *
 * <p>方向约束：memory 属 ai 一侧，不回头读 agent 的库——所以是 <b>push 不是 pull</b>：
 * 调用方（会话侧）在轮次收尾时把「本轮用户输入 + 助手回复」交进来，并附一个 {@link MemoryExec}
 * 说明「这是谁、在哪个项目 / 会话」。
 *
 * <p><b>v5 三条硬规则</b>（对齐 memory-v5 §5.1）：
 * <ul>
 *   <li><b>自动提炼禁写用户画像</b>——画像类 scope 一律只提交到 {@link UserProfileService} 候选，
 *       绝不直接落 {@code owner=USER} 的 active 条目（这正是 v2「画像污染」的根因修复）；</li>
 *   <li><b>归属由信息类型和执行域决定</b>——项目事实留 L3 待确认，方法经验留 L2 私有域（agent_id 空 → 'builtin'），
 *       绝不因「是谁产生的」就升为全局；</li>
 *   <li><b>失败隔离 + 幂等</b>——异步执行、失败只留日志；以 fingerprint 去重，重复不新建。</li>
 * </ul>
 */
@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    /** 单任务线程池（虚拟线程：提炼是一次 LLM 往返，无需池化） */
    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    /** token 统计的来源标签 */
    private static final String SOURCE = "memory-extract";

    /** 送提炼的助手回复截断长度（正文过长只取开头即可概括） */
    private static final int ASSISTANT_CLIP = 1200;

    /** 显式记忆的触发词：命中即视为用户明确要求记住 */
    private static final Pattern EXPLICIT = Pattern.compile("记住|牢记|记一下|帮我记|记录下来");

    /** 提炼提示词（后续如需可迁到提示词页；此处先内置，保证开箱可用） */
    private static final String SYSTEM = """
            你在为一个长期记忆系统做「记忆提炼」。输入是一段用户与助手的对话。
            请提炼稳定的用户信息、项目事实候选、Agent 的工作方法与未解决事项，输出 JSON 数组，不要输出其它文字。

            规则：
            - 用户画像必须有真实用户原话证据；项目事实与决策是待确认候选；方法经验默认私有。临时猜测不应变成确认事实。
            - evidence_quote 必须逐字引用用户消息，不能引用助手、工具、角色对白或系统推断；找不到时留空。
            - 寒暄、纯提问、无信息量的往来一律不产出（返回空数组 []）。
            - 可附 relations 数组，每项为 {"subject":"实体名","predicate":"关系","object":"实体名"}；仅表达本条正文明确支持的关系，不能补造推断。
            - 每条格式：{"scope":"profile|directive|project|fact|decision|method|open_issue|episode","title":"不超过12字的标题","body":"一句完整的中文陈述","confidence":0.0~1.0,"valid_until":null,"evidence_quote":"用户原话，不能引用助手"}
            - scope 判定：偏好 / 身份 / 长期约束→profile；对「以后要不要提起某事」的指令→directive；
              进行中的事 / 主题→project；项目的既定事实→fact；项目已定决策→decision；做法与经验→method；
              待解决事项→open_issue；一次性的时段摘要→episode。
            - 关于「用户本人」的稳定事实与偏好才算 profile/directive；小说人物、剧情设定、虚构选择**绝不是** profile。
            - valid_until 只在进行中的事有明显期限时填 "YYYY-MM-DD"，否则 null。
            - 不确定是否长期有效时给较小的 confidence，绝不编造没出现过的信息。
            - 每条必须附 temporal 对象：{"kind":"event|state|timeless|unknown","source_ref":"所依据的原消息编号","expression":"逐字引用原消息的时间短语","start":null,"end":null}。
            - event 是发生过的一次经历，state 是持续一段时间的状态，timeless 是无时间限定的长期信息；不能确定则 unknown。
            - “今天上午吃了苹果”是 event/episode，不能概括为喜欢苹果或每天吃苹果。用户自述不等于独立核实。
            - 相对时间必须以对应原消息时间和时区解释，严禁以提炼时刻或当前日期解释；跨日批次分别选择 source_ref。
            - expression 必须逐字来自所选原消息。start/end 仅填写原文明确的 ISO 日期或日期时间；未知留 null，不猜具体几点。
            - 上午/下午等保留原精度。已结束的历史事件不设 valid_until；valid_until 只控制有明确期限的当前状态。
            - 新的经历通常追加；昨天吃苹果与今天吃梨不是互相取代。只有同一状态明确变化才考虑更新。
            - 没有可记的内容就返回 []。
            """;

    private final MemoryProps props;
    private final SettingsService settings;
    private final OpenAiChatClient chat;
    private final ObjectMapper om;
    private final MemoryItemStore items;
    private final MemoryItemEmbeddingWriter embeddings;
    private final UserProfileService profiles;
    private final cc.ginpika.minis.ai.memory.store.MemoryRevocationStore revocations;
    private final cc.ginpika.minis.ai.memory.store.MemoryRetractionStore retractions;

    public MemoryExtractor(MemoryProps props, SettingsService settings, OpenAiChatClient chat,
                           ObjectMapper om, MemoryItemStore items,
                           MemoryItemEmbeddingWriter embeddings, UserProfileService profiles,
                           cc.ginpika.minis.ai.memory.store.MemoryRevocationStore revocations,
                           cc.ginpika.minis.ai.memory.store.MemoryRetractionStore retractions) {
        this.props = props;
        this.settings = settings;
        this.chat = chat;
        this.om = om;
        this.items = items;
        this.embeddings = embeddings;
        this.profiles = profiles;
        this.revocations = revocations;
        this.retractions = retractions;
    }

    /**
     * 异步提炼一轮对话（调用方 push 触发；失败隔离，不阻塞、不抛错）。
     *
     * @param exec          执行作用域（谁 / 哪个项目 / 会话 / 任务；决定归属与 source_kind）
     * @param userText      本轮用户输入（信息量门槛的依据）
     * @param assistantText 本轮助手回复（可空）
     */
    public void extractAsync(MemoryExec exec, String userText, String assistantText) {
        if (!props.isEnabled() || userText == null) {
            return;
        }
        /* memory-v6 §4.2：深层写点拿不到透传作用域时，取执行入口圈定的当前作用域（MemoryScopeCtx）；
           两者皆空则放弃这次提炼（绝不写无归属的记忆；I1 的告警/拒绝在 MemoryIngest.tap 处已发生） */
        MemoryExec scope = ScopeGuard.resolve(exec);
        if (scope == null) {
            log.debug("记忆提炼跳过：无执行作用域（memory-v6 I1）");
            return;
        }
        String u = userText.strip();
        boolean explicit = isExplicit(u);
        if (u.length() < props.getNoiseMinChars() && !explicit) {
            return;
        }
        long generation=retractions.generation();
        List<ChatMessage> messages = buildMessages(u, assistantText, explicit);
        POOL.submit(() -> {
            try {
                /* 提炼跑在异步虚拟线程上：把作用域一并带过去，写入审计（revision）才记得住「是哪个
                   Agent / 哪一轮」写的（memory-v6 §6.1）；ThreadLocal 在此线程内自带清理 */
                MemoryScopeCtx.runVoid(scope, () -> extract(scope, messages, explicit, null, false, generation));
            } catch (RuntimeException e) {
                log.warn("记忆提炼失败（不影响主流程）：{}", e.getMessage());
            }
        });
    }

    /**
     * 异步提炼一段「合并对话」（攒批 / 空闲触发用）：{@code conversation} 已是多轮拼接好的文本，
     * 直接作为 user 素材交给模型提炼——一次调用覆盖多轮，省掉逐轮的固定提示词与输出开销。
     */
    public void extractConversationAsync(MemoryExec exec, String conversation, boolean explicit) {
        extractConversationAsync(exec, conversation, explicit, null);
    }

    public java.util.concurrent.CompletableFuture<Boolean> extractConversationAsync(MemoryExec exec, String conversation, boolean explicit, String refs) {
        if (!props.isEnabled() || conversation == null || conversation.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        MemoryExec scope = ScopeGuard.resolve(exec);
        if (scope == null) {
            log.debug("记忆提炼跳过：无执行作用域（memory-v6 I1）");
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        long generation=retractions.generation();
        List<ChatMessage> messages = List.of(new ChatMessage("system", SYSTEM),
                new ChatMessage("user", "【逐消息时间依据】\n" + MemoryTime.material(refs) + "\n【材料】\n" + conversation));
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return MemoryScopeCtx.run(scope, () -> extract(scope, messages, explicit, refs, false, generation));
            } catch (RuntimeException e) {
                log.warn("记忆提炼失败（不影响主流程）：{}", e.getMessage());
                return false;
            }
        }, POOL);
    }

    public java.util.concurrent.CompletableFuture<Boolean> distillAsync(MemoryExec exec, String text, String refs) {
        if (!props.isEnabled()) { return java.util.concurrent.CompletableFuture.completedFuture(false); }
        long generation=retractions.generation();
        String prompt = SYSTEM + "\n这是任务收尾材料。只保留经过验证、跨任务仍有价值的 method 与 open_issue。"
                + "不要把临时结论、工具原始输出、剧情选择或未验证假设当成确认事实。";
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> MemoryScopeCtx.run(exec,
                () -> extract(exec, List.of(new ChatMessage("system", prompt), new ChatMessage("user", "【来源时间】\n"+MemoryTime.material(refs)+"\n"+text)),
                        false, refs, true, generation)), POOL);
    }

    /** 显式记忆触发词判定（用户明确要求记住）：供攒批缓冲判断是否该立即提炼、也用于门槛放行 */
    public static boolean isExplicit(String text) {
        return text != null && EXPLICIT.matcher(text).find();
    }

    /** 真提炼：一次 LLM 往返 → 解析 → 按归属落条目 / 画像候选（+ 异步补向量） */
    private boolean extract(MemoryExec exec, List<ChatMessage> messages, boolean explicit, String refs, boolean distilling, long generation) {
        ModelTarget target = target();
        if (!target.configured()) {
            return false;
        }
        String content = chat.chat(target, messages, SOURCE).content();
        List<Draft> drafts = parse(content);
        String sources = exec.taskId() == null ? refs
                : MemoryEvidence.merge(refs, MemoryEvidence.ref("task", exec.taskId(), null));
        return retractions.writeIfCurrent(generation, sources, () -> persist(exec, drafts, explicit, refs, distilling));
    }

    private boolean persist(MemoryExec exec, List<Draft> drafts, boolean explicit, String refs, boolean distilling) {
        if (drafts.isEmpty()) {
            return true;
        }
        String source = explicit ? MemoryItemStore.SOURCE_EXPLICIT : MemoryItemStore.SOURCE_AUTO;
        for (Draft d : drafts) {
            MemoryTime.Frame time = MemoryTime.extract(d.temporal(), refs, d.body(), d.scope());
            if("state".equals(time.kind()) && time.end()==null && d.validUntil()!=null) {
                time=new MemoryTime.Frame(time.kind(),time.start(),d.validUntil(),time.precision(),time.partOfDay(),
                        time.zone(),time.anchorAt(),time.sourceRef(),time.expression(),time.attribution());
            }
            String timedScope = "event".equals(time.kind()) ? "episode" : d.scope();
            d = new Draft(timedScope, d.title(), MemoryTime.normalize(d.body(), time), d.confidence(),
                    "event".equals(time.kind()) ? null : d.validUntil(), d.quote(), d.relations(), d.temporal());
            if (distilling && !List.of("method", "open_issue").contains(d.scope())) { continue; }
            if (d.confidence() < 0.6) { continue; }
            if (MemoryVocabulary.PROFILE_SCOPES.contains(d.scope())) {
                /* 画像类：只提交候选（不直写正式画像）——用户明说的证据等级为 explicit */
                if (exec.narrative() || exec.identityOrInferred() == MemoryIdentity.PROJECT_PREF) {
                    if (exec.projectId() != null) {
                        MemoryItemStore.Ownership own = ownershipFor(exec, "project");
                        String fp = MemoryVocabulary.fingerprint(own.owner(), own.projectId(), own.agentId(),
                                own.domain(), "project", d.body(), own.layer(), own.sessionId(), own.taskId());
                        if (!revocations.blocked(fp) && items.findByFingerprint(fp).isEmpty()) {
                            items.insert(own, new MemoryItemStore.Governance(exec.agentOrBuiltin(),
                                    exec.narrative() ? "fiction" : "project_pref", "inferred", "normal",
                                    exec.identityOrInferred()), "project", d.title(), d.body(), d.confidence(),
                                    source, d.validUntil(), refs);
                        }
                    }
                } else {
                    String quoteRefs = MemoryEvidence.userQuote(refs, d.quote());
                    profiles.submit(exec, d.scope(), d.title(), truncate(d.body()),
                            MemoryEvidence.count(quoteRefs) > 0 ? "explicit" : "inferred",
                            quoteRefs, d.validUntil());
                }
                continue;
            }
            MemoryItemStore.Ownership own = ownershipFor(exec, d.scope());
            String body = truncate(d.body());
            /* memory-v6 §3.3/I3–I6：草稿归属不合法（如无项目的 L3、无绑定的 TASK）→ 跳过这条草稿，
               不让模型输出把非法归属灌进库（写侧矩阵在 store 也会兜底拒绝） */
            String violation = MemoryPolicy.violation(own.owner(), own.domain(), own.layer(),
                    own.projectId(), own.agentId(), own.sessionId(), own.taskId());
            if (violation != null) {
                log.debug("记忆草稿违反归属矩阵，已跳过：{}（scope={}）", violation, d.scope());
                continue;
            }
            String fp = MemoryVocabulary.fingerprint(own.owner(), own.projectId(), own.agentId(),
                    own.domain(), d.scope(), body, own.layer(), own.sessionId(), own.taskId());
            var existing=items.findByFingerprint(fp);
            if(existing.isPresent()) {
                items.attachEvidence(existing.get().id(),refs);
                continue;
            }
            if (revocations.blocked(fp)) {
                /* 幂等：同域同内容已存在，不重复建 */
                continue;
            }
            /* 来源类型由身份上下文派生（memory-v6 §3.4）：fiction → 只落项目域且永不入画像 */
            MemoryIdentity identity = exec.identityOrInferred();
            String sourceKind = switch (identity) {
                case FICTION -> MemoryVocabulary.SRC_FICTION;
                case PROJECT_PREF -> MemoryVocabulary.SRC_PROJECT_PREF;
                default -> MemoryVocabulary.SRC_INFERRED;
            };
            MemoryItemStore.Governance gov = new MemoryItemStore.Governance(
                    exec.agentId(), sourceKind,
                    explicit ? MemoryVocabulary.EVIDENCE_EXPLICIT : MemoryVocabulary.EVIDENCE_INFERRED,
                    MemoryVocabulary.RETENTION_NORMAL, identity);
            long id = items.insert(own, gov, d.scope(), d.title(), body, d.confidence(),
                    source, d.validUntil(), refs);
            items.setTemporal(id, time);
            items.attachTriples(id,d.relations());
            embeddings.complementAsync(id);
        }
        log.debug("记忆提炼完成：agent={} 产出 {} 条（explicit={}）",
                exec == null ? null : exec.agentId(), drafts.size(), explicit);
        return true;
    }

    /**
     * 由执行作用域 + scope 派生归属（memory-v5 §4.4）：有项目 → PROJECT×PROJECT；否则 → AGENT×AGENT。
     * 事实/进度先留任务 L3；方法/未解决事项留 Agent 私有 L2，有项目时绑定该项目。
     */
    static MemoryItemStore.Ownership ownershipFor(MemoryExec exec, String scope) {
        String project = exec.projectId();
        boolean temporary = List.of("project", "fact", "decision", "episode", "checkpoint").contains(scope);
        String layer = temporary ? "L3" : "L2";
        if (project != null && !project.isBlank()) {
            return new MemoryItemStore.Ownership(exec.userId(), "PROJECT", "PROJECT", layer,
                    project, exec.sessionId(), exec.taskId(), exec.agentOrBuiltin());
        }
        return new MemoryItemStore.Ownership(exec.userId(), temporary ? "TASK" : "AGENT",
                temporary ? "TASK_EPISODIC" : "AGENT", layer, null,
                exec.sessionId(), exec.taskId(), exec.agentOrBuiltin());
    }

    private String truncate(String body) {
        return body;
    }

    private List<ChatMessage> buildMessages(String userText, String assistantText, boolean explicit) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户】").append(userText).append('\n');
        if (assistantText != null && !assistantText.isBlank()) {
            String a = assistantText.strip();
            sb.append("【助手】").append(a.length() > ASSISTANT_CLIP
                    ? a.substring(0, ASSISTANT_CLIP) + "…" : a);
        }
        if (explicit) {
            sb.append("\n（注意：用户在本次对话中明确要求记住，请务必产出对应条目。）");
        }
        return List.of(new ChatMessage("system", SYSTEM), new ChatMessage("user", sb.toString()));
    }

    /** 提炼用模型：配置了 extract-model 且就绪就用它，否则沿用聊天默认模型 */
    private ModelTarget target() {
        String id = props.getExtractModel();
        if (id != null && !id.isBlank()) {
            ModelTarget t = settings.resolveChatModel(id);
            if (t.configured()) {
                return t;
            }
        }
        return settings.chatModel();
    }

    /** 解析模型输出的 JSON 数组：容忍 ```json 围栏与前后噪声；任何异常返回空（不落条目） */
    private List<Draft> parse(String content) {
        if (content == null || content.isBlank()) { throw new IllegalArgumentException("提炼结果为空"); }
        String json = stripToArray(content);
        if (json == null) { throw new IllegalArgumentException("记忆提炼未返回合法数组"); }
        try {
            JsonNode arr = om.readTree(json);
            if (!arr.isArray()) { throw new IllegalArgumentException("提炼结果不是数组"); }
            List<Draft> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String scope = n.path("scope").asText("").trim();
                String body = n.path("body").asText("").trim();
                if (!MemoryVocabulary.SCOPES.contains(scope) || body.isEmpty()) {
                    continue;
                }
                String title = n.path("title").asText("").trim();
                double confidence = n.path("confidence").isNumber()
                        ? Math.max(0d, Math.min(1d, n.path("confidence").asDouble())) : 0.5d;
                out.add(new Draft(scope, title.isEmpty() ? null : title, body, confidence,
                        parseDate(n.path("valid_until").asText(null)), n.path("evidence_quote").asText(""),n.path("relations"),n.path("temporal")));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("记忆提炼输出解析失败", e);
        }
    }

    /** 从模型输出里截出 JSON 数组：去掉 ``` 围栏，取第一个 '[' 到最后一个 ']' */
    private static String stripToArray(String content) {
        String s = content.replace("```json", "").replace("```", "").trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        return start >= 0 && end > start ? s.substring(start, end + 1) : null;
    }

    /** "YYYY-MM-DD" → 当天结束时刻的毫秒；非法 / 空返回 null（= 不过期） */
    private Long parseDate(String text) {
        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text.trim())) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim()).plusDays(1).atStartOfDay(ZoneId.of(props.getTimeZone())).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /** 一条提炼出的记忆草稿 */
    private record Draft(String scope, String title, String body, double confidence, Long validUntil, String quote, JsonNode relations, JsonNode temporal) {
    }
}
