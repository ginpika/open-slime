package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.memory.store.MemoryEdgeStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.config.MemoryProps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 记忆巩固器（STEP M4 · 对标 ChatGPT 的后台合成 Dreaming）：后台周期性地把零散条目收拾成「记忆状态」。
 *
 * <p><b>调度骨架</b>照抄 {@code HeartbeatService}，但**在本包内自建**——分层测试钉死
 * {@code ai.memory} 不得依赖 {@code agent}，故不 import 那边的调度器，只沿用它的形状：
 * 单线程扫描器按固定周期醒来、预筛省空转（关时直接跳过）、真正的活在虚拟线程上跑。
 *
 * <p>当前动作（确定性、零 LLM 成本，可重复执行）：
 * <ul>
 *   <li><b>去重</b>——同一 (scope, 正文) 的重复条目，保留最新的，其余标记被取代；</li>
 *   <li><b>裁剪</b>——情景层（episode）超出 {@link MemoryProps#getEpisodeMax()} 的最旧条目删除。</li>
 * </ul>
 *
 * <p>语义级合并（新旧信息冲突时「新值取代旧值」）需一次 LLM 判断，作为后续增强——本类预留位置，
 * 不在此处引入 LLM 依赖，保证巩固恒定廉价、可离线运行。
 */
@Component
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    /** 单次去重扫描的条目上限（先取「仍有效」的，避免全表） */
    private static final int SCAN_LIMIT = 2000;

    private final MemoryProps props;
    private final MemoryItemStore items;
    /** 知识边（双时态）：条目被取代时一并失效其支撑边；测试路径可为 null */
    private final MemoryEdgeStore edges;
    /** 语义级巩固（P4）：可选，测试路径可为 null */
    private final MemorySemanticConsolidator semantic;

    /** Spring 装配：带知识边与语义巩固（memory-v5 P4） */
    @Autowired
    public MemoryConsolidator(MemoryProps props, MemoryItemStore items, MemoryEdgeStore edges,
                              MemorySemanticConsolidator semantic) {
        this.props = props;
        this.items = items;
        this.edges = edges;
        this.semantic = semantic;
    }

    /** 兼容构造（测试 / 仅确定性巩固）：无语义层、无知识边 */
    public MemoryConsolidator(MemoryProps props, MemoryItemStore items) {
        this(props, items, null, null);
    }

    /** 巩固扫描器（单线程：按固定周期醒来，判是否需巩固；真正的活丢给虚拟线程） */
    private ScheduledExecutorService scanner;
    /** 巩固执行的虚拟线程池 */
    private final Executor work = Executors.newVirtualThreadPerTaskExecutor();

    @PostConstruct
    void start() {
        long interval = Math.max(60_000L, props.getConsolidateIntervalMs());
        scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-consolidate");
            t.setDaemon(true);
            return t;
        });
        scanner.scheduleWithFixedDelay(this::scan, interval, interval, TimeUnit.MILLISECONDS);
        log.info("记忆巩固调度已就绪：intervalMs={}（总开关 enabled={}）", interval, props.isEnabled());
    }

    @PreDestroy
    void stop() {
        if (scanner != null) {
            scanner.shutdownNow();
        }
    }

    /** 一次扫描：关着就跳过（省空转），开着丢给虚拟线程跑一轮巩固 */
    private void scan() {
        if (!props.isEnabled()) {
            return;
        }
        work.execute(() -> {
            try {
                consolidate();
            } catch (RuntimeException e) {
                log.warn("记忆巩固异常（不影响主流程）：{}", e.getMessage());
            }
        });
    }

    /**
     * 跑一轮巩固（页面「立即整理」也调它）：返回本轮的整理条数（去重 + 裁剪）。
     * 全程只动条目与向量投影，不抛错给调用方之外（上层已 try）。
     */
    public int consolidate() {
        int deduped = dedupe();
        int trimmed = trimEpisodes();
        /* 语义级巩固（P4，默认关）：同域内「新值取代旧值 / 矛盾合并」，需一次 LLM；关时零成本跳过 */
        int merged = semantic == null ? 0 : semantic.merge();
        if (deduped > 0 || trimmed > 0 || merged > 0) {
            log.info("记忆巩固：去重 {} 条、裁剪情景 {} 条、语义取代 {} 条", deduped, trimmed, merged);
        }
        return deduped + trimmed + merged;
    }

    /** 去重：同一 (归属, scope, 正文) 的重复条目，保留最新（id 最大），其余标记被取代；protected 跳过 */
    private int dedupe() {
        List<MemoryItemStore.ItemRow> active = items.active(SCAN_LIMIT);
        Map<String, Long> keeper = new HashMap<>();
        int n = 0;
        /* active 按 id DESC（新在前）：先遇到的即最新，后续重复一律被它取代 */
        for (MemoryItemStore.ItemRow it : active) {
            /* protected（pinned / 正式画像 / 未完成事项）不参与去重——不因「重复」被动取代（memory-v5 §8.4） */
            if (isProtected(it)) {
                continue;
            }
            String key = domainKey(it) + "\u0000" + (it.body() == null ? "" : it.body().trim());
            Long keepId = keeper.get(key);
            if (keepId == null) {
                keeper.put(key, it.id());
                continue;
            }
            items.supersede(it.id(), keepId);
            if (edges != null) {
                edges.invalidateBySourceItem(it.id());
            }
            n++;
        }
        return n;
    }

    /** 同域判据：owner / project_id / agent_id / domain / scope 全同才算同域（跨域绝不互相取代） */
    private static String domainKey(MemoryItemStore.ItemRow it) {
        return MemoryVocabulary.retentionKey(it) + "|" + nz(it.scope());
    }

    /** protected：pinned / 正式用户画像 / 未完成事项 / 全局规则 —— 淘汰、去重、巩固一律跳过（memory-v6 I10） */
    private static boolean isProtected(MemoryItemStore.ItemRow it) {
        return "L1".equals(it.layer()) || "L4".equals(it.layer()) || "long".equals(it.retention())
                || MemoryVocabulary.isProtected(it.owner(), it.scope(), it.pinned(), it.retention());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 裁剪：情景层超出上限的最旧条目**归档**（不物理删除——可回查），protected 跳过 */
    private int trimEpisodes() {
        int max = Math.max(1, props.getEpisodeMax());
        List<MemoryItemStore.ItemRow> episodes =
                items.episodesForRetention();
        if (episodes.size() <= max) {
            return 0;
        }
        int n = 0;
        Map<String, Integer> kept = new HashMap<>();
        /* episodes 按 id DESC（新在前）：保留前 max 条非保护条目，其余归档 */
        for (MemoryItemStore.ItemRow it : episodes) {
            if (isProtected(it) || "long".equals(it.retention())) { continue; }
            String group = MemoryVocabulary.retentionKey(it);
            int count = kept.merge(group, 1, Integer::sum);
            if (count <= max) { continue; }
            items.setStatus(it.id(), MemoryVocabulary.STATUS_ARCHIVED);
            if (edges != null) {
                edges.invalidateBySourceItem(it.id());
            }
            n++;
        }
        return n;
    }
}
