package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.LlmProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in external acceptance: reads endpoint configuration only; all work stays in an in-memory fixture. */
@EnabledIfSystemProperty(named="minis.liveScope",matches="true")
class CollaborationScopeLiveTest {
    @Test void configuredModelSeparatesSelectionImplementationAndSingleDeliverables() throws Exception {
        Path database=Path.of("../data/minis.db").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(database),"需要已有模型配置数据库");
        var config=new org.sqlite.SQLiteConfig();config.setReadOnly(true);
        ModelTarget target;
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database,config.toProperties());
            var query=connection.createStatement();
            var rows=query.executeQuery("SELECT m.base_url,m.api_key,m.model FROM llm_model m JOIN coding_run r ON r.model_id=m.id ORDER BY r.created_at DESC LIMIT 1")) {
            assertTrue(rows.next(),"需要已使用的模型配置");
            target=new ModelTarget(rows.getString(1),rows.getString(2),rows.getString(3));
        }
        var settings=mock(SettingsService.class);when(settings.resolveChatModel(any())).thenReturn(target);
        var json=new ObjectMapper();
        var client=new OpenAiChatClient(new LlmProps(),settings,json,mock(TokenUsageService.class));
        var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            var jdbc=new JdbcTemplate(source);
            new ProjectStore(jdbc).insert("project",null,"卡牌塔防","software",false,"active","开发并持续迭代一款二次元卡牌塔防网页游戏");
            var sessions=new RoundtableSessionStore(jdbc);String session=sessions.insert("[\"controller\",\"worker\"]");
            sessions.touch(session,"确定游戏的技术选型和可行性");
            jdbc.update("UPDATE hub_session SET project_id='project' WHERE id=?",session);
            var plans=new CollaborationPlan(jdbc, new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc));plans.create(session,"controller");
            plans.revise(session,"技术比较、风险、最小实验有依据","先消除关键风险",new CollaborationPlan.Scope(
                    "技术比较与决策、风险清单、只针对关键假设的最小实验","完整游戏玩法、服务端、桌面封装",
                    "已实查当前仅有空工程，Node 可用，尚无游戏业务","后续会话再按结论建设原型"));
            var guard=new CollaborationScopeGuard(plans,settings,client,json);
            String broad=guard.check(session,"会话计划","本次必须搭建完整框架，实现三个可玩塔防切片、角色养成、3D家园、服务端和Electron桌面封装；全量端到端验收");
            assertFalse(broad.isEmpty(),"选型会话不能自动实施完整产品");
            System.out.println("LIVE_SCOPE selection-expansion: rejected");
            String narrow=guard.check(session,"具体 TODO","只验证固定时间步是否可行：在空工程编写纯函数时间累加器和Node边界测试，不含敌人、塔、防御和渲染；验证不同帧间隔下逻辑步数一致，记录结论");
            assertEquals("",narrow,"单一可行性假设应被准入："+narrow);
            System.out.println("LIVE_SCOPE minimal-feasibility-experiment: admitted");
            jdbc.update("INSERT INTO chat_message(session_id,role,content,created_at) VALUES(?,'user',?,1)",session,
                    "我明确将本次目标调整为实施首个塔防原型；仍应逐项搭建并验证，不要把多阶段塞进同一TODO。");
            plans.revise(session,"原型逐项通过测试","人类明确开始实施",new CollaborationPlan.Scope(
                    "首个塔防原型","正式服务端、养成、桌面封装","已有最小骨架与时间步实验通过；尚无玩法","后续再做扩展"));
            String bundle=guard.check(session,"具体 TODO","一次完成敌人路径移动、部署系统、阻挡、伤害、DP经济、胜负结算、渲染和交互端到端测试；此单项交付整个可玩原型");
            assertFalse(bundle.isEmpty(),"获准实施仍须拆分多个独立验收阶段");
            System.out.println("LIVE_SCOPE oversized-implementation-step: rejected");
            String step=guard.check(session,"具体 TODO","基于已有骨架，只编写路径距离到坐标的纯插值函数与边界测试：起点、拐点、终点、越界；不接入敌人状态、伤害、部署、渲染；以Node测试通过验收");
            assertEquals("",step,"已知基础上的一个独立产出应被准入："+step);
            System.out.println("LIVE_SCOPE single-bottom-up-step: admitted");
        } finally {source.destroy();}
    }
}
