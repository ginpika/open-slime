package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.agent.runtime.WorkingSetRestorer;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.memory.MemoryConsolidator;
import cc.ginpika.minis.ai.memory.MemoryHealthService;
import cc.ginpika.minis.ai.memory.MemoryMetrics;
import cc.ginpika.minis.ai.memory.MemoryPromoter;
import cc.ginpika.minis.ai.memory.MemoryPublisher;
import cc.ginpika.minis.ai.memory.MemoryPurgeService;
import cc.ginpika.minis.ai.memory.MemoryTemporalService;
import cc.ginpika.minis.ai.memory.MemoryVocabulary;
import cc.ginpika.minis.ai.memory.UserProfileService;
import cc.ginpika.minis.ai.memory.WorkingSetService;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.MemoryCandidateStore.CandidateRow;
import cc.ginpika.minis.ai.memory.store.MemoryEdgeStore;
import cc.ginpika.minis.ai.memory.store.MemoryEdgeStore.EdgeRow;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryPromotionStore;
import cc.ginpika.minis.ai.memory.store.MemoryRevisionStore;
import cc.ginpika.minis.ai.memory.store.MemoryShareStore.ShareRow;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆管理 API（记忆 v5）：让记忆对用户可见、可控——条目增删改查 + 固定 + 手动巩固 +
 * 画像<b>候选审批/撤销</b> + L4 <b>共享发布/撤销</b>。
 *
 * <p>读侧（召回 / 注入）不在这里，见 {@link MemoryItemRecall} 与 {@code MemoryProvider}。
 * 管理页是「人工」作用域：以 {@code isHuman=true} 的 {@link MemoryExec} 读全量（含归档），并可用
 * 乐观锁（{@code version}）编辑、可撤销画像、可发布共享。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    @org.springframework.beans.factory.annotation.Autowired
    private cc.ginpika.minis.agent.chat.store.HubSessionStore hubSessions;
    @org.springframework.beans.factory.annotation.Autowired
    private cc.ginpika.minis.project.ProjectStore projects;
    private final MemoryItemStore items;
    private final MemoryConsolidator consolidator;
    private final UserProfileService profiles;
    private final MemoryPublisher publisher;
    private final MemoryEdgeStore edges;
    /** 工作集（memory-v6 §13）：工作状态与长期记忆分离，但同页可查 */
    private final WorkingSetService workingSet;
    /** 记忆体检（memory-v6 §15.4）：污染 / 挤占 / 漂移 */
    private final MemoryHealthService health;
    /** 任务检查点（工作状态的一部分）：按 taskId 查看「上次做到哪一步」 */
    private final TaskStore tasks;
    /** 双时态点查（memory-v6 §12.4）：as-of 与取代链 */
    private final MemoryTemporalService temporal;
    /** 显式遗忘（memory-v6 §12.5）：先预览后执行 */
    private final MemoryPurgeService purge;
    /** 提升扫描（memory-v6 §9.3）：L2 → L4 候选 */
    private final MemoryPromoter promoter;
    /** 写入审计（memory-v6 §6.1）：revision 查询 */
    private final MemoryRevisionStore revisionsStore;
    /** 工作状态恢复（memory-v6 §9.2）：检查点 → 工作集 */
    private final WorkingSetRestorer restorer;

    public MemoryController(MemoryItemStore items, MemoryConsolidator consolidator,
                            UserProfileService profiles, MemoryPublisher publisher,
                            MemoryEdgeStore edges, WorkingSetService workingSet,
                            MemoryHealthService health, TaskStore tasks,
                            MemoryTemporalService temporal, MemoryPurgeService purge,
                            MemoryPromoter promoter, MemoryRevisionStore revisionsStore,
                            WorkingSetRestorer restorer) {
        this.items = items;
        this.consolidator = consolidator;
        this.profiles = profiles;
        this.publisher = publisher;
        this.edges = edges;
        this.workingSet = workingSet;
        this.health = health;
        this.tasks = tasks;
        this.temporal = temporal;
        this.purge = purge;
        this.promoter = promoter;
        this.revisionsStore = revisionsStore;
        this.restorer = restorer;
    }

    /** 摘要：按 scope 分组（记忆页首屏）+ 候选/共享/待接收/工作集计数 */
    @GetMapping("/summary")
    public SummaryView summary() {
        List<MemoryItemStore.ItemRow> all = items.listAll();
        List<ScopeGroup> groups = new ArrayList<>();
        for (String scope : MemoryVocabulary.SCOPES) {
            List<ItemView> view = all.stream().filter(r -> scope.equals(r.scope()))
                    .map(ItemView::of).toList();
            groups.add(new ScopeGroup(scope, MemoryItemRecall.scopeLabel(scope), view.size(), view));
        }
        int active = (int) all.stream().filter(MemoryController::isActive).count();
        int inbox = publisher.inbox(MemoryExec.system(null), 100).size();
        return new SummaryView(groups, active, all.size(), profiles.pending().size(),
                publisher.published().size(), inbox, workingSet.countOpen());
    }

    /** 条目列表：可按 scope / owner / domain / layer / identity / status / projectId / agentId / sessionId 过滤 */
    @GetMapping("/items")
    public List<ItemView> items(@RequestParam(required = false) String scope,
                                @RequestParam(required = false) String owner,
                                @RequestParam(required = false) String domain,
                                @RequestParam(required = false) String layer,
                                @RequestParam(required = false) String identity,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String projectId,
                                @RequestParam(required = false) String agentId,
                                @RequestParam(required = false) String sessionId) {
        List<MemoryItemStore.ItemRow> rows = scope == null || scope.isBlank()
                ? items.listAll() : items.listByScope(scope);
        return rows.stream()
                .filter(r -> eq(owner, r.owner()))
                .filter(r -> eq(domain, r.domain()))
                .filter(r -> eq(layer, r.layer()))
                .filter(r -> eq(identity, r.identity()))
                .filter(r -> eq(status, r.status()))
                .filter(r -> eq(projectId, r.projectId()))
                .filter(r -> eq(agentId, r.agentId()))
                .filter(r -> eq(sessionId, r.sessionId()))
                .map(ItemView::of).toList();
    }

    /** 人工新增条目：按归属 + 治理落库（source=manual）。归属缺省由 scope / 项目推导，随后仍过写侧矩阵（I3–I6） */
    @PostMapping("/items")
    public ItemView create(@RequestBody ItemRequest req) {
        String scope = req.scope() == null ? "" : req.scope().trim();
        if (!MemoryVocabulary.SCOPES.contains(scope)) {
            throw new IllegalArgumentException("scope 非法：" + req.scope());
        }
        String body = req.body() == null ? "" : req.body().trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("记忆正文不能为空");
        }
        String projectId = blank(req.projectId());
        String owner = orDefault(blank(req.owner()), ownerFor(scope, projectId));
        /* v5 起 agent_id 空已规范化 'builtin'：人工表单少填 Agent 不再算违例 */
        String agentId = MemoryVocabulary.OWNER_AGENT.equals(owner)
                ? orDefault(blank(req.agentId()), MemoryExec.BUILTIN) : blank(req.agentId());
        MemoryItemStore.Ownership own = new MemoryItemStore.Ownership(
                req.userId(), owner, orDefault(blank(req.domain()), domainFor(owner)),
                orDefault(blank(req.layer()), layerFor(owner, scope)), projectId,
                blank(req.sessionId()), req.taskId(), agentId);
        MemoryItemStore.Governance gov = new MemoryItemStore.Governance(
                agentId, MemoryVocabulary.SRC_REAL, MemoryVocabulary.EVIDENCE_EXPLICIT,
                orDefault(req.retention(), MemoryVocabulary.RETENTION_NORMAL),
                req.identity() == null ? MemoryIdentity.REAL : MemoryIdentity.parse(req.identity()));
        long id = items.insert(own, gov, scope, blank(req.title()), body, 0.9,
                MemoryItemStore.SOURCE_MANUAL, req.validUntil(), null);
        return ItemView.of(require(id));
    }

    /** 编辑条目的标题 / 正文 */
    @PutMapping("/items/{id}")
    public ItemView update(@PathVariable long id, @RequestBody ItemRequest req) {
        require(id);
        String body = req.body() == null ? "" : req.body().trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("记忆正文不能为空");
        }
        if (req.version() == null) { throw new IllegalArgumentException("编辑记忆必须提供 version"); }
        {
            int changed = items.updateContentCas(id, blank(req.title()), body, req.version());
            if (changed == 0) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "版本冲突，请刷新后重试");
            }
        }
        return ItemView.of(require(id));
    }

    /** 删除条目（连带向量投影与共享授权） */
    @DeleteMapping("/items/{id}")
    public CountResult delete(@PathVariable long id) {
        publisher.revokeByItem(id);
        return new CountResult(items.delete(id));
    }

    /** 固定 / 取消固定：固定的条目强制注入，不受相关性门控与预算过滤 */
    @PostMapping("/items/{id}/pin")
    public ItemView pin(@PathVariable long id, @RequestBody(required = false) PinRequest req) {
        boolean pinned = req == null || req.pinned() == null || req.pinned();
        items.setPinned(id, pinned);
        return ItemView.of(require(id));
    }

    /** 撤销条目（用户动作）：置 revoked，画像另写墓碑 */
    @PostMapping("/items/{id}/revoke")
    public CountResult revoke(@PathVariable long id, @RequestBody(required = false) RevokeRequest req) {
        MemoryItemStore.ItemRow it = require(id);
        if (MemoryVocabulary.OWNER_USER.equals(it.owner())) {
            return new CountResult(profiles.revoke(id, req == null ? null : req.reason()));
        }
        return new CountResult(purge.purge(new MemoryPurgeService.Filter(List.of(id), null, null,
                null, null, null, req == null ? null : req.reason())));
    }

    @PostMapping("/items/{id}/confirm-project")
    public CountResult confirmProject(@PathVariable long id, @RequestBody ItemRequest req) {
        if (req.version() == null) { throw new IllegalArgumentException("确认需要 version"); }
        return new CountResult(publisher.confirmProject(id, req.version()));
    }

    @PostMapping("/sessions/{sessionId}/complete")
    public CountResult completeMemory(@PathVariable String sessionId) {
        var row = hubSessions.findById(sessionId).orElseThrow();
        MemoryExec exec = MemoryExec.chat(row.agentId(), row.projectId(), row.id(), null,
                row.projectId() != null && projects.isNarrative(row.projectId()));
        items.closeSession(exec);
        return new CountResult(workingSet.distill(exec));
    }

    /** 手动触发一次巩固（去重 / 裁剪） */
    @PostMapping("/consolidate")
    public CountResult consolidate() {
        return new CountResult(consolidator.consolidate());
    }

    /** 清空全部记忆 */
    @DeleteMapping
    public CountResult clear() {
        return new CountResult(items.clearAll());
    }

    // ---------------------------------------------------------------- 画像候选

    /** 候选列表（默认 pending） */
    @GetMapping("/candidates")
    public List<CandidateView> candidates(@RequestParam(required = false) String status) {
        return profiles.list(status == null || status.isBlank() ? "pending" : status)
                .stream().map(CandidateView::of).toList();
    }

    /** 审批候选 → 正式画像 */
    @PostMapping("/candidates/{id}/approve")
    public CountResult approve(@PathVariable long id) {
        return new CountResult((int) profiles.approve(id));
    }

    /** 拒绝候选（写墓碑，同指纹不再自动重建） */
    @PostMapping("/candidates/{id}/reject")
    public CountResult reject(@PathVariable long id, @RequestBody(required = false) RevokeRequest req) {
        return new CountResult(profiles.reject(id, req == null ? null : req.reason()));
    }

    // ---------------------------------------------------------------- 共享 / 交接 / 提升（L4）

    /** 把某条目发布给指定接收方（L4；发布动作即来源凭据） */
    @PostMapping("/items/{id}/share")
    public ShareView share(@PathVariable long id, @RequestBody ShareRequest req) {
        require(id);
        if (req == null || req.toKind() == null || req.toKind().isBlank()) {
            throw new IllegalArgumentException("缺少共享目标 toKind");
        }
        long shareId = publisher.publish(id, req.toKind(), req.toRef(), req.note());
        return publisher.sharesOf(id).stream().filter(s -> s.id() == shareId)
                .map(ShareView::of).findFirst().orElseThrow();
    }

    /** 发起交接：发布 + 记一条 offered（接收方在收件箱 accept / done 跟进） */
    @PostMapping("/items/{id}/handoff")
    public HandoffView handoff(@PathVariable long id, @RequestBody ShareRequest req) {
        require(id);
        if (req == null || req.toKind() == null || req.toKind().isBlank()) {
            throw new IllegalArgumentException("缺少交接目标 toKind");
        }
        long[] ids = publisher.handoff(id, req.toKind(), req.toRef(), req.note());
        return new HandoffView(ids[1], id, req.toKind(), req.toRef(), "offered", req.note(),
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    /** 已发布共享列表 */
    @GetMapping("/shared")
    public List<ShareView> shared() {
        return publisher.published().stream().map(ShareView::of).toList();
    }

    /** 撤销一条共享 */
    @PostMapping("/shared/{id}/revoke")
    public CountResult revokeShare(@PathVariable long id) {
        return new CountResult(publisher.revoke(id));
    }

    /** 收件箱：待我（当前作用域）接收的交接（管理页为人工作用域 = 全量待接收） */
    @GetMapping("/inbox")
    public List<InboxView> inbox() {
        return publisher.inbox(MemoryExec.system(null), 100).stream().map(InboxView::of).toList();
    }

    /** 交接状态机：accept / reject / done */
    @PostMapping("/inbox/{id}/{state}")
    public CountResult moveHandoff(@PathVariable long id, @PathVariable String state) {
        return new CountResult(publisher.move(id, state));
    }

    /** 记一条提升候选（L2→L4 / L4→L1）；落地走 apply */
    @PostMapping("/items/{id}/promote")
    public PromotionView promote(@PathVariable long id, @RequestBody(required = false) PromoteRequest req) {
        String toLayer = req == null || req.toLayer() == null || req.toLayer().isBlank()
                ? MemoryVocabulary.LAYER_L4 : req.toLayer().trim();
        long promotionId = publisher.promote(id, toLayer,
                req == null || req.evidenceCount() == null ? 1 : req.evidenceCount(),
                req == null ? null : req.note());
        return PromotionView.of(publisher.promotionCandidates(200).stream()
                .filter(p -> p.id() == promotionId).findFirst()
                .orElseThrow(() -> new IllegalStateException("提升记录未落库：" + promotionId)));
    }

    /** 待决提升候选 */
    @GetMapping("/promotions")
    public List<PromotionView> promotions() {
        return publisher.promotionCandidates(200).stream().map(PromotionView::of).toList();
    }

    /** 落地提升（改 layer + 记来源凭据） */
    @PostMapping("/promotions/{id}/apply")
    public CountResult applyPromotion(@PathVariable long id) {
        return new CountResult(publisher.applyPromotion(id));
    }

    // ---------------------------------------------------------------- 工作集 / 体检（memory-v6 §13 / §15.4）

    /** 当前工作集：给 sessionId / taskId 就是那一份，都不给 = 人工作用域看全量待办中的工作项 */
    @GetMapping("/working-set")
    public List<WorkingView> workingSet(@RequestParam(required = false) String sessionId,
                                       @RequestParam(required = false) Long taskId,
                                       @RequestParam(required = false) Integer limit) {
        int cap = limit == null ? 100 : limit;
        if (sessionId != null && !sessionId.isBlank()) {
            return workingSet.bySession(sessionId, cap).stream().map(WorkingView::of).toList();
        }
        MemoryExec exec = new MemoryExec(null, null, null, sessionId, taskId,
                cc.ginpika.minis.ai.core.MemorySurface.SYSTEM, true, null, MemoryIdentity.REAL);
        return workingSet.open(exec, cap).stream().map(WorkingView::of).toList();
    }

    /** 人工登记一条工作状态（临时结论 / 进度）：工作集与长期记忆分离，故单独入口 */
    @PostMapping("/working-set")
    public WorkingView addWorking(@RequestBody WorkingRequest req) {
        if (req == null || req.body() == null || req.body().isBlank()) {
            throw new IllegalArgumentException("工作项正文不能为空");
        }
        MemoryExec exec = new MemoryExec(null, null, null, req.sessionId(), req.taskId(),
                cc.ginpika.minis.ai.core.MemorySurface.SYSTEM, true, null, MemoryIdentity.REAL);
        long id = workingSet.record(exec, req.kind(), req.title(), req.body(), "manual", null,
                req.expiresAt());
        if (id == 0) {
            throw new IllegalArgumentException("工作项必须绑定会话或任务（sessionId / taskId）");
        }
        return workingSet.open(exec, 200).stream().filter(w -> w.id() == id).map(WorkingView::of)
                .findFirst().orElseThrow();
    }

    /** 关闭一条工作项 */
    @PostMapping("/working-set/{id}/close")
    public CountResult closeWorking(@PathVariable long id) {
        return new CountResult(workingSet.close(id));
    }

    /** 任务检查点（工作状态的一部分）：查看「上次做到哪一步」 */
    @GetMapping("/working-set/checkpoint")
    public CheckpointView checkpoint(@RequestParam long taskId) {
        String status = tasks.task(taskId).map(TaskStore.TaskRow::status).orElse("unknown");
        return new CheckpointView(taskId, status, tasks.checkpointOf(taskId));
    }

    /** 手动恢复某任务的工作状态（把检查点还原进工作集；幂等，已还原过返回 0） */
    @PostMapping("/restore")
    public CountResult restore(@RequestParam long taskId) {
        return new CountResult(restorer.restore(taskId));
    }

    /** 记忆体检：三病（污染 / 挤占 / 漂移）报告 */
    @GetMapping("/health")
    public MemoryHealthService.Report health() {
        return health.report();
    }

    /** 运行指标（memory-v6 §17.2）：不变量是否被守住、预算挤出、快照覆盖率（进程内计数，重启清零） */
    @GetMapping("/metrics")
    public java.util.Map<String, Object> metrics() {
        return MemoryMetrics.snapshot();
    }

    // ---------------------------------------------------------------- 双时态 / 审计 / 遗忘 / 提升（memory-v6 §12）

    /** 双时态点查（T7）：{@code at} 时刻仍有效的条目与知识边（旧值只关闭有效期，历史照样查得到） */
    @GetMapping("/as-of")
    public AsOfView asOf(@RequestParam long at, @RequestParam(required = false) Integer limit) {
        int cap = limit == null ? 100 : limit;
        MemoryTemporalService.AsOfView view = temporal.asOf(at, cap);
        return new AsOfView(view.at(), view.items().stream().map(ItemView::of).toList(),
                view.edges().stream().map(EdgeView::of).toList());
    }

    /** 某条目的取代链（旧 → 新）：看清这条记忆的演化 */
    @GetMapping("/items/{id}/history")
    public List<ItemView> history(@PathVariable long id) {
        return temporal.history(id).stream().map(ItemView::of).toList();
    }

    /** 写入审计：某条目（或全部）的变更记录，新的在前 */
    @GetMapping("/revisions")
    public List<RevisionView> revisions(@RequestParam(required = false) Long itemId,
                                        @RequestParam(required = false) Integer limit) {
        int cap = limit == null ? 100 : limit;
        return (itemId == null ? revisionsStore.recent(cap) : revisionsStore.byItem(itemId, cap))
                .stream().map(RevisionView::of).toList();
    }

    /** 显式遗忘预览（§12.5）：先看影响面（命中 / 有效 / 其中正式画像）再决定 */
    @PostMapping("/purge/preview")
    public PurgePreviewView purgePreview(@RequestBody(required = false) PurgeRequest req) {
        MemoryPurgeService.Preview p = purge.preview(filterOf(req), 20);
        return new PurgePreviewView(p.matched(), p.active(), p.userProfile(),
                p.sample().stream().map(ItemView::of).toList());
    }

    /** 执行显式遗忘：置 revoked + 写墓碑（同指纹不再自动重建） */
    @PostMapping("/purge")
    public CountResult executePurge(@RequestBody(required = false) PurgeRequest req) {
        return new CountResult(purge.purge(filterOf(req)));
    }

    /** 触发一次提升扫描（L2 → L4 候选）：只产候选，落地走 /promotions/{id}/apply */
    @PostMapping("/promote/scan")
    public CountResult promoteScan() {
        return new CountResult(promoter.scan());
    }

    private static MemoryPurgeService.Filter filterOf(PurgeRequest req) {
        if (req == null) {
            return new MemoryPurgeService.Filter(null, null, null, null, null, null, null);
        }
        return new MemoryPurgeService.Filter(req.itemIds(), req.owner(), req.agentId(),
                req.projectId(), req.scope(), req.before(), req.reason());
    }

    // ---------------------------------------------------------------- 知识边（双时态 · P4）

    /** 仍有效的知识边列表（管理页 / 导出） */
    @GetMapping("/edges")
    public List<EdgeView> edges() {
        return edges.activeAll(200).stream().map(EdgeView::of).toList();
    }

    /**
     * 人工加一条知识边：同域同主谓的矛盾旧边会被**关闭有效期**（双时态，不删除）。
     * 自动抽取（P4 增强）走同一落点。
     */
    @PostMapping("/edges")
    public EdgeView addEdge(@RequestBody EdgeRequest req) {
        if (req == null || isBlank(req.subject()) || isBlank(req.predicate()) || isBlank(req.object())) {
            throw new IllegalArgumentException("subject / predicate / object 均不能为空");
        }
        MemoryItemStore.ItemRow it = req.sourceItem() == null ? null : items.findById(req.sourceItem()).orElse(null);
        String owner = req.owner() == null ? (it != null ? it.owner() : MemoryVocabulary.OWNER_USER) : req.owner();
        String id = edges.add(req.userId(), owner, req.projectId(), req.agentId(),
                req.subject().trim(), req.predicate().trim(), req.object().trim(),
                req.confidence() == null ? 0.7 : req.confidence(), req.validAt(), req.sourceItem());
        return edges.activeAll(200).stream().filter(e -> e.id().equals(id))
                .map(EdgeView::of).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- 内部

    private MemoryItemStore.ItemRow require(long id) {
        return items.findById(id).orElseThrow(() -> new IllegalArgumentException("条目不存在：" + id));
    }

    private static boolean isActive(MemoryItemStore.ItemRow r) {
        return MemoryVocabulary.STATUS_ACTIVE.equals(r.status())
                && r.supersededBy() == null
                && (r.validUntil() == null || r.validUntil() > System.currentTimeMillis())
                && (r.expiresSoft() == null || r.expiresSoft() > System.currentTimeMillis());
    }

    private static boolean eq(String filter, String value) {
        return filter == null || filter.isBlank() || filter.equals(value);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String orDefault(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v;
    }

    /** 由 scope / 项目推导缺省 owner（人工新增兜底）：画像类 → USER；给了项目 → PROJECT；否则 → AGENT 私有 */
    private static String ownerFor(String scope, String projectId) {
        if (MemoryVocabulary.PROFILE_SCOPES.contains(scope)) {
            return MemoryVocabulary.OWNER_USER;
        }
        return projectId != null ? MemoryVocabulary.OWNER_PROJECT : MemoryVocabulary.OWNER_AGENT;
    }

    /** 由 owner 推导缺省 domain（归属决定描述对象：memory-v6 §3.3） */
    private static String domainFor(String owner) {
        return switch (owner == null ? "" : owner) {
            case MemoryVocabulary.OWNER_USER -> MemoryVocabulary.DOMAIN_USER_PROFILE;
            case MemoryVocabulary.OWNER_PROJECT -> MemoryVocabulary.DOMAIN_PROJECT;
            case MemoryVocabulary.OWNER_TASK -> MemoryVocabulary.DOMAIN_TASK;
            default -> MemoryVocabulary.DOMAIN_AGENT;
        };
    }

    /** 由 owner / scope 推导缺省 layer，并夹到 owner 允许的集合（AGENT 不得 L1） */
    private static String layerFor(String owner, String scope) {
        String o = owner == null ? "" : owner;
        if (MemoryVocabulary.OWNER_USER.equals(o)) {
            return MemoryVocabulary.LAYER_L1;
        }
        if (MemoryVocabulary.OWNER_TASK.equals(o)) {
            return MemoryVocabulary.LAYER_L3;
        }
        String layer = MemoryVocabulary.defaultLayer(scope);
        if (MemoryVocabulary.OWNER_AGENT.equals(o) && MemoryVocabulary.LAYER_L1.equals(layer)) {
            return MemoryVocabulary.LAYER_L2;
        }
        return layer;
    }

    /** 一条记忆条目（读侧视图） */
    public record ItemView(long id, String agentId, String owner, String domain, String layer,
                           String projectId, String sessionId, Long taskId, String scope,
                           String scopeLabel, String title, String body, double confidence,
                           String source, String sourceKind, String evidenceLevel, String status,
                           String retention, String identity, Long version, boolean pinned,
                           boolean active, Long validUntil, Long supersededBy, long createdAt,
                           long updatedAt, String producerAgent, String sourceRefs, Long expiresSoft,
                           Long promotedFrom, Long learnedAt, Long invalidAt) {
        static ItemView of(MemoryItemStore.ItemRow r) {
            return new ItemView(r.id(), r.agentId(), r.owner(), r.domain(), r.layer(), r.projectId(),
                    r.sessionId(), r.taskId(), r.scope(), MemoryItemRecall.scopeLabel(r.scope()),
                    r.title(), r.body(), r.confidence(), r.source(), r.sourceKind(), r.evidenceLevel(),
                    r.status(), r.retention(), r.identity(), r.version(), r.pinned(), isActive(r),
                    r.validUntil(), r.supersededBy(), r.createdAt(), r.updatedAt(), r.producerAgent(),
                    r.sourceRefs(), r.expiresSoft(), r.promotedFrom(), r.learnedAt(), r.invalidAt());
        }
    }

    /** 某 scope 的一组条目 */
    public record ScopeGroup(String scope, String label, int count, List<ItemView> items) {
    }

    /** 摘要视图 */
    public record SummaryView(List<ScopeGroup> scopes, int active, int total, int pendingCandidates,
                              int publishedShares, int inboxPending, int workingOpen) {
    }

    /** 一条画像候选（读侧视图） */
    public record CandidateView(long id, String scope, String scopeLabel, String title, String body,
                               String sourceKind, String evidenceLevel, int evidenceCount, String status,
                               long createdAt, long updatedAt, String evidenceRefs, Long validUntil) {
        static CandidateView of(CandidateRow c) {
            return new CandidateView(c.id(), c.scope(), MemoryItemRecall.scopeLabel(c.scope()),
                    c.title(), c.body(), c.sourceKind(), c.evidenceLevel(), c.evidenceCount(),
                    c.status(), c.createdAt(), c.updatedAt(), c.evidenceRefs(), c.validUntil());
        }
    }

    /** 一条共享记录（读侧视图） */
    public record ShareView(long id, long itemId, String fromOwner, String fromAgent, String toKind,
                            String toRef, String status, String note, long createdAt) {
        static ShareView of(ShareRow s) {
            return new ShareView(s.id(), s.itemId(), s.fromOwner(), s.fromAgent(), s.toKind(),
                    s.toRef(), s.status(), s.note(), s.createdAt());
        }
    }

    /** 一条知识边（读侧视图 · 双时态） */
    public record EdgeView(String id, String owner, String projectId, String agentId, String subject,
                           String predicate, String object, double confidence, Long validAt,
                           Long invalidAt, long learnedAt, boolean active) {
        static EdgeView of(EdgeRow e) {
            return new EdgeView(e.id(), e.owner(), e.projectId(), e.agentId(), e.subject(),
                    e.predicate(), e.object(), e.confidence(), e.validAt(), e.invalidAt(),
                    e.learnedAt(), e.invalidAt() == null);
        }
    }

    /** 一条工作状态（memory_work · L3）：与长期记忆物理分离，注入时一并呈现 */
    public record WorkingView(long id, String sessionId, Long taskId, String agentId, String projectId,
                              String kind, String title, String body, String status, Long expiresAt,
                              long createdAt, long updatedAt) {
        static WorkingView of(MemoryWorkStore.WorkRow w) {
            return new WorkingView(w.id(), w.sessionId(), w.taskId(), w.agentId(), w.projectId(),
                    w.kind(), w.title(), w.body(), w.status(), w.expiresAt(), w.createdAt(),
                    w.updatedAt());
        }
    }

    /** 新增工作状态请求体（必须绑定 sessionId 或 taskId） */
    public record WorkingRequest(String sessionId, Long taskId, String kind, String title, String body,
                                 Long expiresAt) {
    }

    /** 任务检查点视图：status = 任务状态；checkpoint = 上次落的工作集摘要（JSON 字符串） */
    public record CheckpointView(long taskId, String status, String checkpoint) {
    }

    /** 双时态点查结果：某时刻仍有效的条目 + 知识边 */
    public record AsOfView(long at, List<ItemView> items, List<EdgeView> edges) {
    }

    /** 一条写入审计（before/after 为紧凑 JSON 摘要，可为空） */
    public record RevisionView(long id, Long itemId, String op, String actorKind, String actorId,
                               String turnId, String beforeJson, String afterJson, String reason,
                               long createdAt) {
        static RevisionView of(MemoryRevisionStore.RevisionRow r) {
            return new RevisionView(r.id(), r.itemId(), r.op(), r.actorKind(), r.actorId(),
                    r.turnId(), r.beforeJson(), r.afterJson(), r.reason(), r.createdAt());
        }
    }

    /** 遗忘请求体（全部可空；itemIds 给了就按 id 收窄） */
    public record PurgeRequest(List<Long> itemIds, String owner, String agentId, String projectId,
                               String scope, Long before, String reason) {
    }

    /** 遗忘预览结果：影响面 + 前若干条样例 */
    public record PurgePreviewView(int matched, int active, int userProfile, List<ItemView> items) {
    }

    /** 一条交接（L4 状态机：offered|accepted|done|rejected） */
    public record HandoffView(long id, long itemId, String toKind, String toRef, String state,
                              String note, long createdAt, long updatedAt) {
    }

    /** 收件箱一条（待接收的交接 + 条目摘要） */
    public record InboxView(long handoffId, String toKind, String toRef, String state, long itemId,
                            String title, String body, String layer, String note, long createdAt) {
        static InboxView of(MemoryPublisher.InboxEntry e) {
            return new InboxView(e.handoffId(), e.toKind(), e.toRef(), e.state(), e.itemId(),
                    e.title(), e.body(), e.layer(), e.note(), e.createdAt());
        }
    }

    /** 一条提升记录（candidate → applied） */
    public record PromotionView(long id, long itemId, String fromLayer, String toLayer, String state,
                                int evidenceCount, String note, long createdAt) {
        static PromotionView of(MemoryPromotionStore.PromotionRow p) {
            return new PromotionView(p.id(), p.itemId(), p.fromLayer(), p.toLayer(), p.state(),
                    p.evidenceCount(), p.note(), p.createdAt());
        }
    }

    /** 提升请求体（缺省目标层 = L4） */
    public record PromoteRequest(String toLayer, Integer evidenceCount, String note) {
    }

    /** 新增知识边请求体 */
    public record EdgeRequest(String userId, String owner, String projectId, String agentId,
                              String subject, String predicate, String object, Double confidence,
                              Long validAt, Long sourceItem) {
    }

    /** 新增 / 编辑请求体（v5：带归属与治理字段；v6：identity + version 乐观锁） */
    public record ItemRequest(String scope, String title, String body, Long validUntil, String owner,
                              String domain, String layer, String projectId, String agentId,
                              String sessionId, Long taskId, String userId, String retention,
                              String identity, Long version) {
    }

    /** 固定请求体（缺省 = 固定） */
    public record PinRequest(Boolean pinned) {
    }

    /** 撤销 / 拒绝请求体 */
    public record RevokeRequest(String reason) {
    }

    /** 共享请求体 */
    public record ShareRequest(String toKind, String toRef, String note) {
    }

    /** 变更条数结果 */
    public record CountResult(int changed) {
    }
}
