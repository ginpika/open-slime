package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import cc.ginpika.minis.agent.chat.ChatContextStages.Excerpt;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * R1/P3 · 续接从每会话一份迁移为每任务段一份：只有该任务段内成功完成的 Turn 才能发布任务段的续接，
 * 另一任务段的 Turn 不写入；回退（invalidateContextStage）使任务段续接一并失效。
 */
class ChatSceneContinuationTest {

    private static final String SESSION = "chat";
    private static final String AGENT = AgentDirectory.BUILTIN_ID;

    private final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final ObjectMapper om = new ObjectMapper();
    private final ChatMessageStore messages = new ChatMessageStore(jdbc);
    private final TurnStore turns = new TurnStore(jdbc);
    private final ChatTaskRunner tasks = new ChatTaskRunner(new TaskStore(jdbc));
    private final ChatContextStages stages = new ChatContextStages(jdbc, om);

    private long scene;
    private long otherScene;
    private long user;
    private long turn;

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO hub_session(id, mode, created_at) VALUES(?, 'ai', ?)", SESSION, now);
        scene = scene("跑团");
        otherScene = scene("排障");
        user = messages.insert(SESSION, "user", "继续旅程", null, null, null, null, null);
        long reply = messages.insert(SESSION, "assistant", "队伍继续前行", null, null, null, null, null);
        long task = tasks.begin(SESSION, AGENT, "跑团", "继续旅程");
        turn = turns.begin(SESSION, TurnTrigger.Source.USER_INPUT, user, task, scene);
        turns.complete(turn, reply);
        tasks.done(task, "队伍继续前行");
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void settlesPerSceneAndRejectsTurnsFromAnotherScene() {
        List<Excerpt> excerpts = List.of(new Excerpt("constraint", "message:" + user, 0, 2, "sha"));
        stages.settleScene(scene, SESSION, 0, turn, excerpts, 0);
        var state = stages.loadScene(scene, SESSION);
        assertNotNull(state);
        assertEquals(turn, state.stage().turnId());
        assertEquals(1, state.stage().excerpts().size());

        // 同一 Turn 不能写进别的任务段：该段仍无续接
        stages.settleScene(otherScene, SESSION, 0, turn, excerpts, 0);
        assertNull(stages.loadScene(otherScene, SESSION).stage());
    }

    @Test
    void rewindInvalidatesPerSceneContinuation() {
        stages.settleScene(scene, SESSION, 0, turn,
                List.of(new Excerpt("open", "message:" + user, 1, 1, "sha")), 0);
        assertNotNull(stages.loadScene(scene, SESSION).stage());

        new cc.ginpika.minis.agent.chat.store.HubSessionStore(jdbc).invalidateContextStage(SESSION);
        assertNull(stages.loadScene(scene, SESSION).stage());
    }

    private long scene(String name) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO chat_scene(session_id, name, status, created_at, updated_at) "
                + "VALUES(?,?, 'ended', ?, ?)", SESSION, name, now, now);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }
}
