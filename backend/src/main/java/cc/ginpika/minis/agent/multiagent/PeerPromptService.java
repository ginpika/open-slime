package cc.ginpika.minis.agent.multiagent;

import cc.ginpika.minis.agent.multiagent.store.MaPeerStore;
import cc.ginpika.minis.agent.multiagent.store.PeerPromptStore;
import cc.ginpika.minis.ai.core.prompt.PromptComposer;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary.PromptContext;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.prompt.block.IdentityBlock;
import cc.ginpika.minis.ai.core.prompt.dto.PromptDtos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 成员提示词：<b>每个智能体独立一套</b>「对话」提示词（对话身份 / 对话增强 / 终端 / 子代理 /
 * 子代理回报摘要），在自己的内部页里管理，与首页对话的全局提示词（提示词页）完全隔离。
 *
 * <p>这套提示词取自 {@link PromptLibrary#CAT_CHAT} 这个大类——正是提示词页「系统级提示词」tab 的全部内容。
 * 迁移之后它在提示词页仍然是首页对话在用的那一份，而圆桌上的每位成员在这里各持一份：
 * <ul>
 *   <li><b>读</b>——{@link #view(String)} 给出这个成员的全量视图（与提示词页同构，前端复用同一套编排 UI）、
 *       {@link #resolve(String, String)} 解析某位当前生效的正文、{@link #system(String)} 按成员自己的
 *       顺序与绑定拼出它在圆桌上说话时的 system；</li>
 *   <li><b>写</b>——版本 / 生效绑定 / 拼装顺序的 CRUD，全部落在 {@code ma_peer_prompt_*} 上。</li>
 * </ul>
 *
 * <p><b>回退链</b>：成员自己绑定的版本 → 全局（提示词页绑定 → 内置）。刚建出来的成员什么都不用配，
 * 于是它和首页对话一样说话；一旦在它的内部页改了，就只影响它自己。
 *
 * <p><b>一处例外：圆桌上下文（{@link PromptLibrary#CTX_ROUNDTABLE}）是全局一份</b>——协作规程对全桌
 * 是同一套，没有「这位成员自己的版本」这回事。它出现在这一页只是为了让人看得见、改得动，
 * 读写都直接落全局（{@link PromptManager}），不经过 {@code ma_peer_prompt_*}。
 */
@Service
public class PeerPromptService {

    /** 自动分叉出来的版本名：成员第一次直接编辑「内置 / 全局」正文时使用 */
    private static final String FORKED_NAME = "自定义版本";

    /** 不随成员分叉的上下文：读写都落全局，全桌共用一份 */
    private static final Set<String> GLOBAL_CONTEXTS = Set.of(PromptLibrary.CTX_ROUNDTABLE);

    /** 上面那些上下文里的提示词位——「谁属于全局」由注册表解出来，不在两处各写一份 key */
    private static final Set<String> GLOBAL_SLOTS = PromptLibrary.contexts().stream()
            .filter(ctx -> GLOBAL_CONTEXTS.contains(ctx.key()))
            .flatMap(ctx -> ctx.parts().stream())
            .collect(Collectors.toUnmodifiableSet());

    private final PeerPromptStore store;
    private final PromptComposer composer;
    private final PromptManager prompts;
    private final MaPeerStore peers;

    public PeerPromptService(PeerPromptStore store, PromptComposer composer, PromptManager prompts,
                             MaPeerStore peers) {
        this.store = store;
        this.composer = composer;
        this.prompts = prompts;
        this.peers = peers;
    }

    // ---------- 读 ----------

    /**
     * 某成员提示词的全量视图：大类（对话 / 圆桌）→ 上下文 → 提示词位（按拼装顺序，
     * 含内置 / 版本 / 当前生效项）+ 运行时素材。结构与提示词页完全一致，前端因此复用同一套编排 UI。
     */
    public PromptDtos.PromptsView view(String peerId) {
        requirePeer(peerId);
        Map<String, String> bindings = new HashMap<>();
        for (PeerPromptStore.BindingRow b : store.bindings(peerId)) {
            bindings.put(b.promptKey(), b.versionId());
        }
        Map<String, List<PromptDtos.VersionView>> versions = new HashMap<>();
        for (PeerPromptStore.VersionRow v : store.versions(peerId)) {
            versions.computeIfAbsent(v.promptKey(), k -> new ArrayList<>())
                    .add(new PromptDtos.VersionView(v.id(), v.name(), v.content(), v.updatedAt()));
        }
        /* 只列这两类：成员各自那份「对话」，与全桌共用的「圆桌」——开发者选项那条流水线不属于智能体 */
        List<PromptDtos.CategoryView> categories = PromptLibrary.categories().stream()
                .filter(c -> PromptLibrary.CAT_CHAT.equals(c.key())
                        || PromptLibrary.CAT_ROUNDTABLE.equals(c.key()))
                .map(c -> new PromptDtos.CategoryView(c.key(), c.name(), c.description(),
                        c.contextKeys().stream()
                                .map(key -> contextViewOf(peerId, key, bindings, versions))
                                .flatMap(Optional::stream)
                                .toList()))
                .toList();
        return new PromptDtos.PromptsView(categories);
    }

    /** 某上下文在这一页的视图：全局一份的（圆桌）取全局那份，其余取这位成员自己那份 */
    private Optional<PromptDtos.ContextView> contextViewOf(
            String peerId, String contextKey, Map<String, String> bindings,
            Map<String, List<PromptDtos.VersionView>> versions) {
        return PromptLibrary.context(contextKey).map(ctx -> isGlobalContext(ctx.key())
                ? prompts.contextView(ctx.key())
                : toContextView(peerId, ctx, bindings, versions));
    }

    /**
     * 某成员某提示词位当前生效的正文：成员自己绑定的版本 → 全局（提示词页绑定 → 内置）。
     * 绑定已失效（版本被删）时同样回退全局，与全局侧的约定一致；圆桌那几个位没有成员版本，
     * 直接取全局生效项。
     */
    public String resolve(String peerId, String slotKey) {
        if (isGlobalSlot(slotKey)) {
            return prompts.resolve(slotKey);
        }
        String versionId = store.binding(peerId, slotKey).orElse(null);
        if (versionId != null && !versionId.isBlank()) {
            Optional<PeerPromptStore.VersionRow> row = store.version(versionId);
            if (row.isPresent() && row.get().peerId().equals(peerId)
                    && row.get().promptKey().equals(slotKey)) {
                return row.get().content();
            }
        }
        return composer.resolve(slotKey);
    }

    /** 某成员在圆桌上说话时的 system：它自己那套「对话」提示词（身份 + 增强 + 终端，按它的顺序） */
    public String system(String peerId) {
        return composer.system(PromptLibrary.CTX_CHAT,
                store.orders(peerId, PromptLibrary.CTX_CHAT), key -> resolve(peerId, key));
    }

    /**
     * 圆桌协作规程（「圆桌」上下文）的正文：全桌共用一份，正文与顺序都取全局。
     *
     * <p>与 {@link #system(String)} 分开成两个上下文，是因为它们的归属不同——人格逐成员各异，
     * 协作机制对全桌恒定。拼在成员 system 的哪一段由 {@code PeerContextBuilder} 决定。
     */
    public String roundtableSystem() {
        return composer.system(PromptLibrary.CTX_ROUNDTABLE, composer::resolve);
    }

    // ---------- 写（版本 / 绑定 / 顺序；视图由调用方重新取） ----------

    public void createVersion(String peerId, String slotKey, PromptDtos.VersionRequest req) {
        requirePeer(peerId);
        requireSlot(slotKey);
        if (isGlobalSlot(slotKey)) {
            prompts.createVersion(slotKey, req);
            return;
        }
        requireName(req);
        store.insertVersion(new PeerPromptStore.VersionRow(UUID.randomUUID().toString(), peerId,
                slotKey, req.name().strip(), req.content() == null ? "" : req.content(), 0));
    }

    public void updateVersion(String peerId, String id, PromptDtos.VersionRequest req) {
        if (isGlobalVersion(id)) {
            prompts.updateVersion(id, req);
            return;
        }
        requireName(req);
        requireOwnedVersion(peerId, id);
        store.updateVersion(id, req.name().strip(), req.content() == null ? "" : req.content());
    }

    public void deleteVersion(String peerId, String id) {
        if (isGlobalVersion(id)) {
            prompts.deleteVersion(id);
            return;
        }
        requireOwnedVersion(peerId, id);
        store.deleteVersion(id);
        /* 版本被删 → 指向它的绑定落回全局 */
        store.clearBindingsOfVersion(id);
    }

    /** 选定某成员某提示词位生效的版本；versionId 为空或 builtin = 回退全局 */
    public void bind(String peerId, String slotKey, PromptDtos.BindRequest req) {
        requirePeer(peerId);
        requireSlot(slotKey);
        if (isGlobalSlot(slotKey)) {
            prompts.bind(slotKey, req);
            return;
        }
        String versionId = req == null ? null : req.versionId();
        String id = versionId == null ? null : versionId.strip();
        if (id == null || id.isEmpty() || PromptComposer.BUILTIN.equals(id)) {
            store.saveBinding(peerId, slotKey, null);
            return;
        }
        PeerPromptStore.VersionRow row = store.version(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提示词版本不存在: " + id));
        if (!row.peerId().equals(peerId) || !row.promptKey().equals(slotKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该版本不属于这位成员的提示词位 " + slotKey);
        }
        store.saveBinding(peerId, slotKey, id);
    }

    /**
     * 保存某成员某提示词位「当前生效的正文」（编辑器直接改正文走这条）：已绑定自己的版本则改那份；
     * 否则为这位成员自动分叉出一份新版本并绑定——全局与内置原文都不受影响。
     */
    public void saveContent(String peerId, String slotKey, PromptDtos.ContentRequest req) {
        requirePeer(peerId);
        requireSlot(slotKey);
        if (isGlobalSlot(slotKey)) {
            prompts.saveContent(slotKey, req);
            return;
        }
        String body = req == null || req.content() == null ? "" : req.content();
        String active = store.binding(peerId, slotKey).orElse(null);
        if (active != null && !active.isBlank()) {
            PeerPromptStore.VersionRow row = store.version(active).orElse(null);
            if (row != null && row.peerId().equals(peerId) && row.promptKey().equals(slotKey)) {
                store.updateVersion(active, row.name(), body);
                return;
            }
        }
        String id = UUID.randomUUID().toString();
        store.insertVersion(new PeerPromptStore.VersionRow(id, peerId, slotKey, FORKED_NAME, body, 0));
        store.saveBinding(peerId, slotKey, id);
    }

    /** 覆盖某上下文内提示词位的拼装顺序（拖拽结果）；未带上的块按注册表顺序补在末尾 */
    public void saveOrder(String peerId, String contextKey, PromptDtos.OrderRequest req) {
        requirePeer(peerId);
        if (isGlobalContext(contextKey)) {
            prompts.saveOrder(contextKey, req);
            return;
        }
        PromptContext ctx = PromptLibrary.context(contextKey).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "上下文不存在: " + contextKey));
        Set<String> known = new LinkedHashSet<>(ctx.parts());
        List<String> order = new ArrayList<>();
        List<String> given = req == null || req.slots() == null ? List.of() : req.slots();
        for (String key : given) {
            if (key != null && known.contains(key) && !order.contains(key)) {
                order.add(key);
            }
        }
        for (String key : known) {
            if (!order.contains(key)) {
                order.add(key);
            }
        }
        store.saveOrder(peerId, contextKey, order);
    }

    /** 成员被移出圆桌时清理它这套提示词（版本 / 绑定 / 顺序） */
    public void deleteAll(String peerId) {
        store.deleteAll(peerId);
    }

    // ---------- 私有 ----------

    /** 该上下文是不是「全局一份」（见 {@link #GLOBAL_CONTEXTS}） */
    private static boolean isGlobalContext(String contextKey) {
        return contextKey != null && GLOBAL_CONTEXTS.contains(contextKey);
    }

    /** 该提示词位是不是「全局一份」——它的版本 / 绑定 / 生效项都不分成员 */
    private static boolean isGlobalSlot(String slotKey) {
        return slotKey != null && GLOBAL_SLOTS.contains(slotKey);
    }

    /** 该版本 id 是不是全局那份——成员侧的改 / 删要据此改路由，不能按「属于某位成员」校验 */
    private boolean isGlobalVersion(String id) {
        return prompts.slotOfVersion(id).filter(PeerPromptService::isGlobalSlot).isPresent();
    }

    private PromptDtos.ContextView toContextView(String peerId, PromptContext ctx,
                                                 Map<String, String> bindings,
                                                 Map<String, List<PromptDtos.VersionView>> versions) {
        List<PromptDtos.SlotView> parts = composer
                .orderedParts(ctx.key(), store.orders(peerId, ctx.key())).stream()
                .map(b -> new PromptDtos.SlotView(b.key(), b.name(), b.description(),
                        composer.builtinOf(b.key()),
                        IdentityBlock.KEY.equals(b.key()) ? "env" : "code",
                        blankToNull(bindings.get(b.key())),
                        versions.getOrDefault(b.key(), List.of())))
                .toList();
        List<PromptDtos.RuntimeView> runtime = ctx.runtime().stream()
                .map(r -> new PromptDtos.RuntimeView(r.key(), r.name(), r.description()))
                .toList();
        return new PromptDtos.ContextView(ctx.key(), ctx.name(), ctx.description(), parts, runtime);
    }

    private void requirePeer(String peerId) {
        if (peerId == null || peerId.isBlank() || peers.peer(peerId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在: " + peerId);
        }
    }

    private void requireSlot(String slotKey) {
        if (!composer.hasBlock(slotKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提示词位不存在: " + slotKey);
        }
    }

    private static void requireName(PromptDtos.VersionRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本名不能为空");
        }
    }

    /** 取版本并确认它属于这位成员（防跨成员改别人的版本） */
    private PeerPromptStore.VersionRow requireOwnedVersion(String peerId, String id) {
        PeerPromptStore.VersionRow row = (id == null ? Optional.<PeerPromptStore.VersionRow>empty()
                : store.version(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "提示词版本不存在: " + id));
        if (!row.peerId().equals(peerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提示词版本不存在: " + id);
        }
        return row;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
