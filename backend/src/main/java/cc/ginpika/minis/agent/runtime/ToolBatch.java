package cc.ginpika.minis.agent.runtime;

import cc.ginpika.minis.agent.subagent.SubagentService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一批工具调用的执行：能并行的并行，不能的按原顺序来。
 *
 * <p>模型在一条消息里一次要求调多个工具时才会用到它。若这一批全部声明可并行
 * （见 {@link cc.ginpika.minis.ai.core.tool.AiTool#parallelizable()}，目前只有
 * {@code delegate_subagent}），就按 {@link #MAX_PARALLEL} 分批用虚拟线程并发跑——
 * 一次派好几个子代理时并的是它们各自的受理回执（子代理本身在 {@link SubagentService}
 * 的后台线程里跑，不在这一段等着）；只要混进一个不能并行的工具，
 * 整批退回串行，免得打乱「先写后读」这类隐含顺序。
 *
 * <p>结果按<b>原顺序</b>返回（与调用顺序一一对应），单条调用抛错只把这一条兜成可读文本，
 * 不牵连同一批里的其他调用。
 */
public final class ToolBatch {

    /** 一批里同时跑几条的上限：再多就排队，别把上游 API 打爆 */
    public static final int MAX_PARALLEL = 4;

    private static final Logger log = LoggerFactory.getLogger(ToolBatch.class);

    /** 并发用的虚拟线程池：只跑「等上游 LLM 返回」这类阻塞活 */
    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    private ToolBatch() {
    }

    /**
     * 执行一批调用，按原顺序返回每条的结果文本。
     *
     * @param parallelizable 该工具名是否可并行
     * @param one            落地一条调用并返回要回给模型的文本（副作用如写库留痕在它内部完成）
     */
    public static List<String> run(List<JsonNode> calls, Predicate<String> parallelizable,
                                   Function<JsonNode, String> one) {
        List<String> results = new ArrayList<>(calls.size());
        boolean parallel = calls.size() > 1
                && calls.stream().allMatch(call -> parallelizable.test(nameOf(call)));
        if (!parallel) {
            for (JsonNode call : calls) {
                results.add(safeRun(one, call));
            }
            return results;
        }
        for (int from = 0; from < calls.size(); from += MAX_PARALLEL) {
            int to = Math.min(from + MAX_PARALLEL, calls.size());
            List<CompletableFuture<String>> futures = new ArrayList<>(to - from);
            for (JsonNode call : calls.subList(from, to)) {
                futures.add(CompletableFuture.supplyAsync(() -> safeRun(one, call), POOL));
            }
            for (CompletableFuture<String> future : futures) {
                results.add(future.join());
            }
        }
        return results;
    }

    /** 单条调用抛错时兜成一条可读文本：整批不因其中一条失败而中断 */
    private static String safeRun(Function<JsonNode, String> one, JsonNode call) {
        try {
            return one.apply(call);
        } catch (RuntimeException e) {
            log.warn("工具调用失败：{} {}", nameOf(call), e.getMessage());
            return "工具执行失败：" + e.getMessage();
        }
    }

    private static String nameOf(JsonNode call) {
        return call.path("function").path("name").asText("");
    }
}
