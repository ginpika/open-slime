package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.llm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebDistillTest {
    @Test void distillationUsesUserCriteriaAndValidatesSourceReferences() {
        var browser=mock(WebBrowserService.class);var store=mock(WebSnapshotStore.class);var chat=mock(OpenAiChatClient.class);
        var clean=SnapshotHtml.clean("<p>The author argues that method A only works when condition B holds.</p>","https://example.org");
        var capture=new WebDtos.Capture("captured","browser","https://example.org","Article","Author","",1,
                SnapshotHtml.hash(clean.html()),clean.paragraphs(),clean.warnings(),clean.html());
        when(browser.captured("owner","captured")).thenReturn(capture);
        when(chat.configured()).thenReturn(true);when(chat.model()).thenReturn("fixture-model");
        String json="{\"summary\":\"A conditional argument\",\"rationale\":\"Useful argument; requires verification\",\"claims\":[{\"text\":\"The author argues A requires B\",\"kind\":\"opinion\",\"evidence\":[\"p1\"]}]}";
        when(chat.chat(anyList(),eq("web-distill"))).thenReturn(new OpenAiChatClient.Completion(json,null,json));
        var service=new WebSnapshotService(browser,store,chat,mock(EmbeddingClient.class),mock(ChunkStore.class),new ObjectMapper());
        try {
            var draft=service.distill("owner",new WebDtos.DistillRequest("captured","Only preserve the author's argument and its conditions"));
            assertEquals("fixture-model",draft.model());assertEquals(List.of("p1"),draft.claims().getFirst().evidence());
            @SuppressWarnings("unchecked") ArgumentCaptor<List<OpenAiChatClient.ChatMessage>> messages=ArgumentCaptor.forClass(List.class);
            verify(chat).chat(messages.capture(),eq("web-distill"));
            assertTrue(messages.getValue().get(1).content().contains("Only preserve the author's argument"));
            assertTrue(messages.getValue().get(1).content().contains("[p1]"));
            when(chat.chat(anyList(),eq("web-distill"))).thenReturn(new OpenAiChatClient.Completion(json.replace("p1","p999"),null,""));
            assertThrows(IllegalArgumentException.class,()->service.distill("owner",new WebDtos.DistillRequest("captured","")));
            verifyNoInteractions(store);
            when(chat.configured()).thenReturn(false);
            assertThrows(IllegalStateException.class,()->service.distill("owner",new WebDtos.DistillRequest("captured","")));
            assertEquals(capture,browser.captured("owner","captured"));
        } finally {service.close();}
    }
}
