package cc.ginpika.minis.agent.subconscious;

import cc.ginpika.minis.agent.subconscious.store.PlanStore;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 心跳默认正文的幂等播种（启动时执行一次），外加一次性的旧值迁移。
 *
 * <p>任务计划不预置：出厂就是一张空表，全部由大模型自己立（{@code create_plan}）。
 *
 * <p>必须晚于 {@link cc.ginpika.minis.agent.subconscious.store.SubconsciousMigrator}：旧库的表要等改完名、
 * 补出 role/content/tools 后才能被读出来。
 */
@Component
@DependsOn("subconsciousMigrator")
public class SubconsciousSeeder {

    private static final Logger log = LoggerFactory.getLogger(SubconsciousSeeder.class);

    /** 旧的四个渠道（store/memory/chat/user）：检出即说明这行数据是重构前留下的 */
    private static final Pattern LEGACY_CHANNELS =
            Pattern.compile("\"(?:store|memory|chat|user)\"");

    private final PlanStore plans;

    public SubconsciousSeeder(PlanStore plans) {
        this.plans = plans;
        migrateLegacy();
        migrateHeartbeatAnchors();
        seedHeartbeat();
    }

    /**
     * 心跳主会话的默认正文只播一次：这一行已在库里（用户看过或改过）就不再覆盖。
     * 心跳没有它就没有人格，也就没法调度——所以默认正文必须存在。
     */
    private void seedHeartbeat() {
        if (plans.heartbeat().isPresent()) {
            return;
        }
        plans.saveHeartbeat(new PlanStore.HeartbeatRow(SubconsciousPrompts.HEARTBEAT,
                SubconsciousPrompts.heartbeatInjectors(), null));
        log.info("潜意识：已播种心跳主会话的默认正文（总线，可在 heartbeat 页修改）");
    }

    /**
     * 一次性补挂：锚点概念上线前已播种的心跳正文，在末尾追加「锚点与用户画像」小节。
     *
     * <p>只追加、不重写——用户在 heartbeat 页改过的正文其余部分原样保留；
     * 幂等条件 = 正文已含「锚点」字样即跳过（新内置正文自带小节，不会二次追加）。
     */
    private void migrateHeartbeatAnchors() {
        plans.heartbeat().ifPresent(h -> {
            if (h.content() == null || h.content().contains("锚点")) {
                return;
            }
            String next = h.content().stripTrailing() + "\n\n"
                    + SubconsciousPrompts.HEARTBEAT_ANCHORS.stripTrailing() + "\n";
            plans.saveHeartbeat(new PlanStore.HeartbeatRow(next, h.injectors(), h.modelId()));
            log.info("潜意识：心跳正文已补挂「锚点与用户画像」小节");
        });
    }

    /**
     * 一次性迁移：把重构前的「四渠道 + 来讯插槽」收敛为「silent/thread + threads 插槽」。
     *
     * <p>渠道按原意折算：声明过 {@code user}（要主动对人说话）→ {@code thread}，其余 → {@code silent}。
     * 正文只在检出旧插槽 {@code {{inbox}}} 时才做插槽名替换，不会整段冲掉用户写的内容。
     */
    private void migrateLegacy() {
        int count = 0;
        for (PlanStore.PlanRow p : plans.plans()) {
            boolean legacyChannels = p.channels() != null
                    && LEGACY_CHANNELS.matcher(p.channels()).find();
            boolean legacySlot = p.content() != null && p.content().contains("{{inbox}}");
            if (!legacyChannels && !legacySlot) {
                continue;
            }
            String channels = !legacyChannels ? p.channels()
                    : p.channels().contains("\"user\"")
                            ? "[\"" + SubconsciousService.CH_THREAD + "\"]"
                            : "[\"" + SubconsciousService.CH_SILENT + "\"]";
            String content = p.content() == null ? null
                    : p.content().replace("{{inbox}}", "{{threads}}");
            String injectors = p.injectors() == null ? null
                    : p.injectors().replace("\"injector\":\"inbox\"",
                            "\"injector\":\"threads\"");
            plans.updatePlan(new PlanStore.PlanRow(p.id(), p.name(), p.role(), content,
                    p.tools(), injectors, channels, p.intervalMs(), p.input(),
                    p.modelId(), p.sort(), p.lastBeatAt()));
            count++;
        }
        if (count > 0) {
            log.info("潜意识：旧渠道/插槽已迁移（任务计划 {} 份）", count);
        }
    }
}
