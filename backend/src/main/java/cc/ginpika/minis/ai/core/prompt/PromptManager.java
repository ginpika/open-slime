package cc.ginpika.minis.ai.core.prompt;

import cc.ginpika.minis.ai.core.prompt.PromptLibrary.PromptContext;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore.BindingRow;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore.VersionRow;
import cc.ginpika.minis.ai.core.prompt.dto.PromptDtos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 提示词管理器：提示词子系统对外的唯一门面。
 *
 * <p><b>读</b>一律委托 {@link PromptComposer}——解析某块当前生效的正文（{@link #resolve}，
 * 用户版本可覆盖内置）、按上下文组装对话 system（{@link #chatSystem}）、提示词页全量视图
 * （{@link #view}）。消费方（Chunker、DocSummaryService、AnalyzeService、提示词页）只认本类，
 * 不直接触碰 Composer。
 *
 * <p><b>写</b>由本类落 {@code PromptStore}：用户版本 / 生效绑定 / 拼装顺序的 CRUD
 * （「潜意识 · 提示词」页的操作面）。
 *
 * <p>依赖单向：Manager → Composer →（块注册表 / store / .env），无环。
 */
@Service
public class PromptManager {

    /** 自动分叉出来的版本名：用户第一次直接编辑「内置」正文时使用 */
    private static final String FORKED_NAME = "自定义版本";

    private final PromptComposer composer;
    private final PromptStore store;

    public PromptManager(PromptComposer composer, PromptStore store) {
        this.composer = composer;
        this.store = store;
    }

    // ---------- 读（委托 Composer） ----------

    /** 某提示词块当前生效的正文：绑定版本 → 该版本内容；未绑定/版本已删 → 内置 */
    public String resolve(String slotKey) {
        return composer.resolve(slotKey);
    }

    /**
     * 带会话级覆盖的解析：versionId 为空 → 走全局（{@link #resolve(String)}）；
     * {@link PromptComposer#BUILTIN}（或旧 id "env"）→ 显式用内置；否则用该版本（不存在/不属于该块时回退全局）。
     */
    public String resolve(String slotKey, String versionId) {
        return composer.resolve(slotKey, versionId);
    }

    /**
     * 对话 system：「对话」上下文里的提示词块按用户排定的顺序拼起来（空片段自动跳过），
     * 身份块支持会话级版本覆盖。
     *
     * @param identityVersionId 会话级身份版本；空 = 用提示词页的全局绑定，{@code "builtin"} = 显式用内置
     */
    public String chatSystem(String identityVersionId) {
        return composer.chatSystem(identityVersionId);
    }

    /** 提示词页全量视图：大类 → 上下文 → 提示词块（按拼装顺序，含内置/版本/生效项）+ 运行时素材 */
    public PromptDtos.PromptsView view() {
        return composer.view();
    }

    /**
     * 全局提示词页的「仅开发者选项」视图：对话与圆桌都已逐 Agent 化，全局页只展示流水线上的自动环节。
     * 全局操作面（{@code PromptController}）走本方法。
     */
    public PromptDtos.PromptsView viewDevOnly() {
        return composer.viewDevOnly();
    }

    /**
     * 单个上下文的视图（版本 / 生效项取全局）。给「不随成员分叉」的上下文用：圆桌是全桌共用的一套，
     * 智能体内部页把它并进面板时，读到的就是这里这一份。
     */
    public PromptDtos.ContextView contextView(String contextKey) {
        return composer.contextView(contextKey);
    }

    /** 某个全局版本属于哪个提示词位；不存在返回空（供成员侧判断「这个 id 是不是全局的」） */
    public Optional<String> slotOfVersion(String id) {
        return id == null ? Optional.empty() : store.version(id).map(VersionRow::promptKey);
    }

    // ---------- 写（版本 / 绑定 / 顺序；视图由 Composer 重新组装） ----------

    public void createVersion(String slotKey, PromptDtos.VersionRequest req) {
        requireSlot(slotKey);
        requireName(req);
        store.insertVersion(new VersionRow(UUID.randomUUID().toString(), slotKey,
                req.name().strip(), req.content() == null ? "" : req.content(), 0));
    }

    public void updateVersion(String id, PromptDtos.VersionRequest req) {
        requireName(req);
        requireVersion(id);
        store.updateVersion(id, req.name().strip(), req.content() == null ? "" : req.content());
    }

    public void deleteVersion(String id) {
        requireVersion(id);
        store.deleteVersion(id);
        /* 版本被删 → 指向它的绑定落回内置 */
        store.clearBindingsOfVersion(id);
    }

    /** 选定某块生效的版本；versionId 为空或 builtin = 用内置 */
    public void bind(String slotKey, PromptDtos.BindRequest req) {
        requireSlot(slotKey);
        String versionId = req == null ? null : req.versionId();
        String id = versionId == null ? null : versionId.strip();
        if (id == null || id.isEmpty() || PromptComposer.BUILTIN.equals(id)) {
            store.saveBinding(slotKey, null);
            return;
        }
        VersionRow row = store.version(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提示词版本不存在: " + id));
        if (!row.promptKey().equals(slotKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该版本不属于提示词位 " + slotKey);
        }
        store.saveBinding(slotKey, id);
    }

    /**
     * 保存某提示词块「当前生效的正文」（便签编辑器直接改正文走这条）：
     * 已绑定版本则改那份版本；仍是内置则自动分叉出一份新版本并绑定，内置原文不受影响。
     */
    public void saveContent(String slotKey, PromptDtos.ContentRequest req) {
        requireSlot(slotKey);
        String body = req == null || req.content() == null ? "" : req.content();
        String active = null;
        for (BindingRow b : store.bindings()) {
            if (b.promptKey().equals(slotKey)) {
                active = b.versionId();
                break;
            }
        }
        if (active != null && !active.isBlank()) {
            VersionRow row = store.version(active).orElse(null);
            if (row != null && row.promptKey().equals(slotKey)) {
                store.updateVersion(active, row.name(), body);
                return;
            }
        }
        String id = UUID.randomUUID().toString();
        store.insertVersion(new VersionRow(id, slotKey, FORKED_NAME, body, 0));
        store.saveBinding(slotKey, id);
    }

    /** 覆盖某上下文内提示词块的拼装顺序（拖拽结果）；未带上的块按注册表顺序补在末尾 */
    public void saveOrder(String contextKey, PromptDtos.OrderRequest req) {
        PromptContext ctx = requireContext(contextKey);
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
        store.saveOrder(contextKey, order);
    }

    // ---------- 私有 ----------

    private static PromptContext requireContext(String contextKey) {
        return PromptLibrary.context(contextKey).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "上下文不存在: " + contextKey));
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

    private void requireVersion(String id) {
        if (id == null || store.version(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提示词版本不存在: " + id);
        }
    }
}
