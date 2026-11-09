package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.peer.AgentMessageConversation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 人类查看来源会话中的 Agent 往来，沿用会话接口的 SSO 门禁。 */
@RestController
public class AgentMessageConversationController {
    private final AgentMessageConversation conversations;

    public AgentMessageConversationController(AgentMessageConversation conversations) {
        this.conversations = conversations;
    }

    @GetMapping("/api/sessions/{sessionId}/agent-messages/{messageId}/conversation")
    public AgentMessageConversation.View conversation(@PathVariable String sessionId, @PathVariable long messageId) {
        return conversations.find(sessionId, messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "本会话中找不到该 Agent 消息。"));
    }
}
