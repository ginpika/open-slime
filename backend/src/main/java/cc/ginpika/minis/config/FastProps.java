package cc.ginpika.minis.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** FAST 检索参数（prefix: minis.fast） */
@ConfigurationProperties(prefix = "minis.fast")
public class FastProps {

    private int keywordTopK = 20;
    private int vectorTopK = 20;
    private int graphHops = 2;
    private int rrfK = 60;
    /** RAG 精排候选池：RRF 后送 rerank 的前 N 条（未配置 rerank 端点时无效） */
    private int rerankCandidate = 20;
    private Web web = new Web();

    public int getKeywordTopK() {
        return keywordTopK;
    }

    public void setKeywordTopK(int keywordTopK) {
        this.keywordTopK = keywordTopK;
    }

    public int getVectorTopK() {
        return vectorTopK;
    }

    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    public int getGraphHops() {
        return graphHops;
    }

    public void setGraphHops(int graphHops) {
        this.graphHops = graphHops;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public int getRerankCandidate() {
        return rerankCandidate;
    }

    public void setRerankCandidate(int rerankCandidate) {
        this.rerankCandidate = rerankCandidate;
    }

    public Web getWeb() {
        return web;
    }

    public void setWeb(Web web) {
        this.web = web;
    }

    /** FAST 联网补充段（默认关；M0 不实现请求，仅占位） */
    public static class Web {
        private boolean enabled = false;
        private int topK = 10;
        private Duration cacheTtl = Duration.ofMinutes(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }
    }
}
