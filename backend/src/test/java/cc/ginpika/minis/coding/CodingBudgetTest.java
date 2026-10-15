package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.context.CodingBudget;
import cc.ginpika.minis.coding.context.CodingContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodingBudgetTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void defaultBudgetDoesNotPauseLargeMultiRoundTasks() {
        var budget=new CodingBudget(new CodingProps().getMaxEstimatedTokens(),json);
        var message=json.createObjectNode().put("content","answer");
        var raw=new OpenAiChatClient.RawResult(message,"{\"usage\":{\"prompt_tokens\":120000,\"completion_tokens\":1000}}","stop");
        var request=json.createObjectNode();
        for(int i=0;i<5;i++){budget.beforeRound();budget.response(request,raw,1,i);}
        assertDoesNotThrow(budget::beforeRound);
        assertEquals(726_000L,budget.response(request,raw,1,5).get("totalTokens"));
    }

    @Test void explicitCapUsesProviderUsageAndStopsBeforeAnotherRequest() {
        var budget=new CodingBudget(10_000,json);
        var request=json.createObjectNode();request.putArray("messages").addObject().put("content","x".repeat(100_000));
        var raw=new OpenAiChatClient.RawResult(json.createObjectNode().put("content","ok"),
                "{\"usage\":{\"prompt_tokens\":4000,\"completion_tokens\":2000,\"total_tokens\":6000}}","stop");
        var first=budget.response(request,raw,1,0);
        assertEquals(false,first.get("estimated"));assertEquals(6000L,first.get("totalTokens"));
        assertDoesNotThrow(budget::beforeRound);
        budget.response(request,raw,1,1);
        assertTrue(assertThrows(CodingContext.ContextOverflow.class,budget::beforeRound).getMessage().contains("12000 / 10000"));
    }

    @Test void missingUsageEstimatesTextWithoutChargingJsonEscapingAndEnvelope() {
        var budget=new CodingBudget(0,json);
        var request=json.createObjectNode();request.putArray("messages").addObject().put("content","\n".repeat(100));
        var result=budget.response(request,new OpenAiChatClient.RawResult(json.createObjectNode().put("content","你好"),"raw","stop"),2,3);
        assertEquals(true,result.get("estimated"));assertEquals(false,result.get("enabled"));
        assertEquals(27L,result.get("totalTokens"));assertEquals(2,result.get("attempt"));
    }
}
