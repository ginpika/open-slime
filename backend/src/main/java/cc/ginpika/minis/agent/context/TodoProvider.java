package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.agent.runtime.TodoStore;
import cc.ginpika.minis.ai.core.AgentScope;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 上下文源 · 待办清单（STEP 4）：把本会话当前的 plan-of-record 追加到 system 末尾，让模型每轮都看得见。
 *
 * <p>这是「任务分解能被<b>跟踪</b>」的落点：清单写完就随上下文回灌，模型不必在正文里复述进度，
 * 也不会因为多轮往返而把哪一步做到哪忘了——它据这份清单推进，做完一条就用 {@code write_todos} 改状态。
 *
 * <p>会话没有清单时返回空串，{@link ContextBuilder} 便原样保留 system 基底（零开销）。
 * 位置排在「在跑的子代理」（{@code @Order(0)}）之后：先交代后台有哪些活，再交代本轮怎么排。
 */
@Component
@Order(20)
public class TodoProvider implements ContextProvider {

    private final TodoStore todos;

    public TodoProvider(TodoStore todos) {
        this.todos = todos;
    }

    @Override
    public String systemSuffix(ContextRequest req) {
        /* 待办清单随会话存档，只有前台会话这一路有（子代理 / 潜意识不共享它） */
        if (req.surface() != ContextRequest.Surface.CHAT) {
            return "";
        }
        String sessionId = req.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        /* 取「当前 Agent」自己那份：首页 = 内置 Agent，圆桌 = 当前成员——与写入侧（TodoService）同一口径，
           故用 AgentScope#get() 原值、不做内置兜底（兜底会把写入/读取劈成两个 key） */
        List<TodoStore.TodoItem> items = todos.list(sessionId, AgentScope.get());
        String rendered = TodoStore.render(items);
        if (rendered.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "【本轮待办清单】按它推进；做完 / 失败一条就用 write_todos 把整份清单重写一遍"
                        + "（含新状态），别在正文里复述进度：\n").append(rendered);
        /* 再规划触发：有失败项时明确要求先处置它——重试还是调整计划由模型决定，但不得视而不见 */
        if (items.stream().anyMatch(i -> TodoStore.ST_FAILED.equals(i.status()))) {
            sb.append("（清单里有失败项：先决定重试还是调整计划——改写该条或重排整份清单，别当作没发生。）");
        }
        return sb.toString();
    }
}
