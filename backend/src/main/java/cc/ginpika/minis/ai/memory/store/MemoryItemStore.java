package cc.ginpika.minis.ai.memory.store;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.memory.MemoryMetrics;
import cc.ginpika.minis.ai.memory.MemoryPolicy;
import cc.ginpika.minis.ai.memory.MemoryVocabulary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * memory_item（记忆条目）的读写：记忆体系 v5 里「记忆」的一等公民。
 *
 * <p>与 {@link MemoryEventStore}（事件层 / 证据层）的分工：事件是「发生过什么的原始投影」，
 * 只追加、逐条；本表是「关于用户 / 项目的稳定信息」，会更新、会过期、会被取代。
 *
 * <p><b>v5 变化</b>：条目从「一张扁平 scope」升级为 <b>归属三维</b>——
 * {@link Ownership}（owner × domain × layer + 绑定引用，决定权限）与 {@link Governance}
 * （来源类型 / 证据等级 / 保留级别，决定治理）。读侧新增 {@link #visible}（作用域谓词，
 * 在 SQL 里直接编码 R1–R6，见 memory-v5 §5.2），写侧以 {@code fingerprint} 幂等、{@code version} 乐观锁。
 * 旧的 {@code insert(agentId, scope, ...)} 保留为兼容重载（内部派生归属）。
 */
@Repository
public class MemoryItemStore {

    /** 一条记忆条目（validUntil / supersededBy 及 v5/v6 治理字段可空） */
    public record ItemRow(long id, String agentId, String scope, String title, String body,
                          double confidence, String source, boolean pinned, long validFrom,
                          Long validUntil, Long supersededBy, String sourceRefs, long createdAt,
                          long updatedAt, String userId, String owner, String domain, String layer,
                          String projectId, String sessionId, Long taskId, String producerAgent,
                          String sourceKind, String evidenceLevel, String status, String retention,
                          Long version, String fingerprint, Long validAt, Long invalidAt,
                          Long learnedAt, Long expiredAt, String identity, Long promotedFrom,
                          Long expiresSoft) {

        /**
         * v2 兼容构造（14 参）：仅供尚未迁移到 v5 归属模型的测试/工具构造行使用。
         * 新代码请用全参构造或直接经 {@link MemoryItemStore#insert} 落库。
         */
        public ItemRow(long id, String agentId, String scope, String title, String body,
                       double confidence, String source, boolean pinned, long validFrom,
                       Long validUntil, Long supersededBy, String sourceRefs, long createdAt,
                       long updatedAt) {
            this(id, agentId, scope, title, body, confidence, source, pinned, validFrom, validUntil,
                    supersededBy, sourceRefs, createdAt, updatedAt, null, null,
                    MemoryVocabulary.defaultDomain(scope), MemoryVocabulary.defaultLayer(scope),
                    null, null, null, null, null, null, MemoryVocabulary.STATUS_ACTIVE,
                    MemoryVocabulary.RETENTION_NORMAL, 0L, null, null, null, null, null,
                    MemoryIdentity.INFERRED.name(), null, null);
        }
    }

    /** 归属（决定读写权限）：owner × domain × layer + 绑定引用 */
    public record Ownership(String userId, String owner, String domain, String layer,
                            String projectId, String sessionId, Long taskId, String agentId) {
    }

    /** 治理（决定生命周期与保护）：来源类型 / 证据等级 / 保留级别 / 身份上下文 */
    public record Governance(String producerAgent, String sourceKind, String evidenceLevel,
                             String retention, MemoryIdentity identity) {
    }

    /** 指令层：用户对记忆行为的元指令 */
    public static final String SCOPE_DIRECTIVE = "directive";
    /** 画像层：长期偏好 / 身份 / 约束 */
    public static final String SCOPE_PROFILE = "profile";
    /** 项目层：进行中的事，可过期 */
    public static final String SCOPE_PROJECT = "project";
    /** 情景层：会话 / 时段摘要 */
    public static final String SCOPE_EPISODE = "episode";
    /** 全部合法 scope（顺序即展示顺序） */
    public static final List<String> SCOPES = MemoryVocabulary.SCOPES;

    /** 来源：隐式提炼 */
    public static final String SOURCE_AUTO = "auto";
    /** 来源：用户明说「记住…」 */
    public static final String SOURCE_EXPLICIT = "explicit";
    /** 来源：人工在记忆页新增 / 编辑 */
    public static final String SOURCE_MANUAL = "manual";

    private static final String COLS = "id, agent_id, scope, title, body, confidence, source, "
            + "pinned, valid_from, valid_until, superseded_by, source_refs, created_at, updated_at, "
            + "user_id, owner, domain, layer, project_id, session_id, task_id, producer_agent, "
            + "source_kind, evidence_level, status, retention, version, fingerprint, "
            + "valid_at, invalid_at, learned_at, expired_at, identity, promoted_from, expires_soft";

    private final JdbcTemplate jdbc;
    /** 写入审计（memory-v6 §2.2 法四；可空：迁移 / 只读工具 / 单测没有它，写入照常、只是不留 revision） */
    private final MemoryRevisionStore revisions;

    /** 无审计的构造（迁移 / 只读工具 / 单测）：行为与带审计一致，只是不留 revision */
    public MemoryItemStore(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    /** Spring 装配：带写入审计（记忆的每次变更留一条 revision，见 memory-v6 §2.2 法四） */
    @Autowired
    public MemoryItemStore(JdbcTemplate jdbc, MemoryRevisionStore revisions) {
        this.jdbc = jdbc;
        this.revisions = revisions;
    }

    /** 记一条审计（best-effort：审计失败绝不影响记忆写入本身；actor/turnId 取自执行作用域） */
    private void audit(Long itemId, String op, String before, String after, String reason) {
        if (revisions == null) {
            return;
        }
        MemoryExec scope = MemoryScopeCtx.get();
        String actorKind = scope == null ? "SYSTEM" : (scope.human() ? "USER" : "AGENT");
        String actorId = scope == null ? null : scope.agentId();
        String turnId = scope == null ? null : scope.turnId();
        revisions.add(itemId, op, actorKind, actorId, turnId, before, after, reason);
    }

    // ---------------------------------------------------------------- 写入

    /**
     * v5/v6 主写入：按归属 + 治理落一条条目。{@code fingerprint} 为空时按正文与归属计算；
     * 同指纹且仍有效（active/conflicted）的条目存在 → 由唯一索引拦截，调用方应先查重（见 {@link #findByFingerprint}）。
     *
     * <p><b>写侧矩阵校验</b>（memory-v6 §3.3 · 不变量 I3–I6）：归属/绑定组合非法即抛
     * {@link IllegalArgumentException}——在唯一写入点拦截，任何调用方都绕不过去。
     */
    @Transactional
    public long insert(Ownership o, Governance g, String scope, String title, String body,
                       double confidence, String source, Long validUntil, String sourceRefs) {
        if ("USER".equals(o.owner()) && g != null
                && (g.identity() == MemoryIdentity.FICTION || g.identity() == MemoryIdentity.PROJECT_PREF
                    || "fiction".equals(g.sourceKind()) || "project_pref".equals(g.sourceKind()))) {
            throw new IllegalArgumentException("虚构与项目偏好不可写入现实用户画像");
        }
        String bad = MemoryPolicy.violation(o.owner(), o.domain(), o.layer(), o.projectId(),
                o.agentId(), o.sessionId(), o.taskId());
        if (bad != null) {
            cc.ginpika.minis.ai.memory.MemoryMetrics.invariantViolation();
            throw new IllegalArgumentException("记忆归属违反组合矩阵（memory-v6 §3.3）：" + bad
                    + "（owner=" + o.owner() + " scope=" + scope + "）");
        }
        MemoryExec caller = MemoryScopeCtx.get();
        if (caller != null && !caller.human()) {
            if ("USER".equals(o.owner()) || "L1".equals(o.layer())) {
                throw new IllegalArgumentException("Agent 只能提交共享候选，不能直写画像或共享权威");
            }
            if (o.projectId() != null && !o.projectId().equals(caller.projectId())) {
                throw new IllegalArgumentException("不可跨项目写入");
            }
            if ("L2".equals(o.layer()) && !caller.agentOrBuiltin().equals(o.agentId())) {
                throw new IllegalArgumentException("不可写入其他 Agent 私有域");
            }
            if ("L3".equals(o.layer()) && !(o.sessionId() != null && o.sessionId().equals(caller.sessionId()))
                    && !(o.taskId() != null && o.taskId().equals(caller.taskId()))) {
                throw new IllegalArgumentException("不可写入其他任务或会话");
            }
        }
        long now = System.currentTimeMillis();
        String fp = MemoryVocabulary.fingerprint(o.owner(), o.projectId(), o.agentId(),
                o.domain(), scope, body, o.layer(), o.sessionId(), o.taskId());
        if (caller != null && !caller.human() && relearnBlocked(fp)) {
            throw new IllegalStateException("用户已要求遗忘该内容");
        }
        String status = MemoryVocabulary.STATUS_ACTIVE;
        if (caller != null && !caller.human() && "L3".equals(o.layer()) && o.sessionId() != null) {
            long closedAt = new MemoryCursorStore(jdbc).get(closureKey(o.projectId(), o.sessionId()));
            if (closedAt > 0 && cc.ginpika.minis.ai.memory.MemoryEvidence.latestObservation(sourceRefs) <= closedAt) { status = "archived"; }
        }
        if (caller != null && !caller.human() && "L3".equals(o.layer()) && o.taskId() != null) {
            long closedAt = new MemoryCursorStore(jdbc).get("closed-task:" + o.taskId());
            if (closedAt > 0 && cc.ginpika.minis.ai.memory.MemoryEvidence.latestObservation(sourceRefs) <= closedAt) {
                status = "archived";
            }
        }
        String retention = g != null && g.retention() != null
                ? g.retention()
                : MemoryVocabulary.RETENTION_NORMAL;
        jdbc.update("INSERT INTO memory_item(agent_id, scope, title, body, confidence, source, "
                        + "valid_from, valid_until, source_refs, created_at, updated_at, "
                        + "user_id, owner, domain, layer, project_id, session_id, task_id, "
                        + "producer_agent, source_kind, evidence_level, status, retention, version, "
                        + "fingerprint, valid_at, learned_at, identity) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                o.agentId(), scope, title, body, confidence, source, now, validUntil, sourceRefs,
                now, now, o.userId(), o.owner(), o.domain(), o.layer(), o.projectId(), o.sessionId(),
                o.taskId(), g == null ? null : g.producerAgent(),
                g == null ? null : g.sourceKind(), g == null ? null : g.evidenceLevel(),
                status, retention, 0L, fp, now, now,
                g == null || g.identity() == null ? null : g.identity().name());
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        audit(id, "create", null, AuditJson.kv("owner", o.owner(), "domain", o.domain(),
                "layer", o.layer(), "scope", scope, "projectId", o.projectId(), "agentId", o.agentId(),
                "source", source), null);
        return id;
    }

    /**
     * v2 兼容写入（旧签名）：按 scope 派生归属（画像→USER；其余→AGENT('builtin')×L2），
     * 供尚未升级的调用点与测试使用。新代码请用
     * {@link #insert(Ownership, Governance, String, String, String, double, String, Long, String)}。
     */
    public long insert(String agentId, String scope, String title, String body, double confidence,
                       String source, Long validUntil, String sourceRefs) {
        return insert(deriveOwnership(agentId, scope),
                new Governance(agentId, MemoryVocabulary.SRC_INFERRED, MemoryVocabulary.EVIDENCE_INFERRED,
                        MemoryVocabulary.RETENTION_NORMAL, MemoryIdentity.INFERRED),
                scope, title, body, confidence, source, validUntil, sourceRefs);
    }

    /** 由 scope + agentId 派生一个合法归属（保守；绝不臆造 USER 画像） */
    public static Ownership deriveOwnership(String agentId, String scope) {
        String a = (agentId == null || agentId.isBlank()) ? MemoryExec.BUILTIN : agentId;
        if (MemoryVocabulary.PROFILE_SCOPES.contains(scope)) {
            return new Ownership(null, MemoryVocabulary.OWNER_USER, MemoryVocabulary.DOMAIN_USER_PROFILE,
                    MemoryVocabulary.LAYER_L1, null, null, null, null);
        }
        return new Ownership(null, MemoryVocabulary.OWNER_AGENT, MemoryVocabulary.DOMAIN_AGENT,
                MemoryVocabulary.LAYER_L2, null, null, null, a);
    }

    // ---------------------------------------------------------------- 读

    public boolean relearnBlocked(String fingerprint) {
        return new MemoryRevocationStore(jdbc).blocked(fingerprint);
    }

    public Optional<ItemRow> findById(long id) {
        List<ItemRow> rows = jdbc.query("SELECT " + COLS + " FROM memory_item WHERE id = ?",
                (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Transactional
    public int confirmProject(long id, long version) {
        int changed = jdbc.update("UPDATE memory_item SET layer = 'L1', evidence_level = 'explicit', "
                + "source = 'manual', version = version + 1, updated_at = ? "
                + "WHERE id = ? AND version = ? AND owner = 'PROJECT' AND layer = 'L3' AND status = 'active'",
                System.currentTimeMillis(), id, version);
        if (changed == 0) { throw new IllegalStateException("项目条目版本冲突"); }
        var it = findById(id).orElseThrow();
        String fp = MemoryVocabulary.fingerprint(it.owner(), it.projectId(), it.agentId(), it.domain(), it.scope(), it.body());
        jdbc.update("UPDATE memory_item SET fingerprint = ? WHERE id = ?", fp, id);
        audit(id, "confirm", null, AuditJson.kv("layer", "L1", "version", version + 1, "body", it.body()), "人工确认项目事实");
        return changed;
    }

    /** 按幂等指纹查仍有效（active/conflicted）的条目 */
    public Optional<ItemRow> findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        List<ItemRow> rows = jdbc.query("SELECT " + COLS + " FROM memory_item WHERE fingerprint = ? "
                        + "AND status IN ('active','conflicted') ORDER BY id DESC LIMIT 1",
                (rs, n) -> map(rs), fingerprint);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ItemRow> byIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbc.query("SELECT " + COLS + " FROM memory_item WHERE id IN (" + in + ")",
                (rs, n) -> map(rs));
    }

    /** 全部条目（含已取代 / 已过期；记忆管理页用），新建在前 */
    public List<ItemRow> listAll() {
        return jdbc.query("SELECT " + COLS + " FROM memory_item ORDER BY id DESC", (rs, n) -> map(rs));
    }

    public List<ItemRow> listByScope(String scope) {
        return jdbc.query("SELECT " + COLS + " FROM memory_item WHERE scope = ? ORDER BY id DESC",
                (rs, n) -> map(rs), scope);
    }

    /** 仍有效的条目（未取代、未过期），新建在前：巩固输入 / 无作用域读（管理页、内部流程） */
    public List<ItemRow> active(int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_item WHERE status = 'active' "
                        + "AND superseded_by IS NULL AND (valid_until IS NULL OR valid_until > ?) "
                        + "ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), System.currentTimeMillis(), limit);
    }

    /** 某 scope 下仍有效的条目 */
    public List<ItemRow> activeByScope(String scope, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_item WHERE scope = ? AND status = 'active' "
                        + "AND superseded_by IS NULL AND (valid_until IS NULL OR valid_until > ?) "
                        + "ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), scope, System.currentTimeMillis(), limit);
    }

    /** id 大于游标的仍有效条目（语义巩固增量扫描用，控 LLM 成本） */
    public List<ItemRow> activeSince(long fromIdExclusive, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_item WHERE id > ? AND status = 'active' "
                        + "AND superseded_by IS NULL AND (valid_until IS NULL OR valid_until > ?) "
                        + "ORDER BY id LIMIT ?",
                (rs, n) -> map(rs), fromIdExclusive, System.currentTimeMillis(), limit);
    }

    /**
     * 作用域可见读（memory-v5 §5.2 / memory-v6 §8.1）：谓词 R1–R6 由 {@link MemoryPolicy} 提供
     * （**唯一来源**），在 SQL 里直接编码，只返回当前作用域有权读到、且仍有效的条目。
     * **禁止先全量召回再在 Java 里过滤**——那是 v2 的漏洞（agent 私有记忆实际不存在、画像被所有会话注入）。
     *
     * @param exec  执行作用域；null = 无作用域（退化为「只读 USER 画像」，绝不读全量）
     * @param scope 可空 = 全部 scope
     */
    public List<ItemRow> visible(MemoryExec exec, String scope, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM memory_item WHERE ")
                .append(MemoryPolicy.visibleSql("memory_item"));
        List<Object> args = new ArrayList<>(MemoryPolicy.visibleArgs(exec));
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope = ?");
            args.add(scope);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), args.toArray());
    }

    // ---------------------------------------------------------------- 双时态点查（memory-v6 §12.4）

    /**
     * 点查「{@code at} 时刻仍有效」的条目（双时态，T7）：有效区间 {@code [valid_at, invalid_at)}
     * 覆盖该时刻即算有效——旧值被取代时只**关闭有效期**，所以历史时刻照样查得到。
     */
    public List<ItemRow> protectedVisible(MemoryExec exec) {
        return jdbc.query("SELECT " + COLS + " FROM memory_item WHERE "
                + MemoryPolicy.visibleSql("memory_item")
                + " AND (pinned = 1 OR retention = 'pinned' OR owner = 'USER' "
                + "OR scope IN ('directive','open_issue')) ORDER BY id",
                (rs, n) -> map(rs), MemoryPolicy.visibleArgs(exec).toArray());
    }

    public Optional<ItemRow> visibleById(MemoryExec exec, long id) {
        List<Object> args = new ArrayList<>(MemoryPolicy.visibleArgs(exec)); args.add(id);
        return jdbc.query("SELECT " + COLS + " FROM memory_item WHERE "
                + MemoryPolicy.visibleSql("memory_item") + " AND id = ?",
                (rs, n) -> map(rs), args.toArray()).stream().findFirst();
    }

    public List<ItemRow> episodesForRetention() {
        return jdbc.query("SELECT " + COLS + " FROM memory_item WHERE scope = 'episode' "
                + "AND status = 'active' ORDER BY id DESC", (rs, n) -> map(rs));
    }

    public int archiveTask(MemoryExec exec, long before) {
        return jdbc.update("UPDATE memory_item SET status='archived', version=COALESCE(version,0)+1, updated_at=? "
                + "WHERE layer='L3' AND status='active' AND created_at <= ? AND project_id IS ? "
                + "AND (session_id = ? OR task_id = ?)", System.currentTimeMillis(), before,
                exec.projectId(), exec.sessionId(), exec.taskId());
    }

    private static String closureKey(String project, String session) { return "closed:" + project + ":" + session; }

    @Transactional
    public void closeSession(MemoryExec exec) {
        if (exec.sessionId() == null) { throw new IllegalArgumentException("收尾须绑定会话"); }
        long now = System.currentTimeMillis();
        new MemoryCursorStore(jdbc).set(closureKey(exec.projectId(), exec.sessionId()), now);
        archiveTask(exec, now);
    }

    @Transactional
    public void closeTask(MemoryExec exec) {
        if (exec.taskId() == null) { throw new IllegalArgumentException("收尾须绑定任务"); }
        long now = System.currentTimeMillis();
        new MemoryCursorStore(jdbc).set("closed-task:" + exec.taskId(), now);
        archiveTask(exec, now);
    }

    public boolean authorityConflict(String owner, String project, String scope, String title, String body) {
        if (title == null || title.isBlank()) { return false; }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM memory_item WHERE owner=? AND project_id IS ? "
                + "AND scope=? AND title=? AND body<>? AND layer='L1' AND status='active' "
                + "AND superseded_by IS NULL AND (valid_until IS NULL OR valid_until>?)",
                Integer.class, owner, project, scope, title, body, System.currentTimeMillis());
        return count != null && count > 0;
    }

    public void recordControl(long itemId, String op, String note) {
        audit(itemId, op, null, AuditJson.kv("note", note), null);
    }

    public List<ItemRow> asOf(long at, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_item "
                        + "WHERE COALESCE(valid_at, created_at) <= ? AND (invalid_at IS NULL OR invalid_at > ?) "
                        + "ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), at, at, Math.max(1, limit));
    }

    /**
     * 取代链（memory-v6 §12.4）：返回该条目所在链条，按 **旧 → 新** 排序。
     * 向前找「我取代了谁」（{@code superseded_by} 指向我），向后沿 {@code superseded_by} 走；
     * 两侧各设 50 步上限，防止脏数据成环。
     */
    public List<ItemRow> history(long id) {
        ItemRow current = findById(id).orElse(null);
        if (current == null) {
            return List.of();
        }
        List<ItemRow> older = new ArrayList<>();
        ItemRow prev = predecessorOf(current.id());
        for (int i = 0; prev != null && i < 50; i++) {
            older.add(0, prev);
            prev = predecessorOf(prev.id());
        }
        List<ItemRow> chain = new ArrayList<>(older);
        ItemRow next = current;
        for (int i = 0; next != null && i < 50; i++) {
            chain.add(next);
            Long sid = next.supersededBy();
            next = sid == null ? null : findById(sid).orElse(null);
        }
        return chain;
    }

    /** 取代了 {@code id} 的那条（旧的一侧）；没有则空 */
    private ItemRow predecessorOf(long id) {
        List<ItemRow> rows = jdbc.query("SELECT " + COLS + " FROM memory_item WHERE superseded_by = ? "
                + "ORDER BY id DESC LIMIT 1", (rs, n) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 显式遗忘（purge）的候选收窄（memory-v6 §12.5）：按条件组合过滤，供「先预览后执行」两条路径共用。
     * 全部条件可空；都不给则返回最近 {@code limit} 条（人工作用域的全量语义）。
     */
    public List<ItemRow> findForPurge(Collection<Long> ids, String owner, String agentId,
                                      String projectId, String scope, Long before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM memory_item WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (ids != null && !ids.isEmpty()) {
            sql.append(" AND id IN (").append(ids.stream().map(x -> "?")
                    .collect(Collectors.joining(","))).append(')');
            args.addAll(ids);
        }
        if (owner != null && !owner.isBlank()) {
            sql.append(" AND owner = ?");
            args.add(owner);
        }
        if (agentId != null && !agentId.isBlank()) {
            sql.append(" AND agent_id = ?");
            args.add(agentId);
        }
        if (projectId != null && !projectId.isBlank()) {
            sql.append(" AND project_id = ?");
            args.add(projectId);
        }
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope = ?");
            args.add(scope);
        }
        if (before != null) {
            sql.append(" AND created_at < ?");
            args.add(before);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), args.toArray());
    }

    // ---------------------------------------------------------------- 更新（乐观锁）

    /** 编辑标题 / 正文（人工）：来源标记为 manual，version 自增（无锁，管理页单写者场景） */
    @Transactional
    public int updateContent(long id, String title, String body) {
        ItemRow old = findById(id).orElseThrow();
        return updateContentCas(id, title, body, old.version() == null ? 0 : old.version(), SOURCE_MANUAL);
    }

    /**
     * 乐观锁更新：仅当当前 version 等于期望值才更新（memory-v5 §5.3）。
     *
     * @return 受影响行数；0 = 版本冲突（调用方应返回 409、不得覆盖）
     */
    public int updateContentCas(long id, String title, String body, long expectedVersion) {
        return updateContentCas(id, title, body, expectedVersion, SOURCE_MANUAL);
    }

    /**
     * 同 {@link #updateContentCas(long, String, String, long)}，但可指定来源：人工编辑用
     * {@link #SOURCE_MANUAL}，Agent 主动改写用 {@link #SOURCE_AUTO}（审计条目里另有 actor 区分）。
     */
    @Transactional
    public int updateContentCas(long id, String title, String body, long expectedVersion, String source) {
        ItemRow old = findById(id).orElseThrow();
        String fp = MemoryVocabulary.fingerprint(old.owner(), old.projectId(), old.agentId(), old.domain(),
                old.scope(), body, old.layer(), old.sessionId(), old.taskId());
        int n = jdbc.update("UPDATE memory_item SET title = ?, body = ?, source = ?, "
                        + "fingerprint = ?, version = COALESCE(version,0) + 1, updated_at = ? WHERE id = ? AND COALESCE(version,0) = ?",
                title, body, source, fp, System.currentTimeMillis(), id, expectedVersion);
        if (n == 0) {
            /* 乐观锁挡住了一次覆盖（I9）：调用方应回 409，不得退化为 last-write-wins */
            MemoryMetrics.writeConflict();
        } else {
            jdbc.update("DELETE FROM memory_item_embedding WHERE item_id = ?", id);
            audit(id, "update", AuditJson.kv("version", expectedVersion, "title", old.title(), "body", old.body()),
                    AuditJson.kv("version", expectedVersion + 1, "title", title, "body", body, "source", source),
                    SOURCE_AUTO.equals(source) ? "Agent 主动改写" : null);
        }
        return n;
    }

    /** 取代：old 被 new 取代（old 不再参与召回），同时置 status=superseded、关闭有效期（双时态） */
    public int supersede(long oldId, long newId) {
        int n = jdbc.update("UPDATE memory_item SET superseded_by = ?, status = 'superseded', "
                        + "valid_at = COALESCE(valid_at, valid_from), invalid_at = ?, updated_at = ? "
                        + "WHERE id = ?",
                newId, System.currentTimeMillis(), System.currentTimeMillis(), oldId);
        if (n > 0) {
            audit(oldId, "supersede", AuditJson.kv("status", "active"),
                    AuditJson.kv("supersededBy", newId, "status", "superseded"), null);
        }
        return n;
    }

    /** 置冲突（并发写冲突时保留双方，交由人工/巩固裁决） */
    public int markConflicted(long id) {
        int n = jdbc.update("UPDATE memory_item SET status = 'conflicted', updated_at = ? WHERE id = ?",
                System.currentTimeMillis(), id);
        if (n > 0) {
            audit(id, "status", null, AuditJson.kv("status", "conflicted"),
                    "并发写入冲突，保留双方待裁决");
        }
        return n;
    }

    /** 置状态（archived / revoked / expired 等），version 自增 */
    public int setStatus(long id, String status) {
        int n = jdbc.update("UPDATE memory_item SET status = ?, version = COALESCE(version,0) + 1, "
                + "updated_at = ? WHERE id = ?", status, System.currentTimeMillis(), id);
        if (n > 0) {
            audit(id, "status", null, AuditJson.kv("status", status), null);
        }
        return n;
    }

    /** 提升落地（memory-v6 §9.3）：改 layer 并记来源凭据 {@code promoted_from}（L4/L1 的可追溯来源） */
    @Transactional
    public int promoteLayer(long id, String layer, long promotionId) {
        ItemRow old = findById(id).orElseThrow();
        String bad = MemoryPolicy.violation(old.owner(), old.domain(), layer, old.projectId(),
                old.agentId(), old.sessionId(), old.taskId());
        if (bad != null) { throw new IllegalArgumentException(bad); }
        String fp = MemoryVocabulary.fingerprint(old.owner(), old.projectId(), old.agentId(), old.domain(),
                old.scope(), old.body(), layer, old.sessionId(), old.taskId());
        int n = jdbc.update("UPDATE memory_item SET layer = ?, promoted_from = ?, fingerprint = ?, "
                        + "version = COALESCE(version,0) + 1, updated_at = ? WHERE id = ? AND COALESCE(version,0) = ?",
                layer, promotionId, fp, System.currentTimeMillis(), id, old.version() == null ? 0 : old.version());
        if (n > 0) {
            audit(id, "promote", null, AuditJson.kv("layer", layer, "promotionId", promotionId), null);
        }
        return n;
    }

    /** 删除条目（连带其向量投影，避免外键悬挂） */
    @Transactional
    public int delete(long id) {
        jdbc.update("DELETE FROM memory_item_embedding WHERE item_id = ?", id);
        int n = jdbc.update("DELETE FROM memory_item WHERE id = ?", id);
        if (n > 0) {
            audit(id, "purge", null, null, "物理删除（管理页）");
        }
        return n;
    }

    /** 固定 / 取消固定（人工）：固定的条目强制注入，不受相关性门控过滤 */
    public int setPinned(long id, boolean pinned) {
        int changed = jdbc.update("UPDATE memory_item SET pinned = ?, retention = ?, version=COALESCE(version,0)+1, updated_at = ? WHERE id = ?",
                pinned ? 1 : 0, pinned ? MemoryVocabulary.RETENTION_PINNED : MemoryVocabulary.RETENTION_NORMAL,
                System.currentTimeMillis(), id);
        if (changed > 0) { recordControl(id, "pin", Boolean.toString(pinned)); }
        return changed;
    }

    /** 清空全部条目（先删向量投影，再删正本）——设置页「重置记忆」用 */
    @Transactional
    public int clearAll() {
        jdbc.update("DELETE FROM memory_item_embedding");
        return jdbc.update("DELETE FROM memory_item");
    }

    static ItemRow map(ResultSet rs) throws SQLException {
        return new ItemRow(rs.getLong("id"), rs.getString("agent_id"), rs.getString("scope"),
                rs.getString("title"), rs.getString("body"), rs.getDouble("confidence"),
                rs.getString("source"), rs.getInt("pinned") != 0, rs.getLong("valid_from"),
                nullableLong(rs, "valid_until"), nullableLong(rs, "superseded_by"),
                rs.getString("source_refs"), rs.getLong("created_at"), rs.getLong("updated_at"),
                rs.getString("user_id"), rs.getString("owner"), rs.getString("domain"),
                rs.getString("layer"), rs.getString("project_id"), rs.getString("session_id"),
                nullableLong(rs, "task_id"), rs.getString("producer_agent"), rs.getString("source_kind"),
                rs.getString("evidence_level"), rs.getString("status"), rs.getString("retention"),
                nullableLong(rs, "version"), rs.getString("fingerprint"), nullableLong(rs, "valid_at"),
                nullableLong(rs, "invalid_at"), nullableLong(rs, "learned_at"),
                nullableLong(rs, "expired_at"), rs.getString("identity"),
                nullableLong(rs, "promoted_from"), nullableLong(rs, "expires_soft"));
    }

    /** 可空整数列：NULL 返回 null，否则返回 long（SQLite 的 INTEGER 取原值） */
    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
