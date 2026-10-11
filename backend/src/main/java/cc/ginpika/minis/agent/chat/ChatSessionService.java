package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.context.ChatProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 会话域：会话的增删改查、转录读取、回退，以及围绕它们的那几条规则。
 *
 * <p>从 {@link ChatService} 原样搬出（行为零变化）。之所以能切开，是因为这两件事本就没有依赖关系：
 * 这里不调模型、不跑工具、不参与循环，只碰会话与消息两张表；而循环也不需要知道「会话怎么列出来」。
 *
 * <p>规则集中在这里，不再散落于循环的各个调用点：
 * <ul>
 *   <li>配置的部分更新（仅更新请求里非 null 的项），轮数上限收敛到 {@link #MIN_MAX_ROUNDS}~{@link #MAX_MAX_ROUNDS}；</li>
 *   <li>回退只认「本会话的一条 user 提问」，回答或别的会话的 id 一律拒；</li>
 *   <li>打开会话即清未读。</li>
 * </ul>
 *
 * <p><b>刻意不含</b>：运行时状态（在途生成 / 停止标志，见 {@link ChatLiveStore}）与对话循环
 * （见 {@link ChatService}）。会话在这里是「事实」，不是「流程」。
 */
@Service
public class ChatSessionService {

    /** 工具往返轮数上限的合法区间：会话级可配（{@code hub_session.max_rounds}，「…」菜单），
     *  写入时收敛一下，避免手改请求把轮数设成 0（一轮都跑不了）或天文数字 */
    public static final int MIN_MAX_ROUNDS = 1;
    public static final int MAX_MAX_ROUNDS = 100;

    private final HubSessionStore sessions;
    private final ChatMessageStore messages;
    /** 列表要带上「该会话此刻是否在生成」，供前端在答完时弹通知 */
    private final ChatLiveStore live;
    private final ObjectMapper om;

    public ChatSessionService(HubSessionStore sessions, ChatMessageStore messages,
                              ChatLiveStore live, ObjectMapper om) {
        this.sessions = sessions;
        this.messages = messages;
        this.live = live;
        this.om = om;
    }

    /** 会话列表（最近在前）：带「此刻是否在生成」标记 */
    public List<ChatDtos.SessionItem> list() {
        return sessions.listAll().stream()
                .map(row -> toItem(row, live.isRunning(row.id())))
                .toList();
    }

    /** 新建一个空会话 */
    public ChatDtos.NewSession create() {
        String hubId = UUID.randomUUID().toString();
        sessions.insert(hubId, null);
        return new ChatDtos.NewSession(hubId);
    }

    /** 更新会话配置（「…」菜单在该会话的选择）：仅更新请求中非 null 的项，返回更新后的完整配置 */
    public ChatDtos.SessionConfig updateConfig(String hubId, ChatDtos.ConfigRequest req) {
        HubRow row = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        String modelId = req == null ? null : req.modelId();
        String promptId = req == null ? null : req.promptId();
        String agentId = req == null ? null : req.agentId();
        Boolean showCot = req == null ? null : req.showCot();
        Boolean contextTrace = req == null ? null : req.contextTrace();
        Integer maxRounds = req == null || req.maxRounds() == null ? null
                : Math.max(MIN_MAX_ROUNDS, Math.min(MAX_MAX_ROUNDS, req.maxRounds()));
        Boolean showRounds = req == null ? null : req.showRounds();
        sessions.updateConfig(row.id(), modelId, promptId, agentId, showCot, contextTrace, maxRounds,
                showRounds);
        return new ChatDtos.SessionConfig(row.id(),
                modelId != null ? modelId : row.modelId(),
                promptId != null ? promptId : row.promptId(),
                agentId != null ? (agentId.isBlank() ? null : agentId) : row.agentId(),
                showCot != null ? showCot : row.showCot(),
                contextTrace != null ? contextTrace : row.contextTrace(),
                maxRounds != null ? maxRounds : row.maxRounds(),
                showRounds != null ? showRounds : row.showRounds());
    }

    /** 删除会话：连同其消息正本一并清除（不存在的会话返回 404） */
    @Transactional
    public ChatDtos.SessionDeleted delete(String hubId) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        int removed = messages.deleteBySession(hubId);
        sessions.delete(hubId);
        return new ChatDtos.SessionDeleted(hubId, removed);
    }

    /** 历史：仅 user/assistant 行（system prompt 不入库、不回显）；assistant 带思维链与分段供回放；
     *  子代理结论气泡带 kind 与元信息（标题/摘要/状态/执行 id），content 仍是完整结论（点开看全文）。
     *  打开会话即清未读。 */
    public List<ChatDtos.MessageRow> messages(String hubId) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        sessions.clearUnread(hubId);
        return messages.listBySession(hubId).stream()
                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                .map(m -> new ChatDtos.MessageRow(m.id(), m.role(), m.content(), m.reasoning(),
                        ChatTraceCodec.readTrace(om, m.trace()), m.kind(),
                        ChatProtocol.readSubagentBrief(om, m.kind(), m.meta()),
                        iso(m.createdAt())))
                .toList();
    }

    /**
     * 回退到某条提问之前：删除该条（含）及其后的全部消息，正本与上下文一并回到提问前，
     * 该条提问由前端放回输入框的未发送状态。
     *
     * <p>定位：messageId 优先；缺省时用 keep=保留的消息条数（发送失败时前端尚无落库 id）。
     */
    public ChatDtos.Rewind rewind(String hubId, Long messageId, Integer keep) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        List<MessageRow> rows = messages.listBySession(hubId);
        MessageRow target;
        if (messageId != null) {
            target = rows.stream().filter(m -> messageId.equals(m.id())).findFirst().orElse(null);
        } else if (keep != null && keep >= 0 && keep < rows.size()) {
            target = rows.get(keep);
        } else {
            target = null;
        }
        /* 只认本会话（rows 即本会话）的一条提问：拿回答或别的会话的 id 一律拒绝，避免误删整段历史 */
        if (target == null || !"user".equals(target.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "回退位置无效（需为本会话的一条提问）: messageId=" + messageId + ", keep=" + keep);
        }
        return new ChatDtos.Rewind(hubId, messages.deleteFrom(hubId, target.id()));
    }

    private static ChatDtos.SessionItem toItem(HubRow row, boolean generating) {
        return new ChatDtos.SessionItem(row.id(), row.title(), row.modelId(), row.promptId(), row.agentId(),
                row.showCot(), row.contextTrace(), row.maxRounds(), row.showRounds(), row.unread(),
                generating, iso(row.updatedAt() != null ? row.updatedAt() : row.createdAt()));
    }

    private static String iso(Long ms) {
        return ms == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString();
    }
}
