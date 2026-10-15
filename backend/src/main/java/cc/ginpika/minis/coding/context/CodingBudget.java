package cc.ginpika.minis.coding.context;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Per-attempt accounting. Provider usage wins; estimates never count JSON escaping. */
public final class CodingBudget {
    private final long limit;
    private final ObjectMapper json;
    private long consumed;
    private boolean estimated;

    public CodingBudget(long limit,ObjectMapper json) { this.limit=Math.max(0,limit);this.json=json; }

    public synchronized void beforeRound() {
        if(limit>0 && consumed>=limit){
            throw new CodingContext.ContextOverflow("已达到配置的累计 token 额度（"+consumed+" / "+limit+
                    "），检查点已保存，可继续。MINIS_CODING_MAX_TOKENS=0 可关闭累计额度限制。");
        }
    }

    public synchronized Map<String,Object> response(ObjectNode request,OpenAiChatClient.RawResult raw,int attempt,int round) {
        JsonNode usage=null;
        try{usage=json.readTree(raw.rawResponse()).path("usage");}catch(Exception ignored){/* Legacy or missing envelope: estimate text only. */}
        boolean real=usage!=null && usage.isObject() && (usage.has("prompt_tokens") || usage.has("completion_tokens"));
        long prompt=real?Math.max(0,usage.path("prompt_tokens").asLong()):estimate(request.path("messages"))+estimate(request.path("tools"));
        long completion=real?Math.max(0,usage.path("completion_tokens").asLong()):estimate(raw.message());
        long total=real?Math.max(prompt+completion,usage.path("total_tokens").asLong()):prompt+completion;
        consumed+=total;estimated|=!real;
        return Map.of("estimatedTokens",consumed,"totalTokens",consumed,"estimated",estimated,
                "promptTokens",prompt,"completionTokens",completion,"limit",limit,"enabled",limit>0,
                "attempt",attempt,"round",round);
    }

    private static long estimate(JsonNode node) {
        if(node==null || node.isNull() || node.isMissingNode()){return 0;}
        if(node.isTextual()){return TokenUsageService.estimateTokens(node.asText());}
        long result=0;for(JsonNode child:node){result+=estimate(child);}return result;
    }
}
