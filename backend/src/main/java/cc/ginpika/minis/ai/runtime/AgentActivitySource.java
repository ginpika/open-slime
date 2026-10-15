package cc.ginpika.minis.ai.runtime;

import java.util.List;

/** Read-only activity projection from specialized runtimes, scoped to the viewer. */
public interface AgentActivitySource {
    record Activity(String agentId, int pending, int doing, int done) {
        public boolean working() { return pending > 0 || doing > 0; }
    }

    /** Null session means overview; a missing owner must return no private activity. */
    List<Activity> activities(String owner, String sessionId);
}
