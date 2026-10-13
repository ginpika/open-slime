package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.ai.knowledge.recall.RagRecallRecorder;
import cc.ginpika.minis.ai.memory.MemorySnapshot;
import cc.ginpika.minis.ai.memory.MemorySnapshotBuilder;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import cc.ginpika.minis.config.ContextProps;
import cc.ginpika.minis.config.MemoryProps;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 上下文源 · 记忆（记忆 v6）：每轮进模型前，按本次输入在**当前作用域内**构建一份只读
 * {@link MemorySnapshot} 注入 system，让模型默认就知道「用户是谁、在哪个项目、答应过什么、我的经验」。
 *
 * <p><b>v6 变化</b>（memory-v6 §4.3 / §8.5）：
 * <ul>
 *   <li>不再自己算预算，改为 {@link MemorySnapshotBuilder} 构建快照（受保护条目豁免预算、
 *       落 {@code memory_snapshot} 可回放）；</li>
 *   <li>服务面从「只有前台」扩到 <b>CHAT / SUBAGENT / HEARTBEAT</b>：子代理读派发方作用域 + 本会话，
 *       心跳读该 Agent 自己的 L2 + 绑定项目（修 D6）；</li>
 *   <li>作用域优先取执行入口圈定的 {@link MemoryScopeCtx}（带 turnId 与身份），
 *       缺省再退回 {@link ContextRequest} 的字段；</li>
 *   <li>创作身份（FICTION）下注入真实画像时**显式标注**，避免剧情设定与用户现实混淆（§11）。</li>
 * </ul>
 *
 * <p>默认<b>开</b>：关时直接返回空串；召回失败吞掉降级为「本轮不注入」，不因记忆服务抖动打断对话。
 */
@Component
@Order(-5)
public class MemoryProvider implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(MemoryProvider.class);

    private final MemorySnapshotBuilder snapshots;
    private final ContextProps props;
    private final MemoryProps memoryProps;
    /** RAG 召回留痕：把每轮自动注入的记忆召回也记一行（memory 通道），供「RAG 召回」页回看 */
    private final RagRecallRecorder ragRecalls;

    public MemoryProvider(MemorySnapshotBuilder snapshots, ContextProps props, MemoryProps memoryProps,
                          RagRecallRecorder ragRecalls) {
        this.snapshots = snapshots;
        this.props = props;
        this.memoryProps = memoryProps;
        this.ragRecalls = ragRecalls;
    }

    @Override
    public String systemSuffix(ContextRequest req) {
        MemorySurface surface = surfaceOf(req.surface());
        if (surface == null) {
            return "";
        }
        if (!props.isInjectMemory() || !memoryProps.isEnabled()) {
            return "";
        }
        String query = req.userQuery();
        MemoryExec exec = execOf(req, surface);
        MemorySnapshot snapshot;
        try {
            snapshot = snapshots.build(exec, query, exec.turnId());
        } catch (RuntimeException e) {
            log.warn("记忆注入失败（本轮不注入）：{}", e.getMessage());
            return "";
        }
        cc.ginpika.minis.ai.memory.MemoryLiveContext.capture(snapshot);
        String text = render(snapshot, Math.max(120, memoryProps.getBodyMaxChars()));
        String suffix = cc.ginpika.minis.ai.memory.MemoryLiveContext.START + "\n"
                + header(surface, exec) + text + "\n" + cc.ginpika.minis.ai.memory.MemoryLiveContext.END;
        /* RAG 召回留痕：每轮自动注入的记忆召回也记一行（memory 通道、memory_inject）；无命中不记，免淹没 */
        ragRecalls.recordMemoryInject(query == null ? "" : query, suffix,
                surface.name().toLowerCase(), exec.agentOrBuiltin());
        return suffix;
    }

    /** 渲染快照：长期记忆条目（按层标注） + 本会话工作集（L3）——两类分开呈现，模型据此区分「记得」与「在做」 */
    private static String render(MemorySnapshot snapshot, int maxChars) {
        StringBuilder sb = new StringBuilder(snapshot.items().stream()
                .map(it -> MemoryItemRecall.oneLine(it, maxChars)).collect(java.util.stream.Collectors.joining("\n")));
        List<MemoryWorkStore.WorkRow> work = snapshot.work();
        if (work != null && !work.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("【本会话工作集 · L3】\n");
            for (MemoryWorkStore.WorkRow w : work) {
                sb.append(MemorySnapshotBuilder.workLine(w, maxChars)).append('\n');
            }
        }
        for (var p : snapshot.progress()) {
            sb.append('\n').append(cc.ginpika.minis.project.ProjectProgressStore.render(p)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 注入头：按面说明这段记忆的来路，并标注创作身份下的可信度（memory-v6 §8.4 / §11） */
    private static String header(MemorySurface surface, MemoryExec exec) {
        String base = switch (surface) {
            case SUBAGENT -> "【相关记忆】你在执行子任务，以下是派发方作用域 + 本会话内可读的记忆：\n";
            case HEARTBEAT -> "【我的记忆】你的私有经验与绑定项目内可读的记忆：\n";
            default -> "【相关记忆】按这次提问在当前作用域内召回的记忆条目（用户画像 / 项目 / 我的经验 / 本会话 / 共享）：\n";
        };
        if (exec.identityOrInferred() == MemoryIdentity.FICTION) {
            return base + "（注意：本会话是创作/角色扮演上下文；以下「用户画像」是真实用户信息，勿混入剧情设定）\n";
        }
        return base;
    }

    /** 面映射：只有需要注入记忆的三种面参与；其余（圆桌自建 messages）不走这里 */
    private static MemorySurface surfaceOf(ContextRequest.Surface surface) {
        return switch (surface) {
            case CHAT -> MemorySurface.CHAT;
            case SUBAGENT -> MemorySurface.SUBAGENT;
            case HEARTBEAT -> MemorySurface.HEARTBEAT;
        };
    }

    /**
     * 本轮作用域：优先执行入口圈定的 {@link MemoryScopeCtx}（带 turnId / 身份 / 面），
     * 缺省退回 {@link ContextRequest} 的字段（子代理 / 心跳面由各自的 builder 填好作用域）。
     */
    private static MemoryExec execOf(ContextRequest req, MemorySurface surface) {
        MemoryExec ctx = MemoryScopeCtx.get();
        if (ctx != null && eq(ctx.sessionId(), req.sessionId())) {
            return ctx;
        }
        MemoryIdentity identity = req.identity() == null ? MemoryIdentity.REAL : req.identity();
        return new MemoryExec(null, req.agentId(), req.projectId(), req.sessionId(), req.taskId(),
                surface, false, null, identity);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
