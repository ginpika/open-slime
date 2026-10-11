package cc.ginpika.minis.agent.member;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 内置 Agent 的幂等播种（启动时执行一次）。
 *
 * <p>内置 Agent（{@link AgentDirectory#BUILTIN_ID}）承载两件事：首页对话的应答、心跳的调度——
 * 它正是「单 Agent 架构」里那个靠人格提示词切换模拟出来的单 Agent，如今被落成一个默认存在的真正成员。
 * 因此它必须恒在：这里在缺失时补建，{@link AgentDirectory#delete} 拒绝删除它。
 *
 * <p>只播种名册行；它的人设（提示词）就是全局提示词（见 {@link AgentPromptService}），
 * 心跳正文另由 {@code HeartbeatSeeder} 播种，各管各的。
 *
 * <p>构造时就会读 agent 表（{@link AgentStore#peer}，SELECT 全列）——老库可能还没有后加的列，
 * 故显式依赖结构补丁先跑完（{@code SchemaColumnPatches}），否则会以 {@code no such column} 炸在启动。
 */
@Component
@DependsOn("schemaColumnPatches")
public class AgentSeeder {

    private static final Logger log = LoggerFactory.getLogger(AgentSeeder.class);

    /** 内置 Agent 的默认展示名（人可在页面上改） */
    private static final String BUILTIN_NAME = "助手";

    private final AgentStore store;

    public AgentSeeder(AgentStore store) {
        this.store = store;
        seedBuiltin();
    }

    /** 内置 Agent 只播一次：缺失即建（sort=0 永远排在名册最前） */
    private void seedBuiltin() {
        if (store.peer(AgentDirectory.BUILTIN_ID).isPresent()) {
            return;
        }
        long now = System.currentTimeMillis();
        store.insert(new AgentStore.AgentRow(AgentDirectory.BUILTIN_ID, BUILTIN_NAME, true,
                null, null, null, 0, null, null, null, now, now, null, null));
        log.info("已播种内置 Agent「{}」：首页对话与心跳的调度者，不可删除", BUILTIN_NAME);
    }
}
