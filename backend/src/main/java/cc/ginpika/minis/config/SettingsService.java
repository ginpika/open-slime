package cc.ginpika.minis.config;

import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.llm.RerankClient;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.knowledge.anchor.AnchorSeeder;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import cc.ginpika.minis.ai.memory.MemoryMaintenanceService;
import cc.ginpika.minis.config.dto.SettingsDtos;
import cc.ginpika.minis.stats.TokenUsageService;
import cc.ginpika.minis.stats.client.ModelMetaClient;
import cc.ginpika.minis.config.store.SettingsStore;
import cc.ginpika.minis.config.store.SettingsStore.EmbeddingRow;
import cc.ginpika.minis.config.store.SettingsStore.ModelRow;
import cc.ginpika.minis.config.store.SettingsStore.RerankRow;
import cc.ginpika.minis.config.store.SettingsStore.SettingRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 设置页与运行时配置中枢（模型 / Embedding / Rerank）。
 *
 * <p>持久化在 SQLite（SettingsStore）；.env 传入的 LLM/Embedding 作为内置项（id="env"）在运行时合成，
 * 不入库、不可编辑删除，并在用户未指定默认项时兜底。提示词的用户版本见 {@link cc.ginpika.minis.ai.core.prompt.PromptManager}。
 */
@Service
public class SettingsService {

    /** 内置项 id（.env 传入），不可编辑/删除 */
    public static final String ENV_ID = "env";

    private static final String MODE_LLM = "llm";
    private static final String MODE_CUSTOM = "custom";
    private static final String MODE_OFF = "off";

    /** 连通性测试的基准消息：要求极短固定回复，尽量少耗 token 与时间 */
    private static final String TEST_PROMPT = "连通性测试：请只回复两个字「正常」";

    /** 测试请求的响应上限：超过即判定超时，避免设置页按钮长时间挂起 */
    private static final long TEST_TIMEOUT_MS = 5000;

    /** Embedding 测试的探针文本 */
    private static final String TEST_EMBED_TEXT = "连通性测试";

    /** Rerank 测试的探针查询与候选文档 */
    private static final String TEST_RERANK_QUERY = "连通性测试";

    private static final List<String> TEST_RERANK_DOCS =
            List.of("连通性测试文档一", "连通性测试文档二");

    /** 测试回复截断长度（单行展示用） */
    private static final int TEST_REPLY_LIMIT = 120;

    /** 测试调用跑在虚拟线程上，超时后立即返回（虚拟线程廉价，无需回收池） */
    private static final ExecutorService TEST_POOL = Executors.newVirtualThreadPerTaskExecutor();

    private final LlmProps props;
    private final SettingsStore store;
    private final ModelMetaClient meta;
    /* @Lazy 断开与 OpenAiChatClient 的构造循环（chat 客户端反过来依赖本服务解析默认模型） */
    private final OpenAiChatClient chat;
    /* 同上：Embedding / Rerank 客户端也依赖本服务解析各自端点 */
    private final EmbeddingClient embedding;
    private final RerankClient rerank;
    /* 知识库存储：设置页「重置知识库」直接清空 chunk 与图谱（含相关向量） */
    private final ChunkStore chunkStore;
    private final GraphStore graphStore;
    /* 记忆子系统维护：重置知识库时连带清除<b>全部</b>记忆落点（事件 / 条目 / 候选 / 共享 / 墓碑 / 知识边 / 水位） */
    private final MemoryMaintenanceService memoryMaintenance;
    /* 重置会连带删掉图谱锚点，清空后须补种（见 resetKnowledgeBase）。
       @Lazy 断环：AnchorSeeder 的依赖链会绕回本服务，硬注入即构造成环（本类已有同款 @Lazy 惯例）。 */
    private final AnchorSeeder anchorSeeder;

    public SettingsService(LlmProps props, SettingsStore store, ModelMetaClient meta,
                           @Lazy OpenAiChatClient chat, @Lazy EmbeddingClient embedding,
                           @Lazy RerankClient rerank, ChunkStore chunkStore, GraphStore graphStore,
                           MemoryMaintenanceService memoryMaintenance, @Lazy AnchorSeeder anchorSeeder) {
        this.props = props;
        this.store = store;
        this.meta = meta;
        this.chat = chat;
        this.embedding = embedding;
        this.rerank = rerank;
        this.chunkStore = chunkStore;
        this.graphStore = graphStore;
        this.memoryMaintenance = memoryMaintenance;
        this.anchorSeeder = anchorSeeder;
    }

