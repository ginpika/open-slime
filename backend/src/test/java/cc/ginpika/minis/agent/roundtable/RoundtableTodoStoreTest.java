package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 圆桌共享任务区的回归：加条目、共享可见、按人领取、改状态。
 *
 * <p>「共享任务区」是讨论态与执行态之间的任务板（设计里的 Agent TODO 区）——本测试钉住它的核心语义：
 * 全桌可见；某成员能领到「自己名下的或公共的、还没做的」条目。内存 SQLite，不起 Spring 上下文。
 */
class RoundtableTodoStoreTest {

    /** 与 schema.sql 的 roundtable_todo 同构（去注释） */
    private static final String DDL = """
            CREATE TABLE roundtable_todo(
              id TEXT PRIMARY KEY,
              session_id TEXT NOT NULL,
              topic_id TEXT,
              owner_peer TEXT,
              title TEXT NOT NULL,
              detail TEXT,
              depends_on TEXT,
              priority INTEGER,
              status TEXT NOT NULL,
              created_by TEXT,
              result TEXT,
              block_reason TEXT,
              block_with TEXT,
              seq INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL)""";

    private static RoundtableTodoStore store() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DDL);
        return new RoundtableTodoStore(jdbc);
    }

    @Test
    void addsAndListsTheSharedBoard() {
        RoundtableTodoStore board = store();
        String a = board.add("s1", "t1", "p1", "查资料", "查清 X 的边界", "p1", null);
        board.add("s1", "t1", null, "写文档", null, "p2", null);

        List<RoundtableTodoStore.TodoRow> all = board.list("s1");
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).seq());
        assertEquals(RoundtableTodoStore.STATUS_PENDING, all.get(0).status());
        assertEquals("p1", board.todo(a).orElseThrow().ownerPeer());
        /* 别的会话读不到 */
        assertTrue(board.list("s2").isEmpty());
    }

    @Test
    void claimableCoversOwnAndPublicPendingOnly() {
        RoundtableTodoStore board = store();
        board.add("s1", null, "p1", "我的活", null, "p1", null);       // 自己的
        board.add("s1", null, null, "公共的活", null, "p1", null);     // 公共的
        String other = board.add("s1", null, "p2", "别人的活", null, "p2", null); // 别人的

        List<RoundtableTodoStore.TodoRow> mine = board.claimable("s1", "p1");
        assertEquals(2, mine.size(), "自己名下的 + 公共的都该领到");

        /* 已完成的公共条目不再被领 */
        String pub = mine.stream().filter(t -> t.title().equals("公共的活")).findFirst().orElseThrow().id();
        board.update(pub, RoundtableTodoStore.STATUS_DONE, "做完了", null, null, null);
        assertEquals(1, board.claimable("s1", "p1").size());

        /* 别人的条目一直领不到 */
        assertFalse(board.claimable("s1", "p1").stream().anyMatch(t -> t.id().equals(other)));
    }

    @Test
    void claimableWaitsForDependencies() {
        RoundtableTodoStore board = store();
        String upstream = board.add("s1", null, "p1", "写初稿", null, "p1", null);
        String downstream = board.add("s1", null, "p1", "基于初稿写终稿", null, "p1",
                "[\"" + upstream + "\"]");

        /* 前置未完成：下游领不到（于是它所属的执行线程不会被唤醒去白跑一趟） */
        List<RoundtableTodoStore.TodoRow> mine = board.claimable("s1", "p1");
        assertEquals(1, mine.size(), "前置未完成时下游不该可领");
        assertEquals(upstream, mine.get(0).id());

        /* 前置完成：下游解锁 */
        board.update(upstream, RoundtableTodoStore.STATUS_DONE, "初稿好了", null, null, null);
        assertTrue(board.claimable("s1", "p1").stream().anyMatch(t -> t.id().equals(downstream)),
                "前置完成后下游应可领");
    }

    @Test
    void missingDependencyIsTreatedAsSatisfied() {
        RoundtableTodoStore board = store();
        String gone = board.add("s1", null, "p1", "临时活", null, "p1", null);
        String dependent = board.add("s1", null, "p1", "依赖临时活", null, "p1",
                "[\"" + gone + "\"]");

        assertFalse(board.claimable("s1", "p1").stream().anyMatch(t -> t.id().equals(dependent)));
        /* 前置被删：按已完成处理，免得一条悬空引用把下游永久卡死 */
        board.remove(gone);
        assertTrue(board.claimable("s1", "p1").stream().anyMatch(t -> t.id().equals(dependent)));
    }

    @Test
    void claimableOrdersByPriorityThenSeq() {
        RoundtableTodoStore board = store();
        String normal = board.add("s1", null, "p1", "普通活", null, "p1", null);
        String low = board.add("s1", null, "p1", "跳过它", null, "p1", null);
        String urgent = board.add("s1", null, "p1", "立刻检查", null, "p1", null);

        board.setPriority(low, RoundtableTodoStore.PRIORITY_LOW);
        board.setPriority(urgent, RoundtableTodoStore.PRIORITY_URGENT);

        List<RoundtableTodoStore.TodoRow> order = board.claimable("s1", "p1");
        assertEquals(urgent, order.get(0).id(), "最高优先级排最前");
        assertEquals(normal, order.get(1).id(), "默认优先级居中");
        assertEquals(low, order.get(2).id(), "最低优先级排最后");
    }

    @Test
    void updatesStatusAndReason() {
        RoundtableTodoStore board = store();
        String id = board.add("s1", null, "p1", "部署", null, "p1", null);
        board.update(id, RoundtableTodoStore.STATUS_BLOCKED, null, "找不到服务器", null, null);

        RoundtableTodoStore.TodoRow row = board.todo(id).orElseThrow();
        assertEquals(RoundtableTodoStore.STATUS_BLOCKED, row.status());
        assertEquals("找不到服务器", row.blockReason());
        assertTrue(row.updatedAt() >= row.createdAt());

        assertEquals(1, board.remove(id));
        assertTrue(board.todo(id).isEmpty());
    }
}
