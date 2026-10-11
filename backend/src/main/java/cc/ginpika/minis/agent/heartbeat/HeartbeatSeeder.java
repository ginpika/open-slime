package cc.ginpika.minis.agent.heartbeat;

import cc.ginpika.minis.agent.heartbeat.store.PlanStore;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.config.HeartbeatProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 内置 Agent 心跳行的幂等播种（启动时执行一次）。
 *
 * <p>心跳改成「每个 Agent 各一行」之后，内置 Agent（{@link AgentDirectory#BUILTIN_ID}）也需要自己那行：
 * 这里在缺失时补建一行，{@code content} 用内置正文 {@link HeartbeatPrompts#HEARTBEAT}，
 * {@code enabled=0}（默认关，由人在页面上开），{@code tick_ms} 取 {@link HeartbeatProps#getTickMs()}。
 *
 * <p>常驻职责（{@code heartbeat_plan}）不预置：出厂就是空表，全部由大模型自己立（{@code create_heartbeat}）。
 * DB 可破坏，故不再做任何旧值迁移。
 */
@Component
public class HeartbeatSeeder {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSeeder.class);

    private final PlanStore plans;
    private final HeartbeatProps props;

    public HeartbeatSeeder(PlanStore plans, HeartbeatProps props) {
        this.plans = plans;
        this.props = props;
        seedBuiltinHeartbeat();
    }

    /** 内置 Agent 的心跳行只播一次：这一行已在库里（用户看过或改过）就不再覆盖 */
    private void seedBuiltinHeartbeat() {
        String agentId = AgentDirectory.BUILTIN_ID;
        if (plans.heartbeat(agentId).isPresent()) {
            return;
        }
        plans.saveHeartbeat(new PlanStore.HeartbeatRow(agentId, HeartbeatPrompts.HEARTBEAT, null,
                false, Math.max(5_000L, props.getTickMs()), null));
        log.info("潜意识：已为内置 Agent 播种默认心跳（默认关闭，可在其后台页开启）");
    }
}