    // ---------- 视图 ----------

    /** 设置页全量视图：内置 env 模型项 + 用户模型 + 默认选择 + Embedding + Rerank */
    public SettingsDtos.SettingsView view() {
        List<SettingsDtos.ModelView> models = new ArrayList<>();
        models.add(envModel());
        store.models().forEach(m -> models.add(toModelView(m)));

        SettingRow setting = store.setting().orElse(null);
        String defaultModelId = pickDefault(setting == null ? null : setting.defaultModelId(),
                models.stream().map(SettingsDtos.ModelView::id).toList());

        return new SettingsDtos.SettingsView(models, embeddingView(), rerankView(), defaultModelId);
    }

    // ---------- 模型 CRUD ----------

    public SettingsDtos.SettingsView createModel(SettingsDtos.ModelRequest req) {
        requireModelFields(req);
        store.insertModel(new ModelRow(UUID.randomUUID().toString(), req.name().strip(),
                req.baseUrl().strip(), req.apiKey().strip(), req.model().strip(),
                resolveContextWindow(req.baseUrl().strip(), req.apiKey().strip(),
                        req.model().strip(), req.contextWindow()),req.maxOutputTokens()));
        return view();
    }

    public SettingsDtos.SettingsView updateModel(String id, SettingsDtos.ModelRequest req) {
        requireNotBuiltin(id, "模型");
        requireModelFields(req);
        requireModelExists(id);
        store.updateModel(new ModelRow(id, req.name().strip(), req.baseUrl().strip(),
                req.apiKey().strip(), req.model().strip(),
                resolveContextWindow(req.baseUrl().strip(), req.apiKey().strip(),
                        req.model().strip(), req.contextWindow()),req.maxOutputTokens()));
        return view();
    }

    public SettingsDtos.SettingsView deleteModel(String id) {
        requireNotBuiltin(id, "模型");
        requireModelExists(id);
        store.deleteModel(id);
        /* 默认项指向被删模型 → 清空，运行时回退内置 env 项 */
        SettingRow setting = store.setting().orElse(null);
        if (setting != null && id.equals(setting.defaultModelId())) {
            store.saveSetting(null);
        }
        return view();
    }

    /** 按给定端点从模型 API 试取上下文窗口（设置页编辑时自动回填）；取不到返回 null */
    public SettingsDtos.ContextWindowView fetchContextWindow(
            SettingsDtos.ContextWindowRequest req) {
        Integer v = meta.fetchContextWindow(req.baseUrl(), req.apiKey(), req.model());
        return new SettingsDtos.ContextWindowView(v);
    }

    /**
     * 模型连通性测试（设置页模型列表）：基于 Chat 同款 /chat/completions 发一条基准对话，
     * 验证接口能否正常返回；ok=false 时 error 携带失败原因（未配置 / HTTP 错误 / 超时等）。
     */
    public SettingsDtos.ChatTestView testModel(String id) {
        ModelTarget target = requireModelTarget(id);
        long start = System.nanoTime();
        try {
            OpenAiChatClient.Completion c = withinTestLimit(() -> chat.chat(target,
                    List.of(new OpenAiChatClient.ChatMessage("user", TEST_PROMPT)),
                    TokenUsageService.SOURCE_MODEL_TEST));
            return new SettingsDtos.ChatTestView(true, elapsedMs(start),
                    abbreviate(c.content()), null);
        } catch (Exception e) {
            return new SettingsDtos.ChatTestView(false, elapsedMs(start), null, reason(e));
        }
    }

