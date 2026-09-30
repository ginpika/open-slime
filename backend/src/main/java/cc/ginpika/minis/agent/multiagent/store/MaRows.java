package cc.ginpika.minis.agent.multiagent.store;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * ma_* 各 store 共用的取值助手。
 *
 * <p><b>为什么需要它</b>：SQLite 的 JDBC 驱动按值的实际大小决定 Java 类型——同一个
 * {@code INTEGER} 列，存毫秒时间戳时回 {@code Long}，存行 id 这类小整数时回 {@code Integer}。
 * 于是 {@code (Long) rs.getObject("message_id")} 这种写法在时间戳列上一直相安无事，
 * 一碰到小整数就抛 {@code ClassCastException: Integer cannot be cast to Long}
 * （多智能体的事件表按 message_id 指回消息行时正是这样炸的）。
 *
 * <p>所有可空整型列一律经本类取值，不要在 store 里直接写 {@code (Long) rs.getObject(...)}。
 */
final class MaRows {

    private MaRows() {
    }

    /** 可空整型列 → Long；NULL 仍返回 null（不折成 0，让「没有」与「是 0」保持可区分） */
    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
}
