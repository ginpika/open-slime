package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 普通内容使用轮初快照；权限、撤销与指令在每次模型/工具边界重新校验。 */
@Component
public class MemoryLiveContext {
    public static final String START = "<minis-memory>";
    public static final String END = "</minis-memory>";
    private static final ThreadLocal<MemorySnapshot> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<MemorySnapshot> CAPTURED = new ThreadLocal<>();
    private final MemoryItemStore items;
    private final MemoryWorkStore work;
    private cc.ginpika.minis.project.ProjectProgressStore progress;
    @org.springframework.beans.factory.annotation.Autowired
    public void setProgress(cc.ginpika.minis.project.ProjectProgressStore progress) { this.progress=progress; }
    private cc.ginpika.minis.config.MemoryProps props = new cc.ginpika.minis.config.MemoryProps();

    public MemoryLiveContext(MemoryItemStore items, MemoryWorkStore work) {
        this.items = items; this.work = work;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public void setProps(cc.ginpika.minis.config.MemoryProps props) { this.props = props; }
    public static void capture(MemorySnapshot snapshot) { CAPTURED.set(snapshot); }
    public static MemorySnapshot takeCaptured() {
        MemorySnapshot snapshot = CAPTURED.get(); CAPTURED.remove(); return snapshot;
    }
    public static MemorySnapshot begin(ArrayNode messages) {
        MemorySnapshot previous = CURRENT.get(); CURRENT.remove();
        for (var node : messages) {
            if (node instanceof ObjectNode obj && obj.has("_minis_memory")) {
                try {
                    CURRENT.set(new com.fasterxml.jackson.databind.ObjectMapper()
                            .treeToValue(obj.remove("_minis_memory"), MemorySnapshot.class));
                } catch (Exception e) { throw new IllegalStateException("记忆执行快照损坏", e); }
            }
        }
        return previous;
    }
    public static void restore(MemorySnapshot previous) {
        if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); }
    }
    public static void clear() { CURRENT.remove(); }

    public void refresh(ArrayNode messages) {
        MemorySnapshot initial = CURRENT.get();
        MemoryExec exec = MemoryScopeCtx.get();
        if (initial == null || exec == null) { return; }
        List<MemoryItemStore.ItemRow> allowed = new ArrayList<>();
        for (MemoryItemStore.ItemRow old : initial.items()) {
            if (!"directive".equals(old.scope()) && items.visibleById(exec, old.id()).isPresent()) {
                allowed.add(old);
            }
        }
        items.protectedVisible(exec).stream().filter(it -> "directive".equals(it.scope())).forEach(allowed::add);
        int maxChars = Math.max(120, props.getBodyMaxChars());
        StringBuilder text = new StringBuilder();
        java.util.Set<Long> included=new java.util.HashSet<>();
        allowed.sort(java.util.Comparator.comparing((MemoryItemStore.ItemRow it)->!"directive".equals(it.scope())));
        for(var it:allowed) {
            String line=MemoryItemRecall.oneLine(it,maxChars);
            if(included.add(it.id()) && cc.ginpika.minis.stats.TokenUsageService.estimateTokens(text+"\n"+line)<=props.getBudget().getTotal()) text.append(line).append('\n');
        }
        for (MemoryWorkStore.WorkRow old : initial.work()) {
            work.findById(old.id()).filter(w -> "open".equals(w.status())
                    && java.util.Objects.equals(w.projectId(), exec.projectId())
                    && ((w.sessionId() != null && w.sessionId().equals(exec.sessionId()))
                        || (w.taskId() != null && w.taskId().equals(exec.taskId())))
                    && (w.expiresAt() == null || w.expiresAt() > System.currentTimeMillis()))
                    .ifPresent(w -> {
                        String line=MemorySnapshotBuilder.workLine(old,maxChars);
                        if(cc.ginpika.minis.stats.TokenUsageService.estimateTokens(text+"\n"+line)<=props.getBudget().getTotal()) text.append('\n').append(line);
                    });
        }
        if (progress!=null) {
            for (var p : initial.progress()) {
                if (java.util.Objects.equals(p.projectId(),exec.projectId()) && progress.allowed(exec,p.sourceSessionId())) {
                    text.append('\n').append(cc.ginpika.minis.project.ProjectProgressStore.render(p));
                }
            }
        }
        String rendered = START + "\n当前可用记忆（含实时权限与指令校验）：\n" + text + "\n" + END;
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ObjectNode m) || !"system".equals(m.path("role").asText())) { continue; }
            String content = m.path("content").asText("");
            int start = content.indexOf(START), end = content.indexOf(END);
            if (start >= 0 && end >= start) {
                m.put("content", content.substring(0, start) + rendered + content.substring(end + END.length()));
                return;
            }
        }
    }
}
