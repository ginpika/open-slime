package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.ai.knowledge.recall.RagRecallRecorder;
import cc.ginpika.minis.ai.knowledge.web.WebRetrievalService;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Independent web switch: remote-only evidence is useful even when local knowledge injection is off/empty. */
@Component
@Order(-9)
public class WebKnowledgeProvider implements ContextProvider {
    private final WebRetrievalService web;
    private final RagRecallRecorder recalls;
    public WebKnowledgeProvider(WebRetrievalService web, RagRecallRecorder recalls) { this.web = web; this.recalls = recalls; }

    @Override
    public List<ContextFragment> fragments(ContextRequest req) {
        return ContextFragment.evidence("web_knowledge", systemSuffix(req));
    }

    public String systemSuffix(ContextRequest req) {
        if (req.surface() != ContextRequest.Surface.CHAT || req.userQuery() == null || req.userQuery().isBlank()) return "";
        String text = WebRetrievalService.render(web.search(req.userQuery(), true));
        if (!text.isBlank()) recalls.recordWebInject(req.userQuery(), text, req.agentId());
        return text;
    }
}
