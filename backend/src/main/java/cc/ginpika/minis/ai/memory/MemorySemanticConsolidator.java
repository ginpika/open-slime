package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.ChatMessage;
import cc.ginpika.minis.ai.memory.store.MemoryCursorStore;
import cc.ginpika.minis.ai.memory.store.MemoryEdgeStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.config.MemoryProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 语义级巩固（memory-v5 §7.2 · P4 · 对标 ChatGPT 的 Dreaming）：在<b>同域</b>内识别「新值取代旧值 / 矛盾」，
 * 把过时条目取代掉——这是确定性去重（{@link MemoryConsolidator}）做不到的一层，需一次 LLM 判断。
 *
 * <p><b>成本与安全</b>：
 * <ul>
 *   <li>默认关（{@link MemoryProps#isSemanticConsolidate()}），开启后按 {@code memory_cursor} 水位**增量**处理，
 *       单轮上限 {@link MemoryProps#getSemanticMaxItems()}，不重复对老条目调模型；</li>
 *   <li><b>限同域</b>（owner × project × agent × domain × scope 全同才互相取代），跨域绝不误伤；</li>
 *   <li>protected（pinned / 正式画像 / open_issue）跳过；失败隔离，异常只留日志；</li>
 *   <li>取代同时失效该条目的知识边（双时态：保留历史）。</li>
 * </ul>
 */
@Component
public class MemorySemanticConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemorySemanticConsolidator.class);

    private static final String SOURCE = "memory-semantic";

    /** 单轮最多处理的「同域分组」数（控 LLM 调用次数） */
    private static final int MAX_GROUPS = 5;

    private static final String SYSTEM = """
            你在做「记忆巩固」。输入是同一作用域下的一组记忆条目（编号 + 正文）。
            请找出其中**互相矛盾或已过时**的条目：新信息取代旧信息。
            只输出 JSON 数组，不要任何其它文字；每条格式：{"old_id":旧编号,"new_id":新编号,"reason":"一句话"}
            规则：
            - 只有当两条确实描述同一件事、且一条更新或更准确时才取代；不相关或互补的不要取代。
            - old_id 是被取代（过时）的，new_id 是保留（更新）的。
            - 没有可取代的就输出 []。
            """;

    private final MemoryProps props;
    private final SettingsService settings;
    private final OpenAiChatClient chat;
    private final ObjectMapper om;
    private final MemoryItemStore items;
    private final MemoryEdgeStore edges;
    private final MemoryCursorStore cursors;

    public MemorySemanticConsolidator(MemoryProps props, SettingsService settings, OpenAiChatClient chat,
                                      ObjectMapper om, MemoryItemStore items, MemoryEdgeStore edges,
                                      MemoryCursorStore cursors) {
        this.props = props;
        this.settings = settings;
        this.chat = chat;
        this.om = om;
        this.items = items;
        this.edges = edges;
        this.cursors = cursors;
    }

    /**
     * 跑一轮增量语义巩固：从游标起取新条目，按同域分组交模型判断取代。
     *
     * @return 被取代的条目数；关闭 / 未配置模型 / 无新条目时返回 0
     */
    public int merge() {
        if (!props.isSemanticConsolidate()) {
            return 0;
        }
        ModelTarget target = settings.chatModel();
        if (!target.configured()) {
            return 0;
        }
        String key = MemoryCursorStore.PREFIX_SEMANTIC + "all";
        long cursor = cursors.get(key);
        List<MemoryItemStore.ItemRow> fresh = items.activeSince(cursor, Math.max(2, props.getSemanticMaxItems()));
        if (fresh.isEmpty()) {
            return 0;
        }
        long maxId = cursor;
        Map<String, List<MemoryItemStore.ItemRow>> groups = new LinkedHashMap<>();
        for (MemoryItemStore.ItemRow it : fresh) {
            maxId = Math.max(maxId, it.id());
            if (isProtected(it)) {
                continue;
            }
            // Repeated historical events are separate occurrences, never newer values of one state.
            var time = cc.ginpika.minis.ai.memory.MemoryTime.decode(it.temporalJson());
            if ("episode".equals(it.scope()) || time == null || !"state".equals(time.kind())) continue;
            groups.computeIfAbsent(domainKey(it), k -> new ArrayList<>()).add(it);
        }
        int superseded = 0;
        int groupsDone = 0;
        for (List<MemoryItemStore.ItemRow> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            if (groupsDone >= MAX_GROUPS) {
                /* 本轮预算用完：不推进游标到 maxId，下轮继续处理剩下的（游标只到本轮真正处理的最后一条） */
                break;
            }
            try {
                superseded += mergeGroup(target, group);
            } catch (RuntimeException e) {
                log.warn("语义巩固单组失败（跳过）：{}", e.getMessage());
            }
            groupsDone++;
        }
        cursors.set(key, maxId);
        if (superseded > 0) {
            log.info("语义巩固：取代 {} 条过时记忆", superseded);
        }
        return superseded;
    }

    /** 单组：一次 LLM 往返 → 取代对 */
    private int mergeGroup(ModelTarget target, List<MemoryItemStore.ItemRow> group) {
        StringBuilder sb = new StringBuilder("【同一作用域的记忆条目】\n");
        for (MemoryItemStore.ItemRow it : group) {
            sb.append('(').append(it.id()).append(") ").append(cc.ginpika.minis.ai.memory.MemoryTime.label(it.temporalJson())).append(" ")
                    .append(it.body() == null ? "" : it.body().replaceAll("\\s+", " ")).append('\n');
        }
        String content = chat.chat(target,
                List.of(new ChatMessage("system", SYSTEM), new ChatMessage("user", sb.toString())),
                SOURCE).content();
        List<long[]> pairs = parsePairs(content);
        int n = 0;
        for (long[] p : pairs) {
            long oldId = p[0];
            long newId = p[1];
            if (oldId == newId || !contains(group, oldId) || !contains(group, newId)) {
                continue;
            }
            var oldTime=cc.ginpika.minis.ai.memory.MemoryTime.decode(group.stream().filter(i->i.id()==oldId).findFirst().orElseThrow().temporalJson());
            var newTime=cc.ginpika.minis.ai.memory.MemoryTime.decode(group.stream().filter(i->i.id()==newId).findFirst().orElseThrow().temporalJson());
            if(oldTime==null || newTime==null || oldTime.start()==null || newTime.start()==null || newTime.start()<oldTime.start()) continue;
            items.supersede(oldId, newId);
            edges.invalidateBySourceItem(oldId);
            n++;
        }
        return n;
    }

    private static boolean contains(List<MemoryItemStore.ItemRow> group, long id) {
        return group.stream().anyMatch(it -> it.id() == id);
    }

    /** 解析模型输出的取代对数组；容忍围栏与噪声，异常返回空 */
    private List<long[]> parsePairs(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String s = content.replace("```json", "").replace("```", "").trim();
        int a = s.indexOf('[');
        int b = s.lastIndexOf(']');
        if (a < 0 || b <= a) {
            return List.of();
        }
        try {
            JsonNode arr = om.readTree(s.substring(a, b + 1));
            if (!arr.isArray()) {
                return List.of();
            }
            List<long[]> out = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n.path("old_id").isNumber() && n.path("new_id").isNumber()) {
                    out.add(new long[]{n.path("old_id").asLong(), n.path("new_id").asLong()});
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("语义巩固输出解析失败（跳过）：{}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isProtected(MemoryItemStore.ItemRow it) {
        return "L1".equals(it.layer()) || "L4".equals(it.layer()) || "long".equals(it.retention())
                || MemoryVocabulary.isProtected(it.owner(), it.scope(), it.pinned(), it.retention());
    }

    private static String domainKey(MemoryItemStore.ItemRow it) {
        return MemoryVocabulary.retentionKey(it) + "|" + nz(it.scope()) + "|" + nz(it.identity());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
