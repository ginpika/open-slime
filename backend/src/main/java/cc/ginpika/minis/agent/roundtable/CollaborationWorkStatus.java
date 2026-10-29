package cc.ginpika.minis.agent.roundtable;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded operational facts for a conversation; never exposes private reasoning or changes work. */
@Repository
public class CollaborationWorkStatus {
    private final JdbcTemplate jdbc;

    public CollaborationWorkStatus(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String describe(String session, String peer) {
        StringBuilder out = new StringBuilder("读取时的工作快照（毫秒时间戳：")
                .append(System.currentTimeMillis()).append("；快照之后执行可能继续变化）：\n");
        jdbc.query("SELECT phase,substr(decision,1,1600) FROM collaboration_plan WHERE session_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> out.append("整体阶段：").append(r.getString(1))
                        .append("；最近派工：").append(r.getString(2)).append('\n'), session);
        jdbc.query("SELECT status,reason FROM roundtable_progress WHERE session_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> out.append("推进状态：").append(r.getString(1))
                        .append("；原因：").append(r.getString(2)).append('\n'), session);
        jdbc.query("SELECT w.peer_name,t.title,w.status,w.started_at FROM roundtable_work_run w "
                        + "JOIN roundtable_todo t ON t.id=w.todo_id WHERE w.session_id=? AND w.status='running' ORDER BY w.id DESC LIMIT 5",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> out.append("会话中正在执行：").append(r.getString(1))
                        .append(" · ").append(r.getString(2)).append(" [").append(r.getString(3))
                        .append("]；开始于：").append(r.getLong(4)).append('\n'), session);
        jdbc.query("SELECT t.title,t.status,substr(t.result,1,800),substr(t.block_reason,1,800),w.status,w.error,w.started_at "
                        + "FROM roundtable_work_run w JOIN roundtable_todo t ON t.id=w.todo_id "
                        + "WHERE w.session_id=? AND w.peer_id=? ORDER BY w.id DESC LIMIT 3",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> out.append("你的执行：").append(r.getString(1))
                        .append("；待办状态：").append(r.getString(2)).append("；成果：").append(r.getString(3))
                        .append("；阻塞：").append(r.getString(4)).append("；执行状态：").append(r.getString(5))
                        .append("；错误：").append(r.getString(6)).append("；开始于：").append(r.getLong(7)).append('\n'), session, peer);
        jdbc.query("SELECT r.id,t.title,r.status,r.round,r.validation_status,substr(r.error,1,600),substr(r.answer,1,1000) "
                        + "FROM collaboration_coding_run b JOIN coding_run r ON r.id=b.run_id "
                        + "JOIN roundtable_todo t ON t.id=b.todo_id WHERE b.session_id=? "
                        + "AND (b.executor_id=? OR b.reviewer_id=? OR EXISTS(SELECT 1 FROM collaboration_plan p "
                        + "WHERE p.session_id=b.session_id AND p.coordinator_id=?)) ORDER BY r.updated_at DESC LIMIT 2",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                    out.append("编码任务 ").append(r.getString(1)).append("：").append(r.getString(2))
                            .append("；状态：").append(r.getString(3)).append("；工具轮次：").append(r.getInt(4))
                            .append("；验证：").append(r.getString(5)).append("；错误：").append(r.getString(6))
                            .append("；已提交说明：").append(r.getString(7)).append('\n');
                    jdbc.query("SELECT tool,status,substr(args,1,400),substr(result,1,600),started_at,finished_at "
                                    + "FROM coding_operation WHERE run_id=? ORDER BY id DESC LIMIT 3",
                            (org.springframework.jdbc.core.RowCallbackHandler) op -> out.append("最近操作：").append(op.getString(1))
                                    .append(" [").append(op.getString(2)).append("] ").append(op.getString(3))
                                    .append("；结果：").append(op.getString(4)).append("；开始：").append(op.getLong(5))
                                    .append("；结束：").append(op.getString(6)).append('\n'), r.getString(1));
                }, session, peer, peer, peer);
        return out.toString();
    }
}
