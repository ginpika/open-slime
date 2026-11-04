package cc.ginpika.minis.agent.roundtable;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable controller authority and an explicitly bounded wave of execution/review assignments. */
@Repository
public class CollaborationPlan {
    public record Dispatch(String todoId, String executorId, String reviewerId, String phase, String decision) {}
    public record Assignment(String sessionId, String todoId, String executorId, String reviewerId, String phase, int version, String title) {}
    public record Scope(String deliverables, String exclusions, String baseline, String roadmap) {}
    public record Plan(String sessionId, String coordinatorId, String phase, String criteria,
                       String decision, String evidence, String todoId, String executorId, String reviewerId, int version,
                       String continuationStatus, String pauseReason, Scope scope, List<Assignment> assignments) {}
    private final JdbcTemplate jdbc;
    private final cc.ginpika.minis.project.ProjectKnowledgeStore knowledge;
    public CollaborationPlan(JdbcTemplate jdbc, cc.ginpika.minis.project.ProjectKnowledgeStore knowledge) { this.jdbc = jdbc; this.knowledge = knowledge; }

    public Optional<Plan> find(String session) {
        return all(" WHERE c.session_id=?", session).stream().findFirst();
    }
    public List<Plan> list() { return all(""); }
    public Optional<Scope> scope(String session) {
        return jdbc.query("SELECT * FROM collaboration_scope WHERE session_id=?",(r,n)->new Scope(
                r.getString("deliverables"),r.getString("exclusions"),r.getString("baseline"),r.getString("roadmap")),session).stream().findFirst();
    }
    /** Human-authored goal is read live; a model cannot replace it by editing acceptance criteria. */
    public String boundary(String session) {
        var result=jdbc.query("SELECT s.title,p.name,p.note FROM hub_session s LEFT JOIN project p ON p.id=s.project_id WHERE s.id=?",
                (r,n)->"项目："+r.getString(2)+"\n项目长期方向（背景，不等于本次授权）："+r.getString(3)
                        +"\n本次会话目标（完成后停止）："+r.getString(1),session);
        if(result.isEmpty()) return "";
        StringBuilder out=new StringBuilder(result.getFirst());
        jdbc.query("SELECT content FROM (SELECT id,content FROM chat_message WHERE session_id=? AND role='user' "
                        + "AND COALESCE(event_kind,'')<>'conversation' ORDER BY id DESC LIMIT 3) ORDER BY id",
                (org.springframework.jdbc.core.RowCallbackHandler)r->out.append("\n用户要求：").append(r.getString(1)),session);
        jdbc.query("SELECT content FROM chat_message WHERE session_id=? AND event_kind='workspace_binding' ORDER BY id DESC LIMIT 1",
                (org.springframework.jdbc.core.RowCallbackHandler)r->out.append("\n当前目录约定：").append(r.getString(1)),session);
        return out.toString();
    }
    public String planningContext(String session) {
        return boundary(session)+scope(session).map(s->"\n本次交付物："+s.deliverables()+"\n本次不包含："+s.exclusions()
                +"\n当前基础与证据："+s.baseline()+"\n后续路线图（不自动执行）："+s.roadmap()).orElse("\n尚未建立本次范围；必须先保存交付物、范围外事项和现状依据，再创建或派发 TODO。");
    }
    public String workEvidence(String session) {
        StringBuilder out=new StringBuilder("\n当前任务板与已检查的结果摘要（事实线索，不扩张本次目标）：\n");
        jdbc.query("SELECT t.id,t.title,t.status,substr(t.result,1,1200),substr(t.block_reason,1,800),substr(q.contract,1,2400),substr(q.findings,1,4000),substr(q.diagnosis,1,1600) FROM roundtable_todo t LEFT JOIN collaboration_task_quality q ON q.todo_id=t.id WHERE t.session_id=? ORDER BY t.seq",
                (org.springframework.jdbc.core.RowCallbackHandler)r->out.append(r.getString(1)).append(" ").append(r.getString(2)).append(" [").append(r.getString(3))
                        .append("] 结果：").append(r.getString(4)).append("；问题：").append(r.getString(5))
                        .append("；契约：").append(r.getString(6)).append("；检查意见：").append(r.getString(7)).append("；诊断：").append(r.getString(8)).append("\n"),session);
        return out.toString();
    }
    private List<Plan> all(String where, Object... args) {
        return jdbc.query("SELECT c.*,COALESCE(p.status,'active') AS continuation_status,p.reason AS pause_reason,sc.deliverables,sc.exclusions,sc.baseline,sc.roadmap FROM collaboration_plan c "
                + "LEFT JOIN roundtable_progress p ON p.session_id=c.session_id LEFT JOIN collaboration_scope sc ON sc.session_id=c.session_id" + where, (rs, n) -> new Plan(
                rs.getString("session_id"), rs.getString("coordinator_id"), rs.getString("phase"),
                rs.getString("criteria"), rs.getString("decision"), rs.getString("evidence"),
                rs.getString("todo_id"), rs.getString("executor_id"), rs.getString("reviewer_id"), rs.getInt("version"),
                rs.getString("continuation_status"), rs.getString("pause_reason"),rs.getString("deliverables")==null?null:
                new Scope(rs.getString("deliverables"),rs.getString("exclusions"),rs.getString("baseline"),rs.getString("roadmap")),assignments(rs.getString("session_id"))), args);
    }
    public void create(String session, String coordinator) {
        jdbc.update("INSERT OR IGNORE INTO collaboration_plan(session_id,coordinator_id,phase) VALUES(?,?,'planning')",
                session, coordinator);
    }
    public boolean coordinator(String session, String peer) {
        return find(session).map(p -> p.coordinatorId().equals(peer)).orElse(false);
    }
    public boolean revise(String session, String criteria, String decision) {
        return jdbc.update("UPDATE collaboration_plan SET criteria=?,decision=?,evidence=NULL WHERE session_id=? AND phase='planning'",
                criteria, decision, session) == 1;
    }
    @org.springframework.transaction.annotation.Transactional
    public boolean revise(String session,String criteria,String decision,Scope scope) {
        if(!revise(session,criteria,decision)) return false;
        jdbc.update("INSERT INTO collaboration_scope VALUES(?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET "
                        +"deliverables=excluded.deliverables,exclusions=excluded.exclusions,baseline=excluded.baseline,roadmap=excluded.roadmap",
                session,scope.deliverables(),scope.exclusions(),scope.baseline(),scope.roadmap());
        return true;
    }
    public Optional<String> scopeReview(String session,String digest) {
        return jdbc.query("SELECT allowed,reason FROM collaboration_scope_review WHERE session_id=? AND digest=?",
                (r,n)->r.getBoolean(1)?"":r.getString(2),session,digest).stream().findFirst();
    }
    public void scopeReview(String session,String digest,boolean allowed,String reason) {
        jdbc.update("INSERT OR REPLACE INTO collaboration_scope_review VALUES(?,?,?,?)",session,digest,allowed?1:0,reason);
    }
    @org.springframework.transaction.annotation.Transactional
    public boolean dispatch(String session, String todo, String executor, String reviewer, String phase, String decision) {
        return dispatchBatch(session, List.of(new Dispatch(todo, executor, reviewer, phase, decision)), decision);
    }
    @org.springframework.transaction.annotation.Transactional
    public boolean dispatchBatch(String session, List<Dispatch> work, String decision) {
        if (work.isEmpty() || work.size() > 8 || work.stream().map(Dispatch::todoId).distinct().count() != work.size())
            throw new IllegalArgumentException("每批派工为 1–8 项，条目不能重复");
        for (var d : work) if (d.executorId().equals(d.reviewerId()) || !List.of("executing", "checking").contains(d.phase()))
            throw new IllegalArgumentException("执行与独立检查身份或阶段无效");
        var first = work.getFirst();
        boolean changed = jdbc.update("UPDATE collaboration_plan SET version=version+1,phase=?,todo_id=?,executor_id=?,reviewer_id=?,decision=? "
                        + "WHERE session_id=? AND phase='planning' AND criteria IS NOT NULL",
                first.phase(), first.todoId(), first.executorId(), first.reviewerId(), decision, session) == 1;
        if (!changed) return false;
        int version = find(session).orElseThrow().version();
        jdbc.update("DELETE FROM collaboration_assignment WHERE session_id=?", session);
        for (var d : work) jdbc.update("INSERT INTO collaboration_assignment VALUES(?,?,?,?,?,?,?)",
                session, d.todoId(), version, d.executorId(), d.reviewerId(), d.phase(), d.decision());
        return true;
    }
    public List<Assignment> assignments(String session) {
        return jdbc.query("SELECT a.*,t.title FROM collaboration_assignment a JOIN collaboration_plan c ON c.session_id=a.session_id AND c.version=a.version "
                        + "JOIN roundtable_todo t ON t.id=a.todo_id AND t.session_id=a.session_id "
                        + "WHERE a.session_id=? AND c.phase IN ('executing','checking') AND a.phase IN ('executing','checking') ORDER BY a.todo_id",
                (r,n)->new Assignment(session,r.getString("todo_id"),r.getString("executor_id"),r.getString("reviewer_id"),r.getString("phase"),r.getInt("version"),r.getString("title")),session);
    }
    public Optional<Assignment> assignment(String session, String todo) {
        return assignments(session).stream().filter(a->a.todoId().equals(todo)).findFirst();
    }
    @org.springframework.transaction.annotation.Transactional
    public boolean checking(String session, String todo, int version) {
        boolean changed = jdbc.update("UPDATE collaboration_assignment SET phase='checking' WHERE session_id=? AND todo_id=? AND version=? AND phase='executing' "
                + "AND EXISTS(SELECT 1 FROM collaboration_plan WHERE session_id=? AND version=? AND phase IN ('executing','checking'))", session,todo,version,session,version) == 1;
        if (changed) projectActive(session, version);
        return changed;
    }
    @org.springframework.transaction.annotation.Transactional
    public boolean returned(String session, String todo, String decision, int version) {
        boolean changed = jdbc.update("UPDATE collaboration_assignment SET phase='returned',decision=? WHERE session_id=? AND todo_id=? AND version=? AND phase IN ('executing','checking') "
                + "AND EXISTS(SELECT 1 FROM collaboration_plan WHERE session_id=? AND version=? AND phase IN ('executing','checking'))", decision,session,todo,version,session,version) == 1;
        if (!changed) return false;
        if (!assignments(session).isEmpty()) { projectActive(session,version); return false; }
        var reports=jdbc.query("SELECT decision FROM collaboration_assignment WHERE session_id=? AND version=? ORDER BY todo_id",(r,n)->r.getString(1),session,version);
        return jdbc.update("UPDATE collaboration_plan SET phase='planning',decision=? WHERE session_id=? AND version=? AND phase IN ('executing','checking')",
                String.join("\n\n", reports),session,version)==1;
    }
    private void projectActive(String session, int version) {
        var active=assignments(session);
        if (active.isEmpty()) return;
        var first=active.getFirst();
        jdbc.update("UPDATE collaboration_plan SET phase=?,todo_id=?,executor_id=?,reviewer_id=? WHERE session_id=? AND version=?",
                active.stream().anyMatch(a->"executing".equals(a.phase()))?"executing":"checking",first.todoId(),first.executorId(),first.reviewerId(),session,version);
    }
    /** Explicit work redirection invalidates dispatch while retaining criteria and previous evidence. */
    @org.springframework.transaction.annotation.Transactional
    public void reopen(String session) {
        jdbc.update("DELETE FROM collaboration_assignment WHERE session_id=?",session);
        jdbc.update("UPDATE collaboration_plan SET version=version+1,phase='planning',todo_id=NULL,executor_id=NULL,reviewer_id=NULL WHERE session_id=?", session);
    }
    @org.springframework.transaction.annotation.Transactional
    public boolean finish(String session, String evidence) {
        boolean finished = jdbc.update("UPDATE collaboration_plan SET phase='completed',evidence=? WHERE session_id=? "
                + "AND phase='planning' AND criteria IS NOT NULL", evidence, session) == 1;
        if (finished) knowledge.recordDelivery(session);
        return finished;
    }
    public String context(String session, String peer, boolean working) {
        return find(session).map(p -> """
                \n<collaboration_control>
                主控：%s（id=%s）；当前阶段：%s。
                %s
                本批仍在途的明确派工：%s
                整体验收标准：%s
                最近调度决定：%s
                整体验证：%s
                %s
                </collaboration_control>
                """.formatted(p.coordinatorId(), p.coordinatorId(), p.phase(), planningContext(session), p.assignments(), p.criteria(), p.decision(), p.evidence(),
                working ? "你只负责当前分配的执行或检查，不能扩张或重排任务板。新发现写入成果或受阻原因，交由主控处理。"
                        : p.coordinatorId().equals(peer) ? PROTOCOL
                        : "你提供意见和证据，只有主控能管理计划、派工与交付。" )).orElse("");
    }
    private static final String PROTOCOL = """
            你是此任务的主控，负责动态规划和最终交付。以当前用户目标、代码与验证证据决定下一步。
            项目是长期方向；会话是本次授权范围；TODO 是当前会话内的一项独立可验证工作；议题只解决局部疑问。
            本次目标达成即交付，不把长期项目方向、未来构想或历史成果自动扩张为本次实施目标。
            首先核对绑定目录的现状、工具能力和已有成果，再用 manage_plan(action=revise, criteria=验收标准,
            deliverables=本次交付物, exclusions=本次不包含, baseline=实查现状与证据, roadmap=后续方向, note=计划依据) 保存本次范围。
            开始前通过 read_project_knowledge 读取项目现状及相关决策/成果，按相关性核对文件，避免重复全量调查和重做已有工作。
            对可独立完成的资料检索、方案比较或证据核查，主动用 delegate_subagent 提供自足问题与预期证据；
            当前决策依赖结果时 background=false 直接取回结论，独立旁路调查可 background=true。简单工作直接完成，不机械调用。
            子代理不继承整段对话，不能修改代码、执行命令或扩张计划；代码定位、规划与审阅可在编码任务内用 delegate_agent 专家。
            取得关键决策、阶段成果或阻塞结论后，用 write_project_knowledge 更新项目资料；说明依据、成果位置、验证限制和下一步。
            确认交付前维护 overview 项目现状（已完成、未完成/阻塞、下一步）及相关 decision/reference 文档；交付时系统另存完整成果快照。
            技术选型/可行性会话优先交付比较、决策依据、风险和必要的最小实验；完整业务实现属于后续推进，除非用户明确扩大本次范围。
            只有你能 add_todo / update_todo 创建、合并（修订保留条目并 dropped 被替代条目）、取消或改派工作。
            采用逐步细化规划：远期工作只写 roadmap，任务板仅展开近期已具备条件的步骤，取得实际结果后再确定下游。
            每项待办只围绕一个可独立检查的产出或待验证假设；detail 写清与本次交付物的关系、输入/前置证据、边界、产出和验收方法。
            一条同时包含工程初始化、运行时、渲染、玩法、服务端或封装等多个可分别验收的阶段时，应按真实依赖拆开。
            从现有基础与最大不确定性决定下一步，不能默认从零重建，也不能在上游尚未验证时预先铺满整款产品。
            选型会话先给简短候选判断和可能推翻判断的关键风险，再派最小实验，依据结果修订决策；不要先建完整工程与长报告才开始选型。
            新工作建议附 contract：outcome（一个结果或假设）、acceptanceCriteria（唯一 id/text 的验收项）、stopCondition（取得什么证据就停止）。
            历史任务可沿用原 detail，不为补字段重做成果。近期可执行步骤写成 TODO，远期只保留 roadmap，不按固定任务数量机械拆分。
            发现同因受阻先集中分析，优先修订原条目，避免重复补救任务。
            识别近期已就绪且互不依赖的工作，用 start_work(assignments=[{id:条目ID,reviewer:另一位成员},...],note=独立性与合入依据) 一批派出 1–8 项。
            同一默认 Agent 配置可以承担多项，每项由独立执行实例与代码副本处理；不要为并行复制永久 Agent。
            有先后依赖、同一文件或接口需要共同修改的工作分批执行；共享文件由一项工作负责，其他项提供接口约定。
            不确定时可继续用 start_work(id=条目ID,reviewer=另一位成员,note=依据) 单项派工。不会自动执行未授权下游。
            执行者提交后由另一位成员独立检查；通过、拒绝或受阻都会回到你这里，未经你再次派工不自动返工或启动下游。
            主控优先负责计划与整合，不代替执行者落地后再充当独立检查者。编码改动必须通过同一副本的实际验证和另一成员复核后应用。
            检查不通过时读具体缺陷和已有尝试，决定原条目返工、改派、调整依赖、取消，或请求用户。
            重复未落实同一要求时，先核对实际接收回执、指令来源、工具字段、文件落点与错误证据，不继续强化措辞或声称用户新授权。
            APPLICATION_CONFLICT 和 NEEDS_DIAGNOSIS 是执行链路问题，先修复验收/应用记录或派明确诊断任务，不重新派原实现、不移除真实依赖绕过。
            审阅区分 BLOCKER 与 MINOR；明确缺陷位置、期望结果、验收依据或正确性风险。一般建议集中处理，不能每轮悄悄增加验收范围。
            需要设计意见时用 request_discussion，with 指明相关成员；议题结论由你决定如何应用，不能自动解锁工作。
            所有待办结束后仍须独立核对整体目标、代码差异、构建/测试和必要运行结果，
            用 manage_plan(action=finish, evidence=实际整体验证及结果) 确认交付；普通回复或 propose_end 不代表完成。
            每次主控发言结束前必须明确派工、提出具体议题、请求用户或确认交付；不要只写接下来会做什么。
            派工后结束本次发言，等执行检查回报；主控不必批准每次普通工具调用。
            """;
}
