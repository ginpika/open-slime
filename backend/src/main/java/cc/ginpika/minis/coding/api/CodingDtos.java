package cc.ginpika.minis.coding.api;

import java.util.List;

public final class CodingDtos {
    private CodingDtos() {}
    public enum Mode { PLAN, EDIT, EXECUTE }
    public enum Runner { DOCKER, LOCAL, WORKSPACE }
    public record Workspace(String id, String name, String kind, boolean enabled, String workdir) {}
    public record OpenProject(String workspaceId, String directory, String name, boolean create, String validationCommand) {}
    public record CodingProject(String id, String name, String workspaceId, String workspaceName,
                                String kind, String directory, String validationCommand, boolean enabled, long createdAt, boolean recentHidden) {
        public CodingProject(String id,String name,String workspaceId,String workspaceName,String kind,String directory,String validationCommand,boolean enabled,long createdAt){this(id,name,workspaceId,workspaceName,kind,directory,validationCommand,enabled,createdAt,false);}
    }
    public record RegisterRepository(String name, String path, String projectId, String validationCommand) {}
    /** maxRounds is an ignored legacy field retained for older clients. */
    public record StartRun(String repositoryId, String prompt, String modelId, Mode mode, Runner runner, Integer maxRounds, String sessionId, Boolean autoReview) {
        public StartRun(String repositoryId,String prompt,String modelId,Mode mode,Runner runner,Integer maxRounds,String sessionId){this(repositoryId,prompt,modelId,mode,runner,maxRounds,sessionId,null);}
        public StartRun(String repositoryId,String prompt,String modelId,Mode mode,Runner runner,Integer maxRounds){this(repositoryId,prompt,modelId,mode,runner,maxRounds,null);}
    }
    public record ResumeRun(String input, boolean acknowledgeUnknownOperations, Mode mode, Boolean autoReview) {
        public ResumeRun(String input,boolean acknowledgeUnknownOperations,Mode mode){this(input,acknowledgeUnknownOperations,mode,null);}
        public ResumeRun(String input,boolean acknowledgeUnknownOperations){this(input,acknowledgeUnknownOperations,null);}
    }
    public record Session(String id,String repositoryId,String title,String lastRunId,long createdAt,long updatedAt) {}
    public record Role(String id,String name,String description,String modelId,String instructions,int version,List<String> tools) {}
    public record RoleConfig(String modelId,String instructions) {}
    public record Child(String id,String runId,String role,String title,String prompt,String modelId,String status,int attempt,int round,
                        String answer,String error,String baseHash,String finalHash,int configVersion,long createdAt,long updatedAt) {}
    public record AcceptChanges(String artifactHash) {}
    public record Repository(String id, String ownerId, String name, String path, String projectId,
                             String validationCommand, long createdAt) {}
    public record Run(String id, long taskId, String ownerId, String repositoryId, String prompt,
                      String modelId, Mode mode, Runner runner, String status, int attempt, int round,
                      String answer, String error, String validationStatus, String artifactHash,
                      long createdAt, long updatedAt,String sessionId) {
        public Run(String id,long taskId,String ownerId,String repositoryId,String prompt,String modelId,Mode mode,Runner runner,String status,int attempt,int round,
                   String answer,String error,String validationStatus,String artifactHash,long createdAt,long updatedAt){this(id,taskId,ownerId,repositoryId,prompt,modelId,mode,runner,status,attempt,round,answer,error,validationStatus,artifactHash,createdAt,updatedAt,null);}
    }
    public record Event(long id, String runId, String type, String payload, long createdAt) {}
    public record Change(String path, String kind, String beforeHash, String afterHash, long size, boolean binary, boolean executable) {}
    public record Diff(String artifactHash, List<Change> changes, String patch, boolean truncated) {}
    public record Agent(String id, String name, String modelId, String avatar) {
        public Agent(String id, String name, String modelId) { this(id, name, modelId, null); }
    }
    /** maxRounds is always zero: tool use has no round limit. */
    public record Capabilities(boolean enabled, boolean localExecutionAllowed, String dockerImage,
                               int maxRounds, int maxRunMinutes, String isolation, Agent agent) {
        public Capabilities(boolean enabled, boolean localExecutionAllowed, String dockerImage,
                            int maxRounds, int maxRunMinutes, String isolation) {
            this(enabled, localExecutionAllowed, dockerImage, maxRounds, maxRunMinutes, isolation, null);
        }
    }
    public record Operation(long id, String runId, int attempt, String tool, String args,
                            String status, String result, long startedAt, Long finishedAt) {}
}