    /**
     * Embedding 连通性测试：按设置页表单里的草稿端点向量化一条探针文本，
     * 返回向量维度；不改动已保存配置。
     */
    public SettingsDtos.TestView testEmbedding(SettingsDtos.EmbeddingRequest req) {
        ModelTarget target = draftTarget(req.mode(), req.llmModelId(), req.baseUrl(),
                req.apiKey(), req.model());
        long start = System.nanoTime();
        try {
            float[] vec = withinTestLimit(() -> embedding.embed(target, TEST_EMBED_TEXT, TokenUsageService.SOURCE_MODEL_TEST))
                    .orElseThrow(() -> new IllegalStateException("未配置 Embedding 端点"));
            return new SettingsDtos.TestView(true, elapsedMs(start), "向量维度 " + vec.length, null);
        } catch (Exception e) {
            return new SettingsDtos.TestView(false, elapsedMs(start), null, reason(e));
        }
    }

    /**
     * Rerank 连通性测试：按设置页表单里的草稿端点对两条探针文档打分，
     * 返回最高分候选；不改动已保存配置。
     */
    public SettingsDtos.TestView testRerank(SettingsDtos.RerankRequest req) {
        if (MODE_OFF.equals(normalize(req.mode()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rerank 精排已关闭，无需测试");
        }
        ModelTarget target = draftTarget(req.mode(), req.llmModelId(), req.baseUrl(),
                req.apiKey(), req.model());
        long start = System.nanoTime();
        try {
            double[] scores = withinTestLimit(
                    () -> rerank.score(target, TEST_RERANK_QUERY, TEST_RERANK_DOCS, TokenUsageService.SOURCE_MODEL_TEST))
                    .orElseThrow(() -> new IllegalStateException(
                            "响应缺少 results 或 relevance_score"));
            int top = 0;
            for (int i = 1; i < scores.length; i++) {
                if (scores[i] > scores[top]) {
                    top = i;
                }
            }
            return new SettingsDtos.TestView(true, elapsedMs(start),
                    scores.length + " 条候选打分成功，最高分 " + String.format("%.4f", scores[top]),
                    null);
        } catch (Exception e) {
            return new SettingsDtos.TestView(false, elapsedMs(start), null, reason(e));
        }
    }

    /**
     * 按设置页表单草稿解析端点：custom 用表单里的独立端点，其余复用所选模型端点
     * （llmModelId 缺省/env 视为 .env 内置端点）。
     */
    private ModelTarget draftTarget(String mode, String llmModelId, String baseUrl, String apiKey,
                                    String model) {
        String m = normalize(model);
        if (MODE_CUSTOM.equals(normalize(mode))) {
            return new ModelTarget(normalize(baseUrl), normalize(apiKey), m);
        }
        ModelTarget llm = resolveModelById(normalize(llmModelId));
        return new ModelTarget(llm.baseUrl(), llm.apiKey(), m);
    }

    /** 在 {@link #TEST_TIMEOUT_MS} 内执行测试调用；超时抛出携带原因的 TimeoutException */
    private static <T> T withinTestLimit(Callable<T> task) throws Exception {
        Future<T> future = TEST_POOL.submit(task);
        try {
            return future.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("超过 " + TEST_TIMEOUT_MS / 1000 + "s 未响应");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** 失败原因（异常消息优先，空则退回异常类名） */
    private static String reason(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** 按列表 id 精确解析模型端点（连通性测试用）：非内置 id 不存在时 404，不回退 env */
    private ModelTarget requireModelTarget(String id) {
        if (id == null || id.isBlank() || ENV_ID.equals(id)) {
            return resolveModelById(null);
        }
        ModelRow row = store.model(id).orElse(null);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在: " + id);
        }
        return new ModelTarget(row.baseUrl(), row.apiKey(), row.model());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 测试回复压成单行并截断，便于前端 Toast 展示 */
    private static String abbreviate(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() <= TEST_REPLY_LIMIT ? t : t.substring(0, TEST_REPLY_LIMIT) + "…";
    }

    // ---------- 默认选择与 Embedding ----------

    public SettingsDtos.SettingsView saveDefaults(SettingsDtos.DefaultsRequest req) {
        String modelId = normalize(req == null ? null : req.modelId());
        if (modelId != null && !ENV_ID.equals(modelId) && store.model(modelId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型不存在: " + modelId);
        }
        store.saveSetting(modelId);
        return view();
    }

    public SettingsDtos.SettingsView saveEmbedding(SettingsDtos.EmbeddingRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Embedding 配置不能为空");
        }
        String mode = normalize(req.mode());
        if (mode == null) {
            mode = MODE_LLM;
        }
        if (!MODE_LLM.equals(mode) && !MODE_CUSTOM.equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode 仅支持 llm | custom");
        }
        if (isBlank(req.model())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Embedding 模型名不能为空");
        }
        String llmModelId = normalize(req.llmModelId());
        if (MODE_CUSTOM.equals(mode)) {
            if (isBlank(req.baseUrl()) || isBlank(req.apiKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "自定义 Embedding 需填写 base-url 与 api-key");
            }
        } else if (llmModelId != null && !ENV_ID.equals(llmModelId)
                && store.model(llmModelId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复用的模型不存在: " + llmModelId);
        }
        store.saveEmbedding(new EmbeddingRow(mode,
                MODE_CUSTOM.equals(mode) ? null : llmModelId,
                MODE_CUSTOM.equals(mode) ? req.baseUrl().strip() : null,
                MODE_CUSTOM.equals(mode) ? req.apiKey().strip() : null,
                req.model().strip()));
        return view();
    }

    /**
     * 保存 Rerank 配置：off 关闭精排（保留其余字段，便于再次打开时沿用）；
     * llm 复用某模型端点；custom 用独立端点。
     */
    public SettingsDtos.SettingsView saveRerank(SettingsDtos.RerankRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rerank 配置不能为空");
        }
        String mode = normalize(req.mode());
        if (mode == null) {
            mode = MODE_LLM;
        }
        if (!MODE_LLM.equals(mode) && !MODE_CUSTOM.equals(mode) && !MODE_OFF.equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode 仅支持 llm | custom | off");
        }
        if (MODE_OFF.equals(mode)) {
            store.saveRerank(new RerankRow(MODE_OFF, normalize(req.llmModelId()),
                    normalize(req.baseUrl()), normalize(req.apiKey()), normalize(req.model())));
            return view();
        }
        if (isBlank(req.model())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rerank 模型名不能为空");
        }
        String llmModelId = normalize(req.llmModelId());
        if (MODE_CUSTOM.equals(mode)) {
            if (isBlank(req.baseUrl()) || isBlank(req.apiKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "自定义 Rerank 需填写 base-url 与 api-key");
            }
        } else if (llmModelId != null && !ENV_ID.equals(llmModelId)
                && store.model(llmModelId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复用的模型不存在: " + llmModelId);
        }
        store.saveRerank(new RerankRow(mode,
                MODE_CUSTOM.equals(mode) ? null : llmModelId,
                MODE_CUSTOM.equals(mode) ? req.baseUrl().strip() : null,
                MODE_CUSTOM.equals(mode) ? req.apiKey().strip() : null,
                req.model().strip()));
        return view();
    }

    // ---------- 危险操作 ----------

    /**
     * 重置知识库（设置页「危险操作」）：清空全部 chunk（含双 FTS 索引、chunk_extraction 与
     * chunk_embedding）、知识图谱的全部实体/关系（含 chunk_entity 关联与 entity_embedding），
     * 以及<b>整个记忆子系统</b>——时序事件（memory_event）、记忆条目（memory_item）、
     * 画像候选、共享、撤销墓碑、知识边与提炼水位（见 {@link MemoryMaintenanceService}），不可恢复。
     * documents 元数据不在删除范围。整个清空在同一事务内完成。
     *
     * <p>清空会连带删掉图谱锚点（预置「用户」等），故随后立即补种一次——锚点由 clearAll 一并
     * 抹掉，若不再播种，依赖锚点的功能（用户画像、锚点视图）要到下次重启才恢复。
     */
    @Transactional
    public SettingsDtos.ResetResult resetKnowledgeBase() {
        GraphStore.Cleared graph = graphStore.clearAll();
        int chunks = chunkStore.clearAll();
        MemoryMaintenanceService.Cleared mem = memoryMaintenance.clearAll();
        anchorSeeder.seed();
        return new SettingsDtos.ResetResult(chunks, graph.entities(), graph.relations(), mem.entries(),
                mem.events(), mem.items(), mem.candidates(), mem.shares());
    }

    // ---------- 运行时解析 ----------

    /** 默认聊天模型（无请求级指定时使用） */
    public ModelTarget chatModel() {
        return resolveChatModel(null);
    }

    /** 解析聊天模型：modelId 缺省 → 已保存默认 → 内置 env 项 */
    public ModelTarget resolveChatModel(String modelId) {
        return resolveModelById(chatModelId(modelId));
    }

    /** Resolve the selected identifier so long-running tasks can persist their model choice. */
    public String chatModelId(String modelId) {
        String id = normalize(modelId);
        if (id == null) {
            SettingRow setting = store.setting().orElse(null);
            id = normalize(setting == null ? null : setting.defaultModelId());
        }
        return id == null ? ENV_ID : id;
    }

    /** Output capability cannot be inferred from the context window. Null uses provider defaults. */
    public Integer chatOutputTokens(String modelId){
        String id=chatModelId(modelId);
        return ENV_ID.equals(id)?null:store.model(id).map(ModelRow::maxOutputTokens).filter(value->value>0).orElse(null);
    }

    /** 解析 Embedding 端点：mode=llm 复用某模型端点的 base-url/api-key，mode=custom 用独立端点 */
    public ModelTarget resolveEmbedding() {
        EmbeddingRow e = store.embedding().orElse(null);
        String mode = e == null ? MODE_LLM : normalize(e.mode());
        String model = e == null ? null : normalize(e.model());
        if (model == null) {
            model = props.getEmbeddingModel();
        }
        if (MODE_CUSTOM.equals(mode)) {
            return new ModelTarget(e.baseUrl(), e.apiKey(), model);
        }
        ModelTarget llm = resolveModelById(e == null ? null : e.llmModelId());
        return new ModelTarget(llm.baseUrl(), llm.apiKey(), model);
    }

    /**
     * 本次聊天所用模型的上下文窗口（token 数）：与会话页「上下文占比」同一个数——
     * 按所选模型取它配置的窗口；内置 / 取不到用 .env 的默认窗口。
     * 供上下文平面判断「是否已接近临界、该裁历史了」（见 agent.chat.turn.History）。
     */
    public int chatContextWindow(String modelId) {
        String id = normalize(modelId);
        if (id == null) {
            SettingRow setting = store.setting().orElse(null);
            id = normalize(setting == null ? null : setting.defaultModelId());
        }
        if (id != null && !ENV_ID.equals(id)) {
            ModelRow row = store.model(id).orElse(null);
            Integer configured = positiveOrNull(row == null ? null : row.contextWindow());
            if (configured != null) {
                return configured;
            }
        }
        Integer fallback = positiveOrNull(props.getContextWindow());
        return fallback != null ? fallback : LlmProps.DEFAULT_CONTEXT_WINDOW;
    }

    /** 聊天可用性（内置或已配置任一模型） */
    public boolean chatConfigured() {
        return chatModel().configured();
    }

    /**
     * 解析 Rerank 端点：mode=llm 复用某模型端点的 base-url/api-key，mode=custom 用独立端点；
     * mode=off 或模型名为空则不启用（返回未配置端点，检索保持 RRF 原序）。
     * 无配置行时回退 .env（默认模型端点 + RERANK_MODEL）。
     */
    public ModelTarget resolveRerank() {
        RerankRow r = store.rerank().orElse(null);
        if (r != null) {
            String mode = normalize(r.mode());
            String model = normalize(r.model());
            if (MODE_OFF.equals(mode) || model == null) {
                return new ModelTarget(null, null, null);
            }
            if (MODE_CUSTOM.equals(mode)) {
                return new ModelTarget(r.baseUrl(), r.apiKey(), model);
            }
            ModelTarget llm = resolveModelById(r.llmModelId());
            return new ModelTarget(llm.baseUrl(), llm.apiKey(), model);
        }
        ModelTarget llm = resolveModelById(null);
        return new ModelTarget(llm.baseUrl(), llm.apiKey(), props.getRerankModel());
    }

    /** Embedding 可用性 */
    public boolean embeddingConfigured() {
        return resolveEmbedding().configured();
    }

    // ---------- 私有 ----------

    /** id 缺省/"env" → 内置 env 端点；否则查库，查不到回退内置 */
    private ModelTarget resolveModelById(String id) {
        if (id != null && !ENV_ID.equals(id)) {
            ModelRow row = store.model(id).orElse(null);
            if (row != null) {
                return new ModelTarget(row.baseUrl(), row.apiKey(), row.model());
            }
        }
        return new ModelTarget(props.getBaseUrl(), props.getApiKey(), props.getChatModel());
    }

    private SettingsDtos.ModelView envModel() {
        /* 大模型以模型 id 为标题（.env 未配置模型名时回退 .env 本身） */
        String name = normalize(props.getChatModel());
        if (name == null) {
            name = ".env";
        }
        return new SettingsDtos.ModelView(ENV_ID, name, props.getBaseUrl(),
                props.getApiKey(), props.getChatModel(), props.getContextWindow(), true);
    }

    private SettingsDtos.EmbeddingView embeddingView() {
        EmbeddingRow e = store.embedding().orElse(null);
        if (e == null) {
            return new SettingsDtos.EmbeddingView(MODE_LLM, ENV_ID, null, null,
                    props.getEmbeddingModel());
        }
        String mode = normalize(e.mode());
        if (mode == null) {
            mode = MODE_LLM;
        }
        return new SettingsDtos.EmbeddingView(mode,
                MODE_CUSTOM.equals(mode) ? null : pickDefault(e.llmModelId(), List.of()),
                e.baseUrl(), e.apiKey(), e.model());
    }

    private SettingsDtos.RerankView rerankView() {
        RerankRow r = store.rerank().orElse(null);
        if (r == null) {
            /* 无配置行时按 .env 兜底；RERANK_MODEL 为空即未启用，视作关闭 */
            String model = normalize(props.getRerankModel());
            return new SettingsDtos.RerankView(model == null ? MODE_OFF : MODE_LLM, ENV_ID, null,
                    null, model);
        }
        String mode = normalize(r.mode());
        if (mode == null) {
            mode = MODE_LLM;
        }
        return new SettingsDtos.RerankView(mode,
                MODE_CUSTOM.equals(mode) ? null : pickDefault(r.llmModelId(), List.of()),
                r.baseUrl(), r.apiKey(), r.model());
    }

    private static SettingsDtos.ModelView toModelView(ModelRow m) {
        return new SettingsDtos.ModelView(m.id(), m.name(), m.baseUrl(), m.apiKey(), m.model(),
                m.contextWindow(), false,m.maxOutputTokens());
    }

    /** 默认项校验：必须存在于候选 id 中，否则回退首个内置项（env） */
    private static String pickDefault(String id, List<String> candidates) {
        String norm = normalize(id);
        if (norm == null) {
            return ENV_ID;
        }
        if (candidates.isEmpty() || candidates.contains(norm)) {
            return norm;
        }
        return ENV_ID;
    }

    private void requireModelFields(SettingsDtos.ModelRequest req) {
        if (req == null || isBlank(req.name()) || isBlank(req.baseUrl()) || isBlank(req.apiKey())
                || isBlank(req.model())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "模型配置需填写 名称/base-url/api-key/model");
        }
        if(req.maxOutputTokens()!=null && (req.maxOutputTokens()<1 || req.maxOutputTokens()>1_000_000)){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"单次输出上限须为 1 至 1000000 的 token 数，或留空跟随模型服务");
        }
    }

    private void requireNotBuiltin(String id, String what) {
        if (ENV_ID.equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置" + what + "不可编辑或删除");
        }
    }

    private void requireModelExists(String id) {
        if (id == null || store.model(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在: " + id);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String normalize(String s) {
        return isBlank(s) ? null : s.strip();
    }

    /**
     * 解析上下文窗口：手动填写优先；留空则从模型 API（GET {base}/models）尽力获取，
     * 接口不返回或调用失败时用 {@link LlmProps#DEFAULT_CONTEXT_WINDOW} 兜底。
     */
    private Integer resolveContextWindow(String baseUrl, String apiKey, String model,
                                         Integer manual) {
        Integer m = positiveOrNull(manual);
        if (m != null) {
            return m;
        }
        Integer fetched = meta.fetchContextWindow(baseUrl, apiKey, model);
        return fetched != null ? fetched : LlmProps.DEFAULT_CONTEXT_WINDOW;
    }

    /** 非正数（含未填）视为未设置 */
    private static Integer positiveOrNull(Integer n) {
        return n == null || n <= 0 ? null : n;
    }
}
