package cc.ginpika.minis.coding;

import cc.ginpika.minis.coding.context.CodingStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodingStreamTest {
    private record Event(String type,Map<?,?> payload) {}
    private final ArrayList<Event> events=new ArrayList<>();
    private final CodingStream stream=new CodingStream(2,(type,payload)->events.add(new Event(type,(Map<?,?>)payload)));
    private final ObjectMapper json=new ObjectMapper();

    @Test void reasoningAndContentArePersistedSeparatelyWithReplaySnapshot() {
        stream.delta(0,"先定位",null);stream.delta(0,"入口","正在读取文件");
        stream.finish(0,json.createObjectNode().put("reasoning_content","先定位入口"));
        assertEquals("reasoning.delta",events.get(0).type());assertEquals("先定位入口",events.get(0).payload().get("text"));
        assertEquals("message.delta",events.get(1).type());assertEquals("正在读取文件",events.get(1).payload().get("text"));
        assertEquals("reasoning.finished",events.get(2).type());assertEquals(2,events.get(2).payload().get("attempt"));
        assertEquals(0,events.get(2).payload().get("round"));
    }

    @Test void responseOnlyReasoningAndTruncatedReasoningAreStillVisible() {
        stream.finish(3,json.createObjectNode().put("reasoning_content","供应商只在最终响应提供的思考"));
        assertEquals(1,events.size());assertEquals("reasoning.finished",events.getFirst().type());
        assertEquals(3,events.getFirst().payload().get("round"));
    }

    @Test void interruptionFlushesPartialReasoningWithoutInventingACompletedResponse() {
        stream.delta(1,"尚未完成的思考",null);stream.flush();stream.flush();
        assertEquals(1,events.size());assertEquals("reasoning.delta",events.getFirst().type());
        assertEquals(1,events.getFirst().payload().get("round"));
    }

    @Test void absentProviderReasoningDoesNotFabricateThoughts() {
        stream.delta(0,null,"正文");stream.finish(0,json.createObjectNode().put("content","正文"));
        assertEquals(1,events.size());assertEquals("message.delta",events.getFirst().type());
    }
}
