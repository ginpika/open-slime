package cc.ginpika.minis.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentToolCatalogTest {
    @Mock ToolManager tools;
    @InjectMocks AgentController controller;

    @Test void coreCatalogIncludesManagementToolsAndMarksEverythingGranted() {
        AiTool management = mock(AiTool.class);
        when(management.name()).thenReturn("create_plan");
        when(management.domain()).thenReturn(ToolDomain.values()[0]);
        when(tools.availableTools(ToolSurface.CHAT)).thenReturn(List.of(management));
        var catalog = controller.tools(BuiltinAgents.CORE);
        assertEquals("create_plan", catalog.getFirst().name());
        assertTrue(catalog.getFirst().defaultGranted());
        verify(tools, never()).grantableTools(any());
    }

    @Test void ordinaryAndCodingCatalogsKeepTheGrantableToolBoundary() {
        when(tools.grantableTools(ToolSurface.CHAT)).thenReturn(List.of());
        assertTrue(controller.tools(null).isEmpty());
        assertTrue(controller.tools("member").isEmpty());
        assertTrue(controller.tools(BuiltinAgents.CODING).isEmpty());
        verify(tools, never()).availableTools(any());
    }
}
