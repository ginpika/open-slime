package cc.ginpika.minis.coding;

import cc.ginpika.minis.coding.integration.*;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingInstructionTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void concreteReworkPromptIsNormalizedInsteadOfDiscarded() throws Exception {
        var input=json.readTree("{\"action\":\"resume\",\"runId\":\"r\",\"mode\":\"EXECUTE\",\"prompt\":\"修复 Hz 表格和 rAF 示例\"}");
        var parsed=CodingTaskCommand.parse(input);
        assertTrue(parsed.aliased());assertEquals("修复 Hz 表格和 rAF 示例",parsed.args().path("input").asText());
        assertFalse(parsed.args().has("prompt"));assertTrue(input.has("prompt"),"规范化不能改写原始调用证据");
        assertNull(new CodingTaskTool().validate(input));
    }
    @Test void ambiguousAndIgnoredArgumentsCannotSilentlyProceed() throws Exception {
        for(String raw:new String[]{
                "{\"action\":\"resume\",\"runId\":\"r\",\"input\":\"A\",\"prompt\":\"B\"}",
                "{\"action\":\"resume\",\"runId\":\"r\",\"mode\":\"admin\"}",
                "{\"action\":\"message\",\"runId\":\"r\",\"input\":\"fix\",\"mode\":\"EXECUTE\"}",
                "{\"action\":\"read\",\"runId\":\"r\",\"input\":\"fix\"}",
                "{\"action\":\"list\",\"repositoryId\":\"ignored\"}",
                "{\"action\":\"resume\",\"runId\":\"r\",\"userAuthorized\":true}"
        }) {
            var args=json.readTree(raw);assertNotNull(new CodingTaskTool().validate(args),raw);
            assertThrows(IllegalArgumentException.class,()->CodingTaskCommand.parse(args),raw);
        }
    }
    @Test void repeatedFailedEvidenceNeedsDiagnosisButProgressAndChangedInstructionsDoNot() {
        var store=mock(CodingStore.class);var convergence=new CodingConvergence(store,json);
        var run=new Run("r",1,"alice","p","goal",null,Mode.EXECUTE,Runner.WORKSPACE,"REVIEWABLE",9,0,null,null,"FAILED","hash",0,0);
        var same=json.createObjectNode().put("artifactHash","hash").put("instructionDigest",CodingConvergence.digest("fix"))
                .put("failure","VALIDATION_FAILED").put("validationHash","tree").put("failureEvidence","same-test");
        when(store.recentEvidence("r")).thenReturn(java.util.List.of(same.toString(),same.toString()));
        assertEquals("NEEDS_DIAGNOSIS",convergence.check(run,"fix").get("code"));
        assertNull(convergence.check(run,"采用另一种修复并核验命令落点"));
        var changed=same.deepCopy().put("artifactHash","new-hash");
        when(store.recentEvidence("r")).thenReturn(java.util.List.of(changed.toString(),same.toString()));
        assertNull(convergence.check(run,"fix"),"只要证据有进展，不按次数停工");
        when(store.recentEvidence("r")).thenReturn(java.util.List.of(same.deepCopy().put("failureEvidence","different-failure").toString(),same.toString()));
        assertNull(convergence.check(run,"fix"));
        verify(store,never()).nextAttempt(anyString());
    }
}
