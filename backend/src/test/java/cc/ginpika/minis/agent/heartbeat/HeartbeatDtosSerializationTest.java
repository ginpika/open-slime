package cc.ginpika.minis.agent.heartbeat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.heartbeat.dto.HeartbeatDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 心跳后台 DTO 的序列化冒烟：{@link HeartbeatDtos.PlanView} 与 {@link HeartbeatDtos.HeartbeatView}
 * 都是含集合字段的嵌套 record，Jackson 走泛型解析——钉住它们能正常序列化，避免子页 406。
 *
 * <p>同时钉死「注入器已退役」：视图里不再有 injectors 字段，改为带 agentId。
 */
class HeartbeatDtosSerializationTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void serializesPlanViewWithAgentIdAndWithoutInjectors() throws Exception {
        HeartbeatDtos.PlanView view = new HeartbeatDtos.PlanView("p1", "builtin", "画像", "system",
                "正文", List.of("upsert_node"), List.of("silent"), 60_000L, null, null, 0, null,
                "维护画像", true);

        String json = OM.writeValueAsString(view);

        assertTrue(json.contains("\"agentId\":\"builtin\""));
        assertTrue(json.contains("\"tools\":[\"upsert_node\"]"));
        assertFalse(json.contains("injector"));
    }

    @Test
    void serializesHeartbeatView() throws Exception {
        HeartbeatDtos.HeartbeatView view = new HeartbeatDtos.HeartbeatView("builtin", "正文",
                "env", true, 60_000L, 123L);

        String json = OM.writeValueAsString(view);

        assertTrue(json.contains("\"agentId\":\"builtin\""));
        assertTrue(json.contains("\"enabled\":true"));
        assertTrue(json.contains("\"tickMs\":60000"));
        assertFalse(json.contains("injector"));
    }
}
