package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆策略（memory-v6 §5 · 大纲点名的 {@code MemoryPolicy}）：读侧「谁能读到什么」与写侧
 * 「谁能写成什么」的**唯一判定来源**。
 *
 * <p><b>为什么必须单一来源</b>：v5 的读谓词散在 {@code MemoryItemStore.visible} 的拼串里、写侧矩阵只在文档里，
 * 两边各写一份必然漂移。memory-v6 §2.3 把 I1–I12 定为可测试不变量，本类就是它们的可执行形式：
 * <ul>
 *   <li>{@link #visibleSql(String)} + {@link #visibleArgs(MemoryExec)} —— 读谓词 R1–R6（含 v6 的 L3 会话隔离、
 *       L4 必须有发布来源），直接编进 SQL（禁止「全量召回再 Java 过滤」）；</li>
 *   <li>{@link #violation} —— 写侧归属 / 绑定矩阵（不变量 I3–I6），违规即拒绝（不静默纠正）。</li>
 * </ul>
 *
 * <p>无状态纯函数，故为静态工具：既便于单测逐条覆盖不变量，也避免低层 store 反过来持有 Spring 组件。
 */
public final class MemoryPolicy {

    private MemoryPolicy() {
    }

    // ---------------------------------------------------------------- 读谓词（R1–R6）

    /**
     * 可见性 SQL 片段（不含外围 {@code WHERE} 关键字）：参数占位顺序与 {@link #visibleArgs} 严格一致。
     *
     * <pre>
     * 前置：未取代、未过期（valid_until / expires_soft）
     * R1 owner='USER'    AND domain='USER_PROFILE'                       正式画像（只读）
     * R2 owner='PROJECT' AND project_id = exec.projectId                 当前项目（L1共享 / L2同Agent / L3同会话，L4仅经发布）
     * R3 owner='AGENT'   AND agent_id = exec.agentId AND layer &lt;&gt; 'L4'
     *      AND (layer &lt;&gt; 'L3' OR session_id = exec.sessionId OR task_id = exec.taskId)   ← v6：L3 会话隔离（I5）
     * R4 owner='TASK'    AND (session_id = exec.sessionId OR task_id = exec.taskId)
     * R5 EXISTS(已发布的 memory_share 命中 exec)                          显式授权共享
     * R6 exec.human                                                     管理页全量（含归档）
     * </pre>
     *
     * @param itemTable 条目表名（当前恒为 {@code memory_item}；保留参数便于测试与后续视图）
     */
    public static String visibleSql(String itemTable) {
        return "superseded_by IS NULL AND (valid_until IS NULL OR valid_until > ?) "
                + "AND (expires_soft IS NULL OR expires_soft > ?) "
                + "AND (status = 'active' OR (? = 1 AND status = 'archived')) AND ("
                + "(owner = 'USER' AND domain = 'USER_PROFILE') "
                + "OR (owner = 'PROJECT' AND project_id = ? AND (layer = 'L1' "
                + "    OR (layer = 'L2' AND agent_id = ?) "
                + "    OR (layer = 'L3' AND (session_id = ? OR task_id = ?)))) "
                + "OR (owner = 'AGENT' AND agent_id = ? AND (project_id IS NULL OR project_id = ?) AND layer <> 'L4' "
                + "    AND (layer <> 'L3' OR session_id = ? OR (task_id IS NOT NULL AND task_id = ?))) "
                + "OR (owner = 'TASK' AND (session_id = ? OR (task_id IS NOT NULL AND task_id = ?))) "
                + "OR EXISTS(SELECT 1 FROM memory_share s WHERE s.item_id = " + itemTable + ".id "
                + "    AND s.status = 'published' AND ((s.to_kind = 'project' AND s.to_ref = ?) "
                + "    OR s.to_kind = 'user' OR (s.to_kind = 'agent' AND s.to_ref = ?) "
                + "    OR (s.to_kind = 'session' AND s.to_ref = ?))) "
                + "OR ? = 1)";
    }

    /** {@link #visibleSql} 的参数，顺序一一对应 */
    public static List<Object> visibleArgs(MemoryExec exec) {
        MemoryExec e = exec == null ? MemoryExec.none() : exec;
        long now = System.currentTimeMillis();
        String agent = e.agentOrBuiltin();
        int human = e.human() ? 1 : 0;
        List<Object> args = new ArrayList<>(17);
        args.add(now);              // valid_until
        args.add(now);              // expires_soft
        args.add(human);            // status（archived 仅人工可见）
        args.add(e.projectId());    // R2
        args.add(agent);            // PROJECT L2
        args.add(e.sessionId());    // PROJECT L3
        args.add(e.taskId());       // PROJECT L3
        args.add(agent);            // R3
        args.add(e.projectId());    // AGENT 私有项目经验仍限定项目
        args.add(e.sessionId());    // R3 · L3 会话隔离
        args.add(e.taskId());       // R3 · L3 会话隔离
        args.add(e.sessionId());    // R4
        args.add(e.taskId());       // R4
        args.add(e.projectId());    // R5
        args.add(agent);            // R5
        args.add(e.sessionId());    // R5
        args.add(human);            // R6
        return args;
    }

    /** 该作用域能否读到某条归属的条目（谓词的 Java 镜像；生产读路径以 SQL 为准，本方法供写前检查与测试对照） */
    public static boolean canRead(MemoryExec exec, String owner, String domain, String projectId,
                                  String agentId, String sessionId, Long taskId, String layer) {
        MemoryExec e = exec == null ? MemoryExec.none() : exec;
        if (e.human()) {
            return true;
        }
        if ("USER".equals(owner)) {
            return "USER_PROFILE".equals(domain);
        }
        if ("PROJECT".equals(owner)) {
            return eq(e.projectId(), projectId) && ("L1".equals(layer)
                    || ("L2".equals(layer) && eq(e.agentOrBuiltin(), agentId))
                    || ("L3".equals(layer) && (eq(e.sessionId(), sessionId)
                        || (taskId != null && taskId.equals(e.taskId())))));
        }
        if ("AGENT".equals(owner)) {
            if (projectId != null && !eq(e.projectId(), projectId)) { return false; }
            if (!eq(e.agentOrBuiltin(), agentId)) {
                return false;
            }
            if ("L4".equals(layer)) {
                return false;                       // L4 必须经 R5（发布）命中，见 I6
            }
            if ("L3".equals(layer)) {
                return eq(e.sessionId(), sessionId) || (taskId != null && taskId.equals(e.taskId()));
            }
            return true;
        }
        if ("TASK".equals(owner)) {
            return eq(e.sessionId(), sessionId) || (taskId != null && taskId.equals(e.taskId()));
        }
        return false;
    }

    // ---------------------------------------------------------------- 写侧矩阵（I3–I6）

    /**
     * 归属 / 绑定组合矩阵校验（memory-v6 §3.3）。返回 {@code null} = 合法；否则返回违例说明。
     * 违规即**拒绝写入**（不静默纠正），调用方应记日志并中止。
     */
    public static String violation(String owner, String domain, String layer, String projectId,
                                   String agentId, String sessionId, Long taskId) {
        String o = nz(owner);
        String d = nz(domain);
        String l = nz(layer);
        boolean hasProject = notBlank(projectId);
        boolean hasAgent = notBlank(agentId);
        boolean hasSession = notBlank(sessionId);
        boolean hasTask = taskId != null;
        switch (o) {
            case "USER" -> {
                if (!"USER_PROFILE".equals(d)) {
                    return "owner=USER 只能承载 domain=USER_PROFILE（得到 " + d + "）";
                }
                if (!"L1".equals(l)) {
                    return "owner=USER 只能是 layer=L1（得到 " + l + "）";
                }
                if (hasProject || hasAgent || hasSession || hasTask) {
                    return "owner=USER 不得绑定 project/agent/session/task（I3）";
                }
            }
            case "PROJECT" -> {
                if (!"PROJECT".equals(d)) {
                    return "owner=PROJECT 只能承载 domain=PROJECT（得到 " + d + "）";
                }
                if (!hasProject) {
                    return "owner=PROJECT 必须绑定 project_id（I4）";
                }
                if ("L2".equals(l) && !hasAgent) {
                    return "PROJECT L2 必须绑定私有 agent_id";
                }
                if (!"L1".equals(l) && !"L2".equals(l) && !"L3".equals(l) && !"L4".equals(l)) {
                    return "owner=PROJECT 的 layer 非法：" + l;
                }
            }
            case "AGENT" -> {
                if (!"AGENT".equals(d)) {
                    return "owner=AGENT 只能承载 domain=AGENT（得到 " + d + "）";
                }
                if (!hasAgent) {
                    return "owner=AGENT 必须绑定 agent_id（I4）";
                }
                if (!"L2".equals(l) && !"L3".equals(l) && !"L4".equals(l)) {
                    return "owner=AGENT 只允许 L2/L3/L4（得到 " + l + "）";
                }
                if ("L3".equals(l) && !hasSession && !hasTask) {
                    return "layer=L3 必须绑定 session_id 或 task_id（I5）";
                }
            }
            case "TASK" -> {
                if (!"TASK_EPISODIC".equals(d)) {
                    return "owner=TASK 只能承载 domain=TASK_EPISODIC（得到 " + d + "）";
                }
                if (!"L3".equals(l)) {
                    return "owner=TASK 只能是 layer=L3（得到 " + l + "）";
                }
                if (!hasSession && !hasTask) {
                    return "owner=TASK 必须绑定 session_id 或 task_id（I4）";
                }
            }
            default -> {
                return "非法 owner：" + o;
            }
        }
        /* 全局约束：layer=L3 必须绑定；USER_PROFILE 只能出现在 L1 */
        if ("L3".equals(l) && !hasSession && !hasTask) {
            return "layer=L3 必须绑定 session_id 或 task_id（I5）";
        }
        if ("USER_PROFILE".equals(d) && !"L1".equals(l)) {
            return "domain=USER_PROFILE 只能是 layer=L1";
        }
        return null;
    }

    // ---------------------------------------------------------------- 小工具

    private static boolean eq(String a, String b) {
        return a != null && !a.isBlank() && a.equals(b);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
