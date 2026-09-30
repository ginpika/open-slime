package cc.ginpika.minis.ai.core.prompt;

import cc.ginpika.minis.ai.core.prompt.PromptLibrary.PromptContext;
import cc.ginpika.minis.ai.core.prompt.block.IdentityBlock;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore.BindingRow;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore.VersionRow;
import cc.ginpika.minis.config.LlmProps;
import cc.ginpika.minis.ai.core.prompt.dto.PromptDtos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 提示词组装者（编排）：提示词子系统的读引擎——解析各块当前生效的正文，并按上下文组装成
 * 最终送进 LLM 的 system。
 *
 * <p>本类持有块注册表（Spring 注入的 {@link PromptBlock}，每块一个类），读
 * {@code PromptStore}（用户版本/绑定/顺序）与 .env（对话身份的内置），完成三件事：
 * <ul>
 *   <li><b>生效正文解析</b>——{@link #resolve(String)} / {@link #resolve(String, String)}
 *       （绑定版本覆盖内置，支持会话级覆盖）；</li>
 *   <li><b>按序组装</b>——{@link #orderedParts(String)}（用户拖拽顺序 → 注册表顺序兜底）、
 *       {@link #chatSystem(String)}（「对话」上下文拼成 system，含会话级身份覆盖）；</li>
 *   <li><b>页面视图</b>——{@link #view()}（大类 → 上下文 → 块 + 运行时素材）。</li>
 * </ul>
 *
 * <p>只读不写：版本/绑定/顺序的写操作在 {@link PromptManager}。对外门面也是
 * {@link PromptManager}——消费方（提示词页 / 各服务）只认 Manager，由它委托本类；
 * 依赖保持 Manager → Composer 单向。
 */
@Component
public class PromptComposer {

    /** 绑定/会话选择里表示「用内置」的保留 id（旧数据里的 "env" 亦按内置处理） */
    public static final String BUILTIN = "builtin";

    private static final String LEGACY_ENV = "env";

    /** 未配置顺序的提示词块排到已配置之后（注册表顺序仅作同段内的稳定排序依据） */
    private static final int UNSORTED_BASE = 1000;

    private final PromptStore store;
    private final LlmProps props;

    /** 块注册表：key → 块（按 key 排序登记，顺序稳定便于诊断） */
    private final Map<String, PromptBlock> blocks = new TreeMap<>();

    public PromptComposer(PromptStore store, LlmProps props, List<PromptBlock> registered) {
        this.store = store;
        this.props = props;
        for (PromptBlock block : registered) {
            if (this.blocks.put(block.key(), block) != null) {
                throw new IllegalStateException("提示词块 key 重复登记: " + block.key());
            }
        }
    }

    // ---------- 生效正文解析 ----------

    /** 某提示词块当前生效的正文：绑定版本 → 该版本内容；未绑定/版本已删 → 内置 */
    public String resolve(String slotKey) {
        String versionId = bindings().get(slotKey);
        if (versionId == null || versionId.isBlank()) {
            return builtin(slotKey);
        }
        return store.version(versionId).map(VersionRow::content)
                .orElseGet(() -> builtin(slotKey));
    }

    /**
     * 带会话级覆盖的解析：versionId 为空 → 走全局（{@link #resolve(String)}）；
     * {@link #BUILTIN}（或旧 id "env"）→ 显式用内置；否则用该版本（不存在/不属于该块时回退全局）。
     */
    public String resolve(String slotKey, String versionId) {
        String id = versionId == null ? null : versionId.strip();
        if (id == null || id.isEmpty()) {
            return resolve(slotKey);
        }
        if (BUILTIN.equals(id) || LEGACY_ENV.equals(id)) {
            return builtin(slotKey);
        }
        return store.version(id)
                .filter(v -> v.promptKey().equals(slotKey))
                .map(VersionRow::content)
                .orElseGet(() -> resolve(slotKey));
    }

    /** 某提示词块的内置正文：对话身份取 .env，其余取块类里写死的那份 */
    private String builtin(String slotKey) {
        if (IdentityBlock.KEY.equals(slotKey)) {
            return props.getSystemPrompt() == null ? "" : props.getSystemPrompt();
        }
        PromptBlock block = blocks.get(slotKey);
        return block == null || block.builtin() == null ? "" : block.builtin();
    }

    /** 该 key 是否已登记提示词块（供 Manager 校验请求里的 slotKey） */
    public boolean hasBlock(String slotKey) {
        return blocks.containsKey(slotKey);
    }

    // ---------- 按序组装 ----------

    /**
     * 对话 system：把「对话」上下文里的提示词块按用户排定的顺序拼起来（空片段自动跳过），
     * 每块用其声明的 XML 标签（{@link PromptBlock#tag()}）单独包裹，块间以空行分隔。
     *
     * @param identityVersionId 会话级身份版本；空 = 用提示词页的全局绑定，{@code "builtin"} = 显式用内置
     */
    public String chatSystem(String identityVersionId) {
        Map<String, Integer> sorts = store.orders(PromptLibrary.CTX_CHAT);
        return system(PromptLibrary.CTX_CHAT, sorts, blockKey -> IdentityBlock.KEY.equals(blockKey)
                ? resolve(blockKey, identityVersionId)
                : resolve(blockKey));
    }

    /**
     * 按给定的顺序与解析器组装某上下文的 system：块按 {@code sorts}（缺省用注册表顺序）排定先后，
     * 每块用其声明的 XML 标签包裹，空片段自动跳过。
     *
     * <p>顺序与「某块当前生效的正文」由调用方给——{@link #chatSystem} 传全局绑定，
     * 多智能体那条流传成员自己的绑定与顺序（见 {@code PeerPromptService}），组装规则只有这一处。
     */
    public String system(String contextKey, Map<String, Integer> sorts,
                         java.util.function.Function<String, String> contentOf) {
        List<Prompt> parts = new ArrayList<>();
        for (PromptBlock block : orderedParts(contextKey, sorts)) {
            parts.add(new Prompt(block.key(), tagged(block, contentOf.apply(block.key()))));
        }
        return Prompt.join(parts.toArray(Prompt[]::new));
    }

    /**
     * 按该上下文已保存的<b>全局</b>顺序组装（顺序与正文都取全局）：给「不随成员分叉」的上下文用——
     * 圆桌那套对全桌恒定，没有成员维度的顺序可谈。
     */
    public String system(String contextKey, java.util.function.Function<String, String> contentOf) {
        return system(contextKey, store.orders(contextKey), contentOf);
    }

    /** 按块声明的标签把正文包成 {@code <tag>正文</tag>}；无标签或正文为空时原样返回 */
    private static String tagged(PromptBlock block, String content) {
        String tag = block.tag();
        if (tag == null || tag.isBlank() || content == null || content.isBlank()) {
            return content;
        }
        String body = content.strip();
        return "<" + tag + ">\n" + body + "\n</" + tag + ">";
    }

    /** 某上下文内提示词块的实际拼装顺序（用户排定 → 注册表顺序兜底） */
    public List<PromptBlock> orderedParts(String contextKey) {
        return orderedParts(contextKey, store.orders(contextKey));
    }

    /**
     * 某上下文内提示词块的实际拼装顺序，顺序由调用方给（缺省用注册表顺序）——
     * 多智能体那条流传成员自己保存的顺序，正式复用本方法。
     */
    public List<PromptBlock> orderedParts(String contextKey, Map<String, Integer> sorts) {
        PromptContext ctx = requireContext(contextKey);
        List<PromptBlock> parts = new ArrayList<>();
        for (String key : ctx.parts()) {
            PromptBlock block = blocks.get(key);
            if (block == null) {
                throw new IllegalStateException("上下文 " + contextKey + " 引用了未登记的提示词块: " + key);
            }
            parts.add(block);
        }
        Map<String, Integer> registryIndex = new HashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            registryIndex.put(parts.get(i).key(), i);
        }
        parts.sort(Comparator.comparingInt(p -> sorts.getOrDefault(p.key(),
                UNSORTED_BASE + registryIndex.getOrDefault(p.key(), 0))));
        return parts;
    }

    /** 某提示词块的内置正文（写死在块类里 / identity 取 .env）；未登记返回空串 */
    public String builtinOf(String slotKey) {
        return builtin(slotKey);
    }

    /** 某提示词块的定义（名称 / 说明 / 标签 / 内置）；未登记返回空 */
    public Optional<PromptBlock> block(String slotKey) {
        return Optional.ofNullable(slotKey == null ? null : blocks.get(slotKey));
    }

    // ---------- 提示词页视图 ----------

    /** 提示词页全量视图：大类 → 上下文 → 提示词块（按拼装顺序，含内置/版本/生效项）+ 运行时素材 */
    public PromptDtos.PromptsView view() {
        Map<String, String> bindings = bindings();
        Map<String, List<PromptDtos.VersionView>> versions = new HashMap<>();
        for (VersionRow v : store.versions()) {
            versions.computeIfAbsent(v.promptKey(), k -> new ArrayList<>())
                    .add(new PromptDtos.VersionView(v.id(), v.name(), v.content(), v.updatedAt()));
        }
        List<PromptDtos.CategoryView> categories = new ArrayList<>();
        for (PromptLibrary.PromptCategory cat : PromptLibrary.categories()) {
            /* 圆桌是局部大类：它只在智能体内部页出现（见 PromptLibrary.CAT_ROUNDTABLE），
               提示词页是首页对话的操作面，摆上这一节只会让人以为改了会影响首页 */
            if (PromptLibrary.CAT_ROUNDTABLE.equals(cat.key())) {
                continue;
            }
            List<PromptDtos.ContextView> contexts = cat.contextKeys().stream()
                    .map(key -> PromptLibrary.context(key).orElse(null))
                    .filter(Objects::nonNull)
                    .map(ctx -> toContextView(ctx, bindings, versions))
                    .toList();
            categories.add(new PromptDtos.CategoryView(cat.key(), cat.name(), cat.description(),
                    contexts));
        }
        return new PromptDtos.PromptsView(categories);
    }

    /**
     * 单个上下文的视图（版本 / 生效项取全局）：供「不随成员分叉」的上下文使用——圆桌是全桌共用的
     * 一套，没有成员维度的版本，智能体内部页直接展示这一份。
     */
    public PromptDtos.ContextView contextView(String contextKey) {
        PromptContext ctx = requireContext(contextKey);
        Map<String, String> bindings = bindings();
        Map<String, List<PromptDtos.VersionView>> versions = new HashMap<>();
        for (VersionRow v : store.versions()) {
            if (ctx.parts().contains(v.promptKey())) {
                versions.computeIfAbsent(v.promptKey(), k -> new ArrayList<>())
                        .add(new PromptDtos.VersionView(v.id(), v.name(), v.content(), v.updatedAt()));
            }
        }
        return toContextView(ctx, bindings, versions);
    }

    // ---------- 私有 ----------

    private PromptDtos.ContextView toContextView(PromptContext ctx, Map<String, String> bindings,
                                                 Map<String, List<PromptDtos.VersionView>> versions) {
        List<PromptDtos.SlotView> parts = orderedParts(ctx.key()).stream()
                .map(b -> toSlotView(b, bindings.get(b.key()),
                        versions.getOrDefault(b.key(), List.of())))
                .toList();
        List<PromptDtos.RuntimeView> runtime = ctx.runtime().stream()
                .map(r -> new PromptDtos.RuntimeView(r.key(), r.name(), r.description()))
                .toList();
        return new PromptDtos.ContextView(ctx.key(), ctx.name(), ctx.description(), parts, runtime);
    }

    private PromptDtos.SlotView toSlotView(PromptBlock block, String activeVersionId,
                                           List<PromptDtos.VersionView> versions) {
        String effective = activeVersionId == null || activeVersionId.isBlank() ? null : activeVersionId;
        return new PromptDtos.SlotView(block.key(), block.name(), block.description(),
                builtin(block.key()),
                IdentityBlock.KEY.equals(block.key()) ? "env" : "code", effective, versions);
    }

    /** 全部绑定（块 key → version_id，允许无行/空值） */
    private Map<String, String> bindings() {
        Map<String, String> map = new HashMap<>();
        for (BindingRow b : store.bindings()) {
            map.put(b.promptKey(), b.versionId());
        }
        return map;
    }

    private static PromptContext requireContext(String contextKey) {
        return PromptLibrary.context(contextKey).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "上下文不存在: " + contextKey));
    }
}
