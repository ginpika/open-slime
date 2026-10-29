package cc.ginpika.minis.coding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minis.coding")
public class CodingProps {
    private boolean enabled = true;
    private String dataDir = "";
    private String dockerHostDataDir = "";
    private int maxFiles = 20_000;
    private long maxWorkspaceBytes = 256L * 1024 * 1024;
    private int maxFileBytes = 2 * 1024 * 1024;
    private int maxOutputChars = 40_000;
    private int maxProcessSeconds = 600;
    private int maxRunMinutes = 30;
    private int maxConcurrentRuns = 2;
    private int maxConcurrentSubagents = 2;
    private long maxEstimatedTokens = 0;
    private int maxResponseTokens = 0;
    private int responseReserveTokens = 32_768;
    private int maxOutputContinuations = 3;
    private int maxModelRetries = 3;
    private String dockerImage = "maven:3.9-eclipse-temurin-21";
    private boolean allowLocalExecution = false;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String value) { dataDir = value; }
    public String getDockerHostDataDir() { return dockerHostDataDir; }
    public void setDockerHostDataDir(String value) { dockerHostDataDir=value; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int value) { maxFiles = value; }
    public long getMaxWorkspaceBytes() { return maxWorkspaceBytes; }
    public void setMaxWorkspaceBytes(long value) { maxWorkspaceBytes = value; }
    public int getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(int value) { maxFileBytes = value; }
    public int getMaxOutputChars() { return maxOutputChars; }
    public void setMaxOutputChars(int value) { maxOutputChars = value; }
    public int getMaxProcessSeconds() { return Math.max(1, maxProcessSeconds); }
    public void setMaxProcessSeconds(int value) { maxProcessSeconds = value; }
    public int getMaxRunMinutes() { return Math.max(1, maxRunMinutes); }
    public void setMaxRunMinutes(int value) { maxRunMinutes = value; }
    public int getMaxConcurrentRuns() { return Math.max(1, maxConcurrentRuns); }
    public void setMaxConcurrentRuns(int value) { maxConcurrentRuns = value; }
    public int getMaxConcurrentSubagents(){return Math.max(1,Math.min(8,maxConcurrentSubagents));}
    public void setMaxConcurrentSubagents(int value){maxConcurrentSubagents=value;}
    /** Zero disables the cumulative token cap; the model context window and deadlines still apply. */
    public long getMaxEstimatedTokens() { return Math.max(0,maxEstimatedTokens); }
    public void setMaxEstimatedTokens(long value) { maxEstimatedTokens=value; }
    /** Zero leaves the per-response output limit to the provider. Independent of total run budget. */
    public int getMaxResponseTokens() { return Math.max(0,Math.min(1_000_000,maxResponseTokens)); }
    public void setMaxResponseTokens(int value) { maxResponseTokens=value; }
    public int getResponseReserveTokens() { return Math.max(512,responseReserveTokens); }
    public void setResponseReserveTokens(int value) { responseReserveTokens=value; }
    public int getMaxOutputContinuations() { return Math.max(0,Math.min(10,maxOutputContinuations)); }
    public void setMaxOutputContinuations(int value) { maxOutputContinuations=value; }
    public int getMaxModelRetries(){return Math.max(0,Math.min(10,maxModelRetries));}
    public void setMaxModelRetries(int value){maxModelRetries=value;}
    public String getDockerImage() { return dockerImage; }
    public void setDockerImage(String value) { dockerImage = value; }
    public boolean isAllowLocalExecution() { return allowLocalExecution; }
    public void setAllowLocalExecution(boolean value) { allowLocalExecution = value; }
}
