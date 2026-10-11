package cc.ginpika.minis.ai.core.tool;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.core.tool.doc.CreateDocumentTool;
import cc.ginpika.minis.ai.core.tool.doc.SearchDocumentsTool;
import cc.ginpika.minis.ai.core.tool.graph.DeleteGraphItemTool;
import cc.ginpika.minis.ai.core.tool.graph.UpsertNodeTool;
import cc.ginpika.minis.ai.core.tool.plan.WriteTodosTool;
import cc.ginpika.minis.ai.core.tool.terminal.RunCommandTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 校验节点（STEP 4）的回归：只读放行，写入 / 执行类工具落地前过参数自检，拒了给可读理由。
 *
 * <p>钉住「只给写入 / 执行插校验，不给所有回答统一加自检」这条原则——只读工具即便是坏参数，
 * 也不在自检这一层挡（没有副作用，交由业务侧如实回绝）。
 */
class ToolValidatorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ToolValidator validator = new ToolValidator(new ToolManager(List.of(
            new SearchDocumentsTool(), new UpsertNodeTool(), new CreateDocumentTool(),
            new DeleteGraphItemTool(), new RunCommandTool(), new WriteTodosTool()), om), om);

    @Test
    void readOnlyToolsAreNotValidatedHere() {
        /* 只读工具（含未登记名）一律放行——检索没有副作用，挡它只是白白多一次往返 */
        assertNull(validator.validate(SearchDocumentsTool.NAME, "不是合法 JSON"));
        assertNull(validator.validate("not_a_tool", "{}"));
    }

    @Test
    void execToolRejectsBlankCommand() {
        assertTrue(validator.validate(RunCommandTool.NAME, "{\"target\":\"local\"}") != null);
        assertTrue(validator.validate(RunCommandTool.NAME, "{\"command\":\"ls\"}") != null);
        assertNull(validator.validate(RunCommandTool.NAME, "{\"target\":\"local\",\"command\":\"ls\"}"));
    }

    @Test
    void writeToolsRejectMissingFields() {
        assertTrue(validator.validate(CreateDocumentTool.NAME, "{\"content\":\"正文\"}") != null);
        assertTrue(validator.validate(CreateDocumentTool.NAME, "{\"title\":\"标题\"}") != null);
        assertNull(validator.validate(CreateDocumentTool.NAME, "{\"title\":\"T\",\"content\":\"C\"}"));

        /* delete_graph_item：按 target 要齐必填字段 */
        assertTrue(validator.validate(DeleteGraphItemTool.NAME, "{\"target\":\"entity\"}") != null);
        assertTrue(validator.validate(DeleteGraphItemTool.NAME,
                "{\"target\":\"relation\",\"subject\":\"A\"}") != null);
        assertNull(validator.validate(DeleteGraphItemTool.NAME,
                "{\"target\":\"relation\",\"subject\":\"A\",\"predicate\":\"P\",\"object\":\"B\"}"));
    }

    @Test
    void toolsWithoutOwnCheckPassByDefault() {
        /* upsert_node 未覆写 validate：写入档但无参数自检，默认放行 */
        assertNull(validator.validate(UpsertNodeTool.NAME, "{}"));
    }

    @Test
    void writeTodosRequiresNonEmptyList() {
        assertTrue(validator.validate(WriteTodosTool.NAME, "{\"todos\":[]}") != null);
        assertTrue(validator.validate(WriteTodosTool.NAME, "{}") != null);
        assertNull(validator.validate(WriteTodosTool.NAME,
                "{\"todos\":[{\"content\":\"查 X\",\"status\":\"pending\"}]}"));
    }
}
