package cc.ginpika.minis.ai.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.knowledge.web.WebToolbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentToolDispatchTest {
    @Mock DocumentToolbox documents;
    @Mock WebToolbox web;
    @Mock ToolManager tools;
    @InjectMocks KnowledgeToolbox dispatcher;

    @Test void documentToolsReachLibraryAndSavedSnapshotsReachWebReader() throws Exception {
        var args = new ObjectMapper().readTree("{}");
        for (String name : java.util.List.of("list_documents", "read_document", "search_documents_by_title", "create_document")) {
            when(documents.run(name, args)).thenReturn("document result");
            assertEquals("document result", dispatcher.run(name, "{}", "chat").content());
            verify(documents).run(name, args);
        }
        when(web.run("read_web_snapshot", args)).thenReturn("snapshot result");
        assertEquals("snapshot result", dispatcher.run("read_web_snapshot", "{}", "chat").content());
        verify(web).run("read_web_snapshot", args);
    }
}
