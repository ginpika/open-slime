package cc.ginpika.minis.agent.member;

import cc.ginpika.minis.agent.member.AgentStore;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableSessionStore;
import cc.ginpika.minis.ai.terminal.store.TerminalStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 名册门面：多智能体成员是谁、各自以什么身份说话、各自能碰什么。
 *
 * <p><b>身份只有一处</b>：每位成员自己那套「对话」提示词存在 {@code agent_prompt_*}，
 * 由 {@link AgentPromptService} 解析（成员自己的绑定 → 全局 → 内置），在成员内部页里维护——
 * 于是成员的人设、增强、终端各归各的，与首页对话的全局提示词互不干扰。
 *
 * <p><b>能力也逐成员配</b>：额外可调的工具（{@link #declaredTools}）与可访问的工作空间
 * （{@link #declaredTerminals}，即 {@code term_target.id} 名单）都挂在成员自己那一行上；
 * 绑定了工作空间才会授予 {@code run_command} / {@code list_terminals}，且只在绑定的目标上可用
 * （见 {@code RoundtableChatService}）。
 *
 * <p>名册由人配置（增删改都在这里），大模型只读——它没有「立一个成员」的工具：
 * 圆桌上坐着的人是产品配置，不是模型临时攒的编制。
 */
@Component
public class AgentDirectory {

    private static final Logger log = LoggerFactory.getLogger(AgentDirectory.class);

    /**
     * 内置 Agent 的固定 id。
     *
     * <p>它由 {@code AgentSeeder} 启动播种、<b>不可删除</b>；同时是首页对话的应答者与心跳的调度者——
     * 即「单 Agent 架构」里那个靠人格提示词切换模拟出来的单 Agent，如今被落成一个真正的成员。
     * 它的提示词就是全局提示词（见 {@link AgentPromptService}），因此「改内置 Agent 的人设」= 改首页对话的人设。
     */
    public static final String BUILTIN_ID = "builtin";

    /** 展示名长度上限（也是被 @ 时的名字） */
    private static final int NAME_MAX = 24;

    /** 头像上限：base64 data URL 文本长度。前端会先压到 256px 小图，这里只兜住越界的大图 */
    private static final int AVATAR_MAX_CHARS = 400_000;

    /** 头像允许的 data URL 前缀（只收图片，拒收任意 data: 与外部 URL） */
    private static final String AVATAR_PREFIX = "data:image/";

    private final AgentStore store;
    private final RoundtableSessionStore sessions;
    private final AgentPromptService peerPrompts;
    /** 绑定工作空间时按 id 校验目标确实存在（删除的目标会自然失效，见 {@link #declaredTerminals}） */
    private final TerminalStore terminals;
    private final ObjectMapper om;

    public AgentDirectory(AgentStore store, RoundtableSessionStore sessions, AgentPromptService peerPrompts,
                         TerminalStore terminals, ObjectMapper om) {
        this.store = store;
        this.sessions = sessions;
        this.peerPrompts = peerPrompts;
        this.terminals = terminals;
        this.om = om;
    }

    // ---------- 读 ----------

    /** 全体名册（成员页维护的那一份） */
    public List<AgentRow> peers() {
        return store.peers();
    }

    /** 内置 Agent（默认存在；播种见 {@code AgentSeeder}）；缺失时返回空 */
    public Optional<AgentRow> builtin() {
        return store.peer(BUILTIN_ID);
    }

    /** 这个 id 是不是内置 Agent（不可删除） */
    public static boolean isBuiltin(String id) {
        return BUILTIN_ID.equals(id);
    }

    /**
     * 某会话在座的人：建会话时钉住的那几位。
     *
     * <p>钉住的 id 里已被删掉的成员自动跳过，顺序沿用名册序（谁先说话由名册决定，与勾选顺序无关）；
     * 没钉过时 = 全体名册——首页「和所有智能体对话」直接开说、以及加这一列之前的旧会话都是这种。
     */
    public List<AgentRow> peers(String sessionId) {
        List<String> ids = memberIds(sessionId);
        if (ids.isEmpty()) {
            return store.peers();
        }
        Set<String> want = new LinkedHashSet<>(ids);
        return store.peers().stream().filter(p -> want.contains(p.id())).toList();
    }

    /**
     * 勾选的成员落库前过一遍：去重、丢掉不存在的成员、按名册序排列。
     * 结果为空（没选 / 全都不存在）时返回 null —— 那就是「全体名册」。
     */
    public String membersJson(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        Set<String> want = new LinkedHashSet<>(ids);
        List<String> ordered = store.peers().stream().map(AgentRow::id).filter(want::contains).toList();
        if (ordered.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(ordered);
        } catch (Exception e) {
            log.warn("会话成员清单序列化失败（按全体名册处理）：{}", e.getMessage());
            return null;
        }
    }

    public Optional<AgentRow> peer(String id) {
        return id == null ? Optional.empty() : store.peer(id);
    }

    /** 按展示名查成员（解析发言里的 @名字） */
    public Optional<AgentRow> byName(String name) {
        return name == null || name.isBlank() ? Optional.empty() : store.peerByName(name.strip());
    }

    /** 成员展示名（找不到成员时退化成 id，让历史发言仍可读） */
    public String nameOf(String peerId) {
        return peer(peerId).map(AgentRow::name).orElse(peerId == null ? "" : peerId);
    }

    /** 成员额外声明的工具名（JSON 数组文本解析；坏数据退化为空清单） */
    public List<String> declaredTools(AgentRow peer) {
        return readNames(peer == null ? null : peer.tools(), peer, "工具声明");
    }

    /**
     * 成员绑定的工作空间（{@code term_target.id} 名单，JSON 数组文本解析）。
     *
     * <p>已从工作空间页删掉的目标在这里自动失效：绑定串本身仍留着，但执行侧按 id 取不到目标、
     * 也不会出现在它看到的清单里——于是删目标不会留下一个「能调但调不动」的假象。
     */
    public List<String> declaredTerminals(AgentRow peer) {
        return readNames(peer == null ? null : peer.terminals(), peer, "工作空间绑定");
    }

    /** 成员声明的名字清单：JSON 数组文本解析（去重保序；空/坏数据退化为空清单） */
    private List<String> readNames(String json, AgentRow peer, String what) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> names = om.readValue(json, new TypeReference<List<String>>() {
            });
            return new ArrayList<>(new LinkedHashSet<>(names));
        } catch (Exception e) {
            log.warn("成员「{}」的{}解析失败（按未声明处理）：{}",
                    peer == null ? "?" : peer.name(), what, e.getMessage());
            return List.of();
        }
    }

    // ---------- 写（人配置名册；大模型只能读） ----------

    /** 新建成员：只有名字是必需的，其余留空即跟随默认（身份跟随提示词页绑定、只读检索工具、无工作空间） */
    public AgentRow create(String name, List<String> tools, List<String> terminals,
                          String modelId, String note, String avatar, String role, String roleColor) {
        String clean = requireName(name);
        List<AgentRow> all = store.peers();
        if (all.stream().anyMatch(p -> p.name().equals(clean))) {
            throw new IllegalArgumentException("已有同名成员「" + clean + "」：换个名字（它会用在 @ 里）");
        }
        AgentRow row = new AgentRow(UUID.randomUUID().toString(), clean, false,
                writeJsonList(tools), writeJsonList(requireTerminals(terminals)),
                blankToNull(modelId), store.maxSort() + 1, blankToNull(note),
                requireAvatar(avatar), null, System.currentTimeMillis(), System.currentTimeMillis(),
                blankToNull(role), blankToNull(roleColor));
        store.insert(row);
        return row;
    }

    /** 改写成员：只动传入的字段（null = 不改；avatar 传空串 = 移除头像） */
    public AgentRow update(String id, String name, List<String> tools,
                          List<String> terminals, String modelId, Integer sort, String note,
                          String avatar, String role, String roleColor) {
        AgentRow current = store.peer(id)
                .orElseThrow(() -> new IllegalArgumentException("成员不存在: " + id));
        String nextName = name == null ? current.name() : requireName(name);
        if (!nextName.equals(current.name())) {
            store.peerByName(nextName).ifPresent(other -> {
                throw new IllegalArgumentException("已有同名成员「" + nextName + "」：换个名字");
            });
        }
        AgentRow row = new AgentRow(current.id(), nextName, current.builtin(),
                tools == null ? current.tools() : writeJsonList(tools),
                terminals == null ? current.terminals()
                        : writeJsonList(requireTerminals(terminals)),
                modelId == null ? current.modelId() : blankToNull(modelId),
                sort == null ? current.sort() : sort,
                note == null ? current.note() : blankToNull(note),
                avatar == null ? current.avatar() : requireAvatar(avatar),
                current.anchorId(), current.createdAt(), System.currentTimeMillis(),
                role == null ? current.role() : blankToNull(role),
                roleColor == null ? current.roleColor() : blankToNull(roleColor));
        store.update(row);
        return row;
    }

    /** 移出圆桌：连它自己那套「对话」提示词（版本 / 绑定 / 顺序）一并清掉；内置 Agent 不可删除 */
    public int delete(String id) {
        if (isBuiltin(id)) {
            throw new IllegalArgumentException("内置 Agent 不可删除（它承载首页对话与心跳调度）");
        }
        peerPrompts.deleteAll(id);
        return store.delete(id);
    }

    /** 成员 id → 展示名（前端把发言者渲染成人名用；一次性取全，避免逐条查库） */
    public java.util.Map<String, String> nameMap() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (AgentRow p : store.peers()) {
            map.put(p.id(), p.name());
        }
        return map;
    }

    // ---------- 私有 ----------

    /** 会话钉住的成员 id（JSON 数组）；没钉过 / 解析失败 = 空表（按「全体名册」处理） */
    private List<String> memberIds(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        String raw = sessions.findById(sessionId).map(RoundtableSessionStore.SessionRow::memberIds)
                .orElse(null);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("会话「{}」的成员清单解析失败（按全体名册处理）：{}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("成员名不能为空（它会用在 @ 里）");
        }
        if (clean.length() > NAME_MAX) {
            throw new IllegalArgumentException("成员名过长（最多 " + NAME_MAX + " 字）");
        }
        if (clean.startsWith("@")) {
            throw new IllegalArgumentException("成员名不要带 @（@ 是引用时的前缀）");
        }
        return clean;
    }

    /** 头像校验：空/空白 = 无头像；只收 data:image/ 前缀的小图，防止把任意串或外部 URL 存进来 */
    private static String requireAvatar(String avatar) {
        String clean = blankToNull(avatar);
        if (clean == null) {
            return null;
        }
        if (!clean.startsWith(AVATAR_PREFIX)) {
            throw new IllegalArgumentException("头像格式不对：只接受 data:image/ 开头的图片数据");
        }
        if (clean.length() > AVATAR_MAX_CHARS) {
            throw new IllegalArgumentException("头像过大（请用小图，最大约 "
                    + (AVATAR_MAX_CHARS / 1024) + "KB）");
        }
        return clean;
    }

    /**
     * 工作空间绑定校验：逐个确认 {@code term_target} 确实存在。
     *
     * <p>拼错或已在工作空间页删掉的 id 不该静默存进来——那样页面上显示「已绑定」，
     * 而模型那边其实什么也拿不到。空/空白与重复项就地丢掉（绑定是集合语义）。
     */
    private List<String> requireTerminals(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : ids) {
            String id = raw == null ? "" : raw.strip();
            if (id.isEmpty() || out.contains(id)) {
                continue;
            }
            if (terminals.targetById(id).isEmpty()) {
                throw new IllegalArgumentException("工作空间不存在（可能已在工作空间页删除）：" + id);
            }
            out.add(id);
        }
        return out;
    }

    /** 名字清单 → JSON 数组文本；空清单落 null（让「没声明」与「声明为空」在库里是同一种状态） */
    private String writeJsonList(List<String> names) {
        if (names == null) {
            return null;
        }
        List<String> clean = names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(clean);
        } catch (Exception e) {
            log.warn("成员声明序列化失败（按未声明处理）：{}", e.getMessage());
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
