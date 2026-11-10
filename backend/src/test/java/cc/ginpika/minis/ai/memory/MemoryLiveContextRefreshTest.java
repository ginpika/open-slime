package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import cc.ginpika.minis.project.ProjectProgressStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * R4 · 读取现行版本：轮内刷新使用通过校验的**当前记录正文**，同 ID 原位编辑后不再渲染旧快照；
 * 项目摘录按当前来源重新读取，而不是验证 ID 存在后继续渲染旧值。
 */
class MemoryLiveContextRefreshTest {

    private final MemoryItemStore items = mock(MemoryItemStore.class);
    private final MemoryWorkStore work = mock(MemoryWorkStore.class);
    private final ProjectProgressStore progress = mock(ProjectProgressStore.class);
    private final MemoryLiveContext context = new MemoryLiveContext(items, work);
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void refreshRendersCurrentBodyAfterInPlaceEditAndDropsRevokedItems() {
        context.setProgress(progress);
        MemoryExec exec = MemoryExec.chat("builtin", null, "chat", null, "turn-1", cc.ginpika.minis.ai.core.MemoryIdentity.REAL);
        when(items.protectedVisible(any())).thenReturn(List.of());
        when(progress.allowed(any(), any())).thenReturn(false);

        // 旧快照：同一 ID 的旧正文；另一条已在刷新前退出可见
        when(items.visibleById(eq(exec), eq(1L))).thenReturn(Optional.of(item(1L, "当前纠正后的偏好：用列表")));
        when(items.visibleById(eq(exec), eq(2L))).thenReturn(Optional.empty());

        var messages = messagesWithMemoryTag(new MemorySnapshot("turn-1", exec, 7L,
                List.of(item(1L, "旧正文：用表格"), item(2L, "已撤回条目")), List.of(), 1L, 0L));
        MemoryLiveContext.begin(messages);
        MemoryScopeCtx.runVoid(exec, () -> context.refresh(messages));

        String rendered = messages.get(0).path("content").asText();
        assertTrue(rendered.contains("当前纠正后的偏好：用列表"));
        assertFalse(rendered.contains("旧正文：用表格"));
        assertFalse(rendered.contains("已撤回条目"));
    }

    @Test
    void refreshReloadsProjectProgressInsteadOfRenderingTheSnapshotCopy() {
        context.setProgress(progress);
        MemoryExec exec = MemoryExec.chat("builtin", null, "chat", null, "turn-2", cc.ginpika.minis.ai.core.MemoryIdentity.REAL);
        when(items.protectedVisible(any())).thenReturn(List.of());
        when(items.visibleById(any(MemoryExec.class), anyLong())).thenReturn(Optional.empty());
        when(progress.allowed(any(), any())).thenReturn(true);
        when(progress.read("source-session")).thenReturn(new ProjectProgressStore.Progress(
                "source-session", "来源会话", null, "builtin", List.of(9L), "最新进度正文", 2L));

        var messages = messagesWithMemoryTag(new MemorySnapshot("turn-2", exec, 7L, List.of(), List.of(), 1L, 0L,
                List.of(new ProjectProgressStore.Progress("source-session", "来源会话", null, "builtin",
                        List.of(9L), "旧进度正文", 1L))));
        MemoryLiveContext.begin(messages);
        MemoryScopeCtx.runVoid(exec, () -> context.refresh(messages));

        String rendered = messages.get(0).path("content").asText();
        assertTrue(rendered.contains("最新进度正文"));
        assertFalse(rendered.contains("旧进度正文"));
    }

    private ArrayNode messagesWithMemoryTag(MemorySnapshot snapshot) {
        ArrayNode messages = om.createArrayNode();
        ObjectNode system = messages.addObject().put("role", "system")
                .put("content", "rules\n" + MemoryLiveContext.START + "\n旧快照\n" + MemoryLiveContext.END);
        system.set("_minis_memory", om.valueToTree(snapshot));
        return messages;
    }

    private static MemoryItemStore.ItemRow item(long id, String body) {
        return new MemoryItemStore.ItemRow(id, "builtin", "profile", "偏好", body, 0.9, "explicit",
                false, 1L, null, null, null, 1L, 1L);
    }
}
