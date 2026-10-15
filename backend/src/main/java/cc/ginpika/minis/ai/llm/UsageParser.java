package cc.ginpika.minis.ai.llm;

import cc.ginpika.minis.stats.TokenUsageService.Usage;
import com.fasterxml.jackson.databind.JsonNode;

/** 保留缺失与零的区别，并保留已包含在输入/输出内的缓存与推理细分。 */
final class UsageParser {
    static Usage parse(JsonNode node, boolean inputOnly) {
        if (!node.isObject()) return null;
        Long input=number(node,"prompt_tokens","input_tokens");
        Long output=number(node,"completion_tokens","output_tokens");
        Long total=number(node,"total_tokens");
        Long cached=number(node.path("prompt_tokens_details"),"cached_tokens");
        if (cached==null) cached=number(node.path("input_tokens_details"),"cached_tokens");
        if (cached==null) cached=number(node,"prompt_cache_hit_tokens");
        Long reasoning=number(node.path("completion_tokens_details"),"reasoning_tokens");
        if (reasoning==null) reasoning=number(node.path("output_tokens_details"),"reasoning_tokens");
        Usage result=Usage.reported(input,output,total,inputOnly,cached==null ? 0 : cached,reasoning==null ? 0 : reasoning);
        // 缓存写入等非标准计费信息不能用普通输入单价冒充完整计价。
        if (result!=null && node.path("cache_creation_input_tokens").asLong(0)>0) return result.partial();
        return result;
    }
    private static Long number(JsonNode node,String... names) {
        for (String name:names) {
            JsonNode value=node.path(name);
            if (value.isIntegralNumber() && value.canConvertToLong()) return value.longValue();
        }
        return null;
    }
    private UsageParser() {}
}
