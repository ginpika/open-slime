package cc.ginpika.minis.agent.subconscious;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.ai.knowledge.anchor.GraphAnchors;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import cc.ginpika.minis.ai.memory.recall.MemoryRecall;
import cc.ginpika.minis.agent.subconscious.store.PlanStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 注入器注册表：把「预存提示词里的一个插槽」解析成本次执行该填入的素材。
 *
 * <p>这是整套设计里唯一与存储层耦合的地方——它把库里的现状（文档、图谱、最近投进聊天的话、
 * 上一次维护动作）读出来变成文本。模板不改，素材每次现取，所以同一个模板在不同时刻
 * 会组装出不同的上下文：LLM 无状态，状态在这里与库里。
 *
 * <p>服务对象是<b>任务计划</b>与心跳主会话（两者都有自己的正文与插槽绑定）；
 * 子代理没有插槽这一套——它只有派它的人写的那段交代，system 是固定的内置人格。
 *
 * <p>新增可注入元素 = 在这里 {@code register("名字", ctx -> ...)}，无需改动任何已预存的提示词；
 * 提示词侧只需在 {@code injectors} 里声明 {@code {"slot": "...", "injector": "名字"}}。
 */
@Component
public class SubconsciousInjectors {

    /** 一次注入的输入：模板里声明的插槽名、注入器名、注入器参数，以及当前执行的计划 */
    public record InjectorContext(String slot, String injector, String args,
                                  PlanStore.PlanRow plan) {
    }

    /** 注入器：从存储层现取素材并渲染成可插入提示词的文本；无素材返回空串 */
    @FunctionalInterface
    public interface Injector {
        String load(InjectorContext ctx);
    }

    /** 插槽词典的一条：注入器名 + 它取什么、参数怎么给、从哪儿取（供「插槽」子页只读展示） */
    public record Info(String name, String summary, String args, String source) {
    }

    /** 注册表的一格：实现与它的词典说明放在一起，免得两处走散 */
    private record Entry(Injector impl, Info info) {
    }

    /** 把计划自己的 input 作为素材注入的注入器名 */
    public static final String PLAN_INPUT = "plan_input";

    /** 名册一次列出的任务计划条数上限 */
    private static final int ROSTER_LIMIT = 30;

    /** 名册里每条正文的开头截断长度：主会话只需要认出「这份编制管什么」 */
    private static final int ROSTER_HEAD = 160;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单个注入结果的字符上限：素材是「背景」，不该挤掉任务本身 */
    private static final int MATERIAL_LIMIT = 4000;

    private final Map<String, Entry> registry = new LinkedHashMap<>();
    private final JdbcTemplate jdbc;
    private final DocumentStore documents;
    private final ChunkStore chunks;
    private final GraphStore graph;
    private final PlanStore plans;
    private final ExecStore execs;
    /** 消息表：只读投影「最近对人说过什么」，与本组件同属取数一侧，直读存储不算越层 */
    private final ChatMessageStore chatMessages;
    /** 记忆召回（时序收敛 + 向量打分的融合读侧）：同为只读取数，经门面不直捣 store */
    private final MemoryRecall memoryRecall;

