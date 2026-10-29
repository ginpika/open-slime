package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollaborationScopeGuardTest {
    @Test void humanGoalAndProjectDirectionStaySeparateAndChangedInstructionsInvalidateDecisions() throws Exception {
        var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            var jdbc=new JdbcTemplate(source);var json=new ObjectMapper();
            new ProjectStore(jdbc).insert("project",null,"卡牌塔防","software",false,"active","开发并持续迭代完整网页游戏");
            var sessions=new RoundtableSessionStore(jdbc);String session=sessions.insert("[\"controller\",\"worker\"]");
            sessions.touch(session,"确定游戏的技术选型和可行性");jdbc.update("UPDATE hub_session SET project_id='project' WHERE id=?",session);
            var plans=new CollaborationPlan(jdbc, new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc));plans.create(session,"controller");
            var client=mock(OpenAiChatClient.class);var settings=mock(SettingsService.class);
            when(client.chatRaw(any(),any(),any(),eq("collaboration"))).thenReturn(
                    new OpenAiChatClient.RawResult(json.createObjectNode().put("content","{\"allowed\":false,\"reason\":\"完整塔防、家园和桌面封装超出选型会话；只验证关键假设\"}"),"raw"),
                    new OpenAiChatClient.RawResult(json.createObjectNode().put("content","{\"allowed\":true,\"reason\":\"人类已明确扩大本次范围\"}"),"raw"));
            var guard=new CollaborationScopeGuard(plans,settings,client,json);
            String proposal="搭建框架、实现完整塔防业务、3D 家园、服务端和桌面封装";
            assertTrue(guard.check(session,"会话计划",proposal).contains("超出选型"));
            assertTrue(guard.check(session,"会话计划",proposal).contains("超出选型"));
            verify(client,times(1)).chatRaw(any(),any(),any(),eq("collaboration"));
            var request=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ObjectNode.class);
            verify(client).chatRaw(any(),request.capture(),any(),eq("collaboration"));
            assertTrue(request.getValue().toString().contains("本次会话目标（完成后停止）：确定游戏的技术选型和可行性"));
            assertTrue(request.getValue().toString().contains("项目长期方向（背景，不等于本次授权）"));
            jdbc.update("INSERT INTO chat_message(session_id,role,content,created_at) VALUES(?,'user',?,1)",session,"我明确扩大本次范围：实施这些模块");
            assertEquals("",guard.check(session,"会话计划",proposal));
            verify(client,times(2)).chatRaw(any(),any(),any(),eq("collaboration"));
        } finally {source.destroy();}
    }
    @Test void anUnavailableOrMalformedScopeReviewNeverAdmitsWork() throws Exception {
        var plans=mock(CollaborationPlan.class);var client=mock(OpenAiChatClient.class);var json=new ObjectMapper();
        when(plans.planningContext("s")).thenReturn("本次仅做技术选型");when(plans.workEvidence("s")).thenReturn("");
        when(client.chatRaw(any(),any(),any(),anyString())).thenReturn(new OpenAiChatClient.RawResult(json.createObjectNode().put("content","普通建议不是复核结果"),"raw"));
        var guard=new CollaborationScopeGuard(plans,mock(SettingsService.class),client,json);
        assertTrue(guard.check("s","TODO","实现游戏").contains("未创建或派发"));
        verify(plans,never()).scopeReview(anyString(),anyString(),anyBoolean(),anyString());
    }
    @Test void aChangedGoalDuringModelReviewCannotPersistAnOldApproval() {
        var plans=mock(CollaborationPlan.class);var client=mock(OpenAiChatClient.class);var json=new ObjectMapper();
        var context=new java.util.concurrent.atomic.AtomicReference<>("本次允许一个实验");
        when(plans.planningContext("s")).thenAnswer(i->context.get());when(plans.workEvidence("s")).thenReturn("");
        when(client.chatRaw(any(),any(),any(),anyString())).thenAnswer(i->{
            context.set("用户已暂停实验并调整目标");
            return new OpenAiChatClient.RawResult(json.createObjectNode().put("content","{\"allowed\":true,\"reason\":\"原目标允许\"}"),"raw");
        });
        var guard=new CollaborationScopeGuard(plans,mock(SettingsService.class),client,json);
        assertTrue(guard.check("s","具体 TODO","一个实验").contains("已经变化"));
        verify(plans,never()).scopeReview(anyString(),anyString(),anyBoolean(),anyString());
    }
}
