package cc.ginpika.minis.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsageParserTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void missingFieldsAreNotReportedAsRealZeroAndTotalOnlyIsPreserved() throws Exception {
        var partial=UsageParser.parse(json.readTree("{\"prompt_tokens\":10,\"total_tokens\":15}"),false);
        assertEquals("partial",partial.quality()); assertEquals(15,partial.totalTokens());
        var totalOnly=UsageParser.parse(json.readTree("{\"total_tokens\":20}"),false);
        assertEquals(0,totalOnly.promptTokens()); assertEquals(20,totalOnly.totalTokens()); assertEquals("partial",totalOnly.quality());
        var embedding=UsageParser.parse(json.readTree("{\"total_tokens\":20}"),true);
        assertEquals(20,embedding.promptTokens()); assertEquals("reported",embedding.quality());
        assertNull(UsageParser.parse(json.readTree("{}"),false));
    }
    @Test void cachedAndReasoningTokensStaySubsetsAndInvalidCountersStayPartial() throws Exception {
        var usage=UsageParser.parse(json.readTree("{\"prompt_tokens\":100,\"completion_tokens\":30,\"total_tokens\":130,\"prompt_tokens_details\":{\"cached_tokens\":40},\"completion_tokens_details\":{\"reasoning_tokens\":20}}"),false);
        assertEquals("reported",usage.quality()); assertEquals(130,usage.totalTokens());
        assertEquals(40,usage.cachedTokens()); assertEquals(20,usage.reasoningTokens());
        assertEquals("partial",UsageParser.parse(json.readTree("{\"prompt_tokens\":-1,\"completion_tokens\":2}"),false).quality());
        assertEquals("partial",UsageParser.parse(json.readTree("{\"prompt_tokens\":1,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":10}}"),false).quality());
        assertEquals("partial",UsageParser.parse(json.readTree("{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":1}"),false).quality());
    }
    @Test void providerAliasesAndUnsupportedCacheWritesAreNotSilentlyMispriced() throws Exception {
        var usage=UsageParser.parse(json.readTree("{\"input_tokens\":100,\"output_tokens\":0,\"prompt_cache_hit_tokens\":30}"),false);
        assertEquals(30,usage.cachedTokens()); assertEquals("reported",usage.quality());
        assertEquals("partial",UsageParser.parse(json.readTree("{\"prompt_tokens\":100,\"completion_tokens\":0,\"cache_creation_input_tokens\":50}"),false).quality());
    }
}