    public SubconsciousInjectors(JdbcTemplate jdbc, DocumentStore documents, ChunkStore chunks,
                                 GraphStore graph, PlanStore plans, ExecStore execs,
                                 ChatMessageStore chatMessages, MemoryRecall memoryRecall) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.chunks = chunks;
        this.graph = graph;
        this.plans = plans;
        this.execs = execs;
        this.chatMessages = chatMessages;
        this.memoryRecall = memoryRecall;
        register("none", ctx -> "", "不注入任何内容",
                "—", "恒为空串（显式占位，或临时停掉某个插槽）");
        register("now", ctx -> STAMP.format(LocalDateTime.now()), "当前时间",
                "—", "LocalDateTime.now()，格式 yyyy-MM-dd HH:mm:ss");
        register("stats", ctx -> stats(), "知识库概况：文档 / 子块（含已启用）/ 图谱实体 / 关系四项规模",
                "—", "JdbcTemplate 直查 documents、chunks、entities、relations 四张表");
        register("doc_titles", ctx -> docTitles(), "文档标题清单（带 id、子块数、类型），供模型挑该读哪一篇",
                "—", "DocumentStore.list()，最多 40 篇");
        register("doc_fulltext", this::docFullText, "指定文档的标题与全文",
                "文档 id", "DocumentStore.fullText(id)；id 解析不出则为空");
        register("chunk_text", this::chunkText, "指定子块的正文（【标题】+ 内容）",
                "子块 id 列表，逗号分隔", "ChunkStore.rowsByIds(ids)");
        register("graph_hubs", this::graphHubs, "图谱枢纽实体（连接最多的节点及其摘要）",
                "条数，默认 12（1–40）", "GraphStore.hubs(n)");
        register("graph_entity", this::graphEntity, "某个实体 + 它的一跳关系（主语 —[谓语]→ 宾语）",
                "实体名", "GraphStore.byName + edgesTouching，两端 id 反查实体名");
        register("user_profile", ctx -> userProfile(), "用户锚点画像：「用户」锚点实体 + 它的一跳关系（模型维护用户画像的第一现场）",
                "—", "GraphAnchors.USER + GraphStore.byName + edgesTouching");
        register("threads", this::threads, "最近投进首页 chat 的话（时间〔会话名〕原话）",
                "条数，默认 5（1–30）", "ChatMessageStore.latestAssistantMessages(n)");
        register("recent_results", this::recentResults, "最近几次执行的结论（名字（状态）：结论）",
                "条数，默认 5（1–30）", "ExecStore.latestExecs(null, n)，过滤空输出");
        register("plans", ctx -> plans(), "任务计划名册：全部编制及其渠道、节拍、上次派出时间、正文开头",
                "—", "PlanStore.plans()，最多 30 条，正文截 160 字");
        register(PLAN_INPUT, ctx -> ctx.plan() == null ? "" : text(ctx.plan().input()),
                "计划自己的「本轮交代」（input）",
                "—", "当前执行计划的 input 字段；渲染 input 自身时不生效");
        register("memory", this::memory, "最近发生过的事（与计划正文的语义相关者优先，未配置向量则纯时间倒序）",
                "条数，默认 10（1–50）", "MemoryRecall.recall（query 取计划正文+本轮交代）");
    }

    /** 注册/覆盖一个注入器（构造期调用；也可是运行时热插的入口，词典里记为无说明） */
    public void register(String name, Injector injector) {
        register(name, injector, "", "", "");
    }

    private void register(String name, Injector injector, String summary, String args, String source) {
        registry.put(name, new Entry(injector, new Info(name, summary, args, source)));
    }

    /** 当前可用的注入器名（前端做下拉选项用） */
    public List<String> names() {
        return List.copyOf(registry.keySet());
    }

    /** 插槽词典：全部注入器的人话说明（前端「插槽」子页只读展示） */
    public List<Info> infos() {
        return registry.values().stream().map(Entry::info).toList();
    }

    /**
     * 解析一个插槽的素材。未知注入器或注入器抛错都不致命——素材缺失只应退化为空，
     * 不该让整轮心跳失败。
     */
    public String load(String slot, String injector, String args, PlanStore.PlanRow plan) {
        Entry entry = registry.get(injector);
        if (entry == null) {
            return "";
        }
        try {
            String text = entry.impl().load(new InjectorContext(slot, injector, text(args), plan));
            if (text == null || text.isBlank()) {
                return "";
            }
            return text.length() > MATERIAL_LIMIT ? text.substring(0, MATERIAL_LIMIT) + "…" : text;
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---------- 内置注入器 ----------

    /** 知识库概况：文档/子块/实体/关系的规模，让模型知道自己面对的是多大一片记忆 */
    private String stats() {
        long docs = count("SELECT COUNT(*) FROM documents");
        long chunkTotal = count("SELECT COUNT(*) FROM chunks");
        long chunkEnabled = count("SELECT COUNT(*) FROM chunks WHERE status = 'enabled'");
        long entities = count("SELECT COUNT(*) FROM entities");
        long relations = count("SELECT COUNT(*) FROM relations");
        return "- 文档 " + docs + " 篇，子块 " + chunkTotal + " 块（其中已启用 "
                + chunkEnabled + " 块）\n"
                + "- 图谱实体 " + entities + " 个，关系 " + relations + " 条";
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    /** 文档标题清单（带子块数与类型），供模型挑「该读哪一篇」 */
    private String docTitles() {
        List<DocumentStore.DocSummary> docs = documents.list();
        if (docs.isEmpty()) {
            return "";
        }
        return docs.stream()
                .limit(40)
                .map(d -> "- [" + d.id() + "]《" + text(d.title()) + "》 " + d.chunkCount() + " 块"
                        + (d.docType() == null || d.docType().isBlank() ? "" : " · " + d.docType()))
                .collect(Collectors.joining("\n"));
    }

    /** 指定文档全文；args 为文档 id */
    private String docFullText(InjectorContext ctx) {
        Long id = asLong(ctx.args());
        if (id == null) {
            return "";
        }
        String body = documents.fullText(id);
        return body == null || body.isBlank() ? "" : "《" + text(documents.title(id)) + "》\n" + body;
    }

    /** 指定子块正文；args 为 chunk id 列表（逗号分隔） */
    private String chunkText(InjectorContext ctx) {
        List<Long> ids = new ArrayList<>();
        for (String part : ctx.args().split(",")) {
            Long id = asLong(part.trim());
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return "";
        }
        List<ChunkStore.Row> rows = chunks.rowsByIds(ids);
        return rows.stream()
                .map(r -> "【" + text(r.title()) + "】\n" + text(r.content()))
                .collect(Collectors.joining("\n\n"));
    }

    /** 图谱枢纽实体：连接最多的节点，记忆维护的第一现场 */
    private String graphHubs(InjectorContext ctx) {
        int limit = Optional.ofNullable(asLong(ctx.args())).map(Long::intValue).orElse(12);
        List<GraphStore.Entity> hubs = graph.hubs(Math.max(1, Math.min(limit, 40)));
        return hubs.stream().map(SubconsciousInjectors::entityLine).collect(Collectors.joining("\n"));
    }

    /** 某个实体及其一跳关系；args 为实体名 */
    private String graphEntity(InjectorContext ctx) {
        if (ctx.args().isBlank()) {
            return "";
        }
        return entityRelations(ctx.args());
    }

    /** 用户锚点画像：固定以「用户」锚点为圆心取实体 + 一跳关系（锚点不存在时为空，等播种） */
    private String userProfile() {
        String text = entityRelations(GraphAnchors.USER.name());
        return text.isEmpty() ? "" : "【用户画像】\n" + text;
    }

    private String entityRelations(String name) {
        GraphStore.Entity e = graph.byName(name);
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(entityLine(e));
        List<GraphStore.Edge> edges = graph.edgesTouching(List.of(e.id()));
        Map<String, String> names = namesOf(edges);
        for (GraphStore.Edge edge : edges) {
            sb.append("\n  - ").append(names.getOrDefault(edge.subjectId(), edge.subjectId()))
                    .append(" —[").append(edge.predicate()).append("]→ ")
                    .append(names.getOrDefault(edge.objectId(), edge.objectId()));
        }
        return sb.toString();
    }

    /** 最近投进首页 chat 的话（〔会话名〕+ 原话）：避免计划重复说同一件事 */
    private String threads(InjectorContext ctx) {
        int limit = Optional.ofNullable(asLong(ctx.args())).map(Long::intValue).orElse(5);
        return recentAssistantText(Math.max(1, Math.min(limit, 30)));
    }

    /**
     * 最近投进首页 chat 的 assistant 消息（跨会话）→ 注入文本：一行一条「时间〔会话名〕原话」；
     * 无记录返回空串。取数是只读投影：直读 {@link ChatMessageStore}，不经 ChatService 门面。
     */
    private String recentAssistantText(int n) {
        List<ChatMessageStore.RecentRow> rows = chatMessages.latestAssistantMessages(n);
        if (rows.isEmpty()) {
            return "";
        }
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder();
        for (ChatMessageStore.RecentRow r : rows) {
            sb.append("- ").append(stamp.format(Instant.ofEpochMilli(r.createdAt())))
                    .append("〔").append(r.sessionTitle() == null ? "" : r.sessionTitle().trim())
                    .append("〕").append(r.content() == null ? "" : r.content().trim())
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 最近若干次执行的结论（不分来源）：让「说给人听」的计划知道同伴刚做了什么 */
    private String recentResults(InjectorContext ctx) {
        int limit = Optional.ofNullable(asLong(ctx.args())).map(Long::intValue).orElse(5);
        List<ExecStore.ExecRow> rows = execs.latestExecs(null, Math.max(1, Math.min(limit, 30)));
        return rows.stream()
                .filter(r -> r.output() != null && !r.output().isBlank())
                .map(r -> "- " + text(r.title()) + "（" + r.status() + "）：" + text(r.output()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 最近记忆事件（融合召回）：一行一条「时间〔谁·在哪〕正文开头」。
     * 与 threads / recent_results 的差别：这里是跨来源的统一时间线（用户、我、计划、子代理混排），
     * 且只含 done 的执行——失败的执行不进记忆流。语义 query 取计划自己的正文（+本轮交代），
     * 于是「这份编制关心的往事」浮到前面；主会话没有计划 → 空 query = 纯时间倒序（即 M0 行为），
     * embedding 未配置/失败同样退化为时间倒序——改道不回退。
     */
    private String memory(InjectorContext ctx) {
        int limit = Optional.ofNullable(asLong(ctx.args())).map(Long::intValue).orElse(10);
        List<MemoryRecall.Hit> hits = memoryRecall.recall(planQuery(ctx.plan()),
                null, null, null, null, Math.max(1, Math.min(limit, 50)));
        return MemoryRecall.hitsText(hits);
    }

    /** 语义 query = 计划正文 + 本轮交代（input）：编制的「职责 + 这一拍要做什么」就是它的关注点 */
    private static String planQuery(PlanStore.PlanRow plan) {
        if (plan == null) {
            return "";
        }
        String q = text(plan.content());
        if (plan.input() != null && !plan.input().isBlank()) {
            q = q + "\n" + plan.input().strip();
        }
        return q;
    }

    /**
     * 任务计划名册：心跳主会话是唯一入口，它得先看清这一拍手里有哪些可派的编制。
     * 名册即全部计划（没有「启用」开关），节拍与上次派出的时间都摆出来供它自己判断该不该现在派。
     */
    private String plans() {
        List<PlanStore.PlanRow> rows = plans.plans().stream()
                .limit(ROSTER_LIMIT)
                .toList();
        if (rows.isEmpty()) {
            return "";
        }
        return rows.stream().map(SubconsciousInjectors::rosterLine).collect(Collectors.joining("\n"));
    }

    private static String rosterLine(PlanStore.PlanRow p) {
        StringBuilder sb = new StringBuilder("- ").append(text(p.name()))
                .append("（plan=").append(text(p.id())).append("）｜")
                .append(channelText(p.channels())).append("｜节拍：")
                .append(p.lastBeatAt() == null ? cadence(p) + "，从未派过"
                        : cadence(p) + "，上次 " + ago(p.lastBeatAt()));
        String head = text(p.content()).replaceAll("\\s+", " ");
        if (!head.isEmpty()) {
            sb.append("\n  它做的事：")
                    .append(head.length() > ROSTER_HEAD ? head.substring(0, ROSTER_HEAD) + "…" : head);
        }
        return sb.toString();
    }

    /** 名册里的节拍说明：interval_ms 只是给主会话的参考，不是它必须遵守的时刻表 */
    private static String cadence(PlanStore.PlanRow p) {
        Long ms = p.intervalMs();
        if (ms == null || ms <= 0) {
            return "每拍";
        }
        if (ms % 3_600_000L == 0) {
            return "每约 " + ms / 3_600_000L + " 小时";
        }
        if (ms % 60_000L == 0) {
            return "每约 " + ms / 60_000L + " 分钟";
        }
        return "每约 " + Math.max(1, ms / 1000L) + " 秒";
    }

    /** 上次派出的时间 → 「多久以前」，主会话据此对照节拍判断是否到点 */
    private static String ago(long at) {
        long minutes = Math.max(0L, (System.currentTimeMillis() - at) / 60_000L);
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        return hours < 24 ? hours + " 小时前" : hours / 24 + " 天前";
    }

    /** 交付渠道 → 人话（主会话据此知道派出去的实例能不能直接对人说话） */
    private static String channelText(String channels) {
        if (channels == null || channels.isBlank()) {
            return "静默留痕";
        }
        boolean thread = channels.contains("\"" + SubconsciousService.CH_THREAD + "\"");
        boolean silent = channels.contains("\"" + SubconsciousService.CH_SILENT + "\"");
        if (thread && silent) {
            return "静默 + 投递首页会话";
        }
        return thread ? "投递首页会话" : "静默留痕";
    }

    // ---------- 渲染助手 ----------

    private static String entityLine(GraphStore.Entity e) {
        StringBuilder sb = new StringBuilder("- ").append(text(e.name()));
        if (e.type() != null && !e.type().isBlank()) {
            sb.append('（').append(e.type()).append('）');
        }
        if (e.summary() != null && !e.summary().isBlank()) {
            sb.append('：').append(text(e.summary()));
        }
        return sb.toString();
    }

    /** 边的两端 id → 实体名（查不到回退 id，保证行可读） */
    private Map<String, String> namesOf(List<GraphStore.Edge> edges) {
        List<String> ids = edges.stream()
                .flatMap(e -> java.util.stream.Stream.of(e.subjectId(), e.objectId()))
                .distinct()
                .toList();
        Map<String, String> names = new LinkedHashMap<>();
        for (GraphStore.Entity e : graph.entitiesByIds(ids)) {
            names.put(e.id(), e.name());
        }
        return names;
    }

    private static Long asLong(String s) {
        try {
            return s == null || s.isBlank() ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }
}
