package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.chat.SubagentReportEvent;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 子代理报告 → 圆桌运行时 的桥：把「成员埋头做」的产出接回讨论态。
 *
 * <p>成员派出的子代理跑完后，结论本来只投回会话（落一条气泡 + 发会话事件）。本桥补上关键一步：
 * <b>把它转成运行时事件</b>（{@code AGENT_REPORT}），于是讨论被重新激活——
 * 「成员埋头做 → 做完通知彼此 → 接着讨论」这条回边才真正接通（此前它是断的：
 * 子代理报告完全不会唤醒圆桌，全体沉默就等于停摆）。
 *
 * <p>只对多智能体会话生效，首页对话不受影响。
 */
@Component
public class SubagentReportBridge {

    private final HubSessionStore sessions;
    private final RoundtableDiscussionRuntime runtime;

    public SubagentReportBridge(HubSessionStore sessions, RoundtableDiscussionRuntime runtime) {
        this.sessions = sessions;
        this.runtime = runtime;
    }

    @EventListener
    public void onReport(SubagentReportEvent event) {
        if (event == null || event.hubId() == null) {
            return;
        }
        boolean roundtable = sessions.findById(event.hubId())
                .map(row -> RoundtableChatService.MODE.equals(row.mode()))
                .orElse(false);
        if (!roundtable) {
            return;
        }
        boolean done = "done".equals(event.status());
        String body = done
                ? (event.output() == null ? "" : event.output())
                : ("未跑出结论：" + (event.error() == null ? "未知原因" : event.error()));
        String title = event.title() == null || event.title().isBlank() ? "" : "【" + event.title() + "】";
        runtime.report(event.hubId(), "subagent", title + body, null);
    }
}
