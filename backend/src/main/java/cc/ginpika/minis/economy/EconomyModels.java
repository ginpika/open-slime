package cc.ginpika.minis.economy;

import java.util.List;

/** The three immutable evidence steps and their submitted, versioned conclusion. */
public final class EconomyModels {
    private EconomyModels() {}
    public static final List<String> TIMEFRAMES = List.of("weekly", "daily", "intraday");
    public static final String STRATEGY = "close-open-v1";

    public record NewStudy(String kind, String symbol, String tradeDate) {}
    public record Study(String id, String instrumentId, String kind, String symbol, String tradeDate, long createdAt) {}
    public record CaptureInput(String timeframe, String agentId, String browserId, String screenshot,
                               Long capturedAt, String sourceTitle, String notes) {}
    public record StepAnalysis(boolean sufficientEvidence, String summary, String technical, String risks) {}
    public record Observation(String id, String studyId, String timeframe, long capturedAt, long createdAt,
                              String sourceTitle, String sourceUrl, String imageHash, String agentId,
                              String agentName, String model, String notes, String status,
                              StepAnalysis analysis, String error) {}
    public record ImageData(String mimeType, byte[] bytes) {
        public String dataUrl() { return "data:" + mimeType + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes); }
    }
    public record SubmitInput(String agentId, List<String> observationIds, String notes, boolean fundamentals) {}
    public record Factor(String name, int points, int maximum, String reason) {}
    public record Report(String id, String studyId, String instrumentId, String kind, String symbol,
                         String tradeDate, String strategy, List<String> observationIds, String agentId,
                         String agentName, String model, long analyzedAt, boolean sufficientEvidence,
                         String entryDecision, String overnightDecision, Integer score, Integer stage,
                         String summary, List<Factor> factors, String technical, String fundamentals,
                         String conditions, String exitPlan, String risks, String retrievalStatus) {}
    public record StudyDetail(Study study, List<Observation> observations, List<Report> reports) {}
    public record StudySummary(Study study, int completedSteps, int observationCount,
                               int reportCount, String latestSummary, Integer latestScore) {}
    public record StudyPage(List<StudySummary> items, long total, int page, int pageSize) {}
}
