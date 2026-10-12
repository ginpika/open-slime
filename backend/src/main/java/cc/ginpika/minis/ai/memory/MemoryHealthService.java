package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.memory.store.MemoryCandidateStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 记忆体检（memory-v6 §15.4）：把设计大纲 §七 的**三病**变成可观测指标——污染 / 挤占 / 漂移。
 *
 * <p>为什么需要它：这三病都是「慢慢长出来」的——单轮看不出问题，等到模型开始混淆现实与剧情、
 * 或某个项目把预算吃满，已经很难回溯。体检把它们变成随时可查的几张数字与明细。
 *
 * <p>三项判据（全部来自真实数据，不做估算）：
 * <ul>
 *   <li><b>污染</b>：正式画像里混入非现实来源（{@code owner=USER AND identity≠REAL}，应为 0）；
 *       fiction 来源的画像候选（拒收前被拦下的痕迹）；已被拒绝的候选数；</li>
 *   <li><b>挤占</b>：各 domain 的条目数与正文字数占比——单域超过阈值（默认 60%）即告警；</li>
 *   <li><b>漂移</b>：被取代（superseded）的条目总数与最近明细——长期事实被临时决策覆盖的痕迹。</li>
 * </ul>
 */
@Service
public class MemoryHealthService {

    /** 单域占比告警阈值（0..1） */
    private static final double CROWDING_ALERT_SHARE = 0.60d;

    /** 挤占明细最多看 8 个域；漂移明细最多 20 条 */
    private static final int CROWDING_TOP = 8;
    private static final int DRIFT_RECENT = 20;

    private final MemoryItemStore items;
    private final MemoryWorkStore works;
    private final MemoryCandidateStore candidates;

    public MemoryHealthService(MemoryItemStore items, MemoryWorkStore works,
                               MemoryCandidateStore candidates) {
        this.items = items;
        this.works = works;
        this.candidates = candidates;
    }

    /** 一份体检报告 */
    public record Report(long generatedAt, String grade, Pollution pollution, Crowding crowding,
                         Drift drift, int workingOpen) {
    }

    /** 污染：画像被虚构内容污染的痕迹 */
    public record Pollution(int fictionInProfile, int blockedFictionCandidates, int rejectedCandidates,
                            List<String> alerts) {
    }

    /** 挤占：容量被单一领域吞掉 */
    public record Crowding(List<DomainShare> domains, List<String> alerts) {
    }

    /** 一个域的占用（share = 字数占比 0..1） */
    public record DomainShare(String key, int count, long chars, double share) {
    }

    /** 漂移：长期事实被取代 */
    public record Drift(int superseded, List<Superseded> recent, List<String> alerts) {
    }

    /** 一条被取代的条目 */
    public record Superseded(long itemId, String scope, String title, String body, long supersededBy,
                            Long invalidAt) {
    }

    public Report report() {
        List<MemoryItemStore.ItemRow> all = items.listAll();

        /* ---------- 污染 ---------- */
        int fictionInProfile = 0;
        for (MemoryItemStore.ItemRow it : all) {
            if (MemoryVocabulary.OWNER_USER.equals(it.owner()) && !isReal(it.identity())) {
                fictionInProfile++;
            }
        }
        int blocked = candidates.countBySourceKind(MemoryVocabulary.SRC_FICTION);
        int rejected = candidates.countByStatus("rejected");
        List<String> pollutionAlerts = new ArrayList<>();
        if (fictionInProfile > 0) {
            pollutionAlerts.add("正式画像中有 " + fictionInProfile + " 条来源非现实（虚构/推断）——应立即撤销");
        }
        if (blocked > 0) {
            pollutionAlerts.add("有 " + blocked + " 条画像候选来自创作类项目（已被闸门拦下，仅作审计）");
        }
        if (rejected > 0) {
            pollutionAlerts.add("用户/流程已拒绝 " + rejected + " 条画像候选（同指纹不再自动重建）");
        }

        /* ---------- 挤占 ---------- */
        Map<String, int[]> counter = new LinkedHashMap<>();
        Map<String, Long> chars = new LinkedHashMap<>();
        long totalChars = 0;
        for (MemoryItemStore.ItemRow it : all) {
            if (!MemoryVocabulary.STATUS_ACTIVE.equals(it.status())) {
                continue;
            }
            String key = it.domain() == null ? "AGENT" : it.domain();
            counter.computeIfAbsent(key, k -> new int[1])[0]++;
            long len = (it.title() == null ? 0 : it.title().length())
                    + (it.body() == null ? 0 : it.body().length());
            chars.merge(key, len, Long::sum);
            totalChars += len;
        }
        List<DomainShare> shares = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counter.entrySet()) {
            long c = chars.getOrDefault(e.getKey(), 0L);
            shares.add(new DomainShare(e.getKey(), e.getValue()[0], c,
                    totalChars == 0 ? 0d : (double) c / totalChars));
        }
        shares.sort(Comparator.comparingLong(DomainShare::chars).reversed());
        List<String> crowdingAlerts = new ArrayList<>();
        for (DomainShare s : shares) {
            if (s.share() >= CROWDING_ALERT_SHARE && s.count() >= 10) {
                crowdingAlerts.add("「" + s.key() + "」占了 " + Math.round(s.share() * 100)
                        + "% 的记忆容量（" + s.count() + " 条）——其他 Agent 的重要信息可能被挤出");
            }
        }

        /* ---------- 漂移 ---------- */
        List<Superseded> recent = new ArrayList<>();
        int superseded = 0;
        for (MemoryItemStore.ItemRow it : all) {
            if (it.supersededBy() == null && !MemoryVocabulary.STATUS_SUPERSEDED.equals(it.status())) {
                continue;
            }
            superseded++;
            if (recent.size() < DRIFT_RECENT) {
                recent.add(new Superseded(it.id(), it.scope(), it.title(), clip(it.body()), 
                        it.supersededBy() == null ? 0L : it.supersededBy(), it.invalidAt()));
            }
        }
        recent.sort(Comparator.comparingLong((Superseded s) ->
                s.invalidAt() == null ? 0L : s.invalidAt()).reversed());
        List<String> driftAlerts = new ArrayList<>();
        if (superseded > 0) {
            driftAlerts.add("已有 " + superseded + " 条记忆被新版本取代（旧版保留、可点查——确认这是期望的演化）");
        }

        String grade = "ok";
        if (!pollutionAlerts.isEmpty()) {
            grade = "risk";
        } else if (!crowdingAlerts.isEmpty() || !driftAlerts.isEmpty()) {
            grade = "warn";
        }
        return new Report(System.currentTimeMillis(), grade, new Pollution(fictionInProfile, blocked,
                rejected, pollutionAlerts), new Crowding(shares.stream().limit(CROWDING_TOP).toList(),
                crowdingAlerts), new Drift(superseded, recent, driftAlerts),
                works.countOpen(System.currentTimeMillis()));
    }

    private static String clip(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 120 ? body : body.substring(0, 120) + "…";
    }

    /** identity 是否算「现实来源」（空值视为未知 → 不算现实，与 I11 的保守取向一致） */
    private static boolean isReal(String identity) {
        return "REAL".equalsIgnoreCase(identity == null ? "" : identity.trim());
    }
}
