package cc.ginpika.minis.stats;

import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.stats.dto.StatsDtos;
import cc.ginpika.minis.stats.store.TokenUsageStore;
import cc.ginpika.minis.stats.store.TokenUsageStore.PriceRow;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TokenUsageService {
    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_ANALYZE = "analyze";
    public static final String SOURCE_COMMIT = "commit";
    public static final String SOURCE_DOC_SUMMARY = "doc-summary";
    public static final String SOURCE_CHUNK_QUESTIONS = "chunk-questions";
    public static final String SOURCE_CHUNK_SUMMARY = "chunk-summary";
    public static final String SOURCE_CHUNKER = "chunker";
    public static final String SOURCE_HEARTBEAT = "heartbeat";
    public static final String SOURCE_EMBEDDING = "embedding";
    public static final String SOURCE_RERANK = "rerank";
    public static final String SOURCE_MODEL_TEST = "model-test";

    /** reasoningTokens 已包含在输出里，cachedTokens 已包含在输入里，均不再加到 total。 */
    public record Usage(long promptTokens, long completionTokens, long totalTokens,
                        boolean estimated, String quality, long cachedTokens, long reasoningTokens) {
        public Usage(long prompt, long completion, long total, boolean estimated) {
            this(Math.max(0,prompt), Math.max(0,completion), Math.max(Math.max(0,total),Math.max(0,prompt)+Math.max(0,completion)),
                    estimated, estimated ? "estimated" : total == prompt+completion ? "reported" : "partial", 0, 0);
        }
        public static Usage real(long prompt, long completion, long total) {
            return new Usage(prompt, completion, total > 0 ? total : prompt+completion, false);
        }
        public static Usage estimated(long prompt, long completion) {
            return new Usage(prompt, completion, prompt+completion, true);
        }
        public Usage partial() {
            return new Usage(promptTokens, completionTokens, totalTokens, estimated, "partial", cachedTokens, reasoningTokens);
        }
        /** 缺失字段仍保留为不完整；只有明确的纯输入接口可用 total 回填输入。 */
        public static Usage reported(Long input, Long output, Long total, boolean inputOnly,
                                     long cached, long reasoning) {
            if (input == null && output == null && total == null) return null;
            boolean invalid = input != null && input < 0 || output != null && output < 0
                    || total != null && total < 0 || cached < 0 || reasoning < 0;
            if (inputOnly && input == null && total != null) input = total;
            if (inputOnly) output = 0L;
            long p = input == null ? 0 : Math.max(0,input), c = output == null ? 0 : Math.max(0,output);
            long t = total == null ? p+c : Math.max(0,total);
            boolean complete = !invalid && input != null && output != null && t == p+c
                    && cached <= p && reasoning <= c;
            return new Usage(p,c,Math.max(t,p+c),false,complete ? "reported" : "partial",
                    Math.max(0,Math.min(p,cached)),Math.max(0,Math.min(c,reasoning)));
        }
    }

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private final TokenUsageStore store;
    private final Clock clock;
    private final AtomicLong recordingFailures = new AtomicLong();

    @Autowired
    public TokenUsageService(TokenUsageStore store) { this(store, Clock.systemDefaultZone()); }
    TokenUsageService(TokenUsageStore store, Clock clock) { this.store=store; this.clock=clock; }

    /** 兼容旧调用方；无法追溯供应商的历史记录不猜测端点。 */
    public void record(String source, String model, Usage usage) { record(source, "", model, usage, "success"); }
    public void record(String source, ModelTarget target, Usage usage, String status) {
        record(source, providerOf(target.baseUrl()), target.model(), usage, status);
    }
    private void record(String source, String provider, String model, Usage usage, String status) {
        if (usage == null) return;
        try {
            store.insert(source, provider, model == null ? "" : model, usage.promptTokens(),
                    usage.completionTokens(), usage.totalTokens(), usage.estimated(), usage.quality(),
                    status, usage.cachedTokens(), usage.reasoningTokens());
        } catch (Exception e) {
            recordingFailures.incrementAndGet();
            log.warn("token 用量落库失败：source={} model={} error={}",source,model,e.getMessage());
        }
    }

    /** 不保存 API key、URL 用户信息、query 或 fragment。 */
    public static String providerOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "";
        try {
            URI uri = URI.create(baseUrl.strip());
            if (uri.getHost() == null || !Set.of("http","https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) return "";
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT),null,uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort(),uri.getPath().replaceAll("/+$",""),null,null).toASCIIString();
        } catch (Exception ignored) { return ""; }
    }

    public StatsDtos.StatsView view(int days) { return view(days,null,null,null,null,null); }

    @Transactional(readOnly=true)
    public StatsDtos.StatsView view(int days, LocalDate from, LocalDate to, String source, String provider, String model) {
        LocalDate today = LocalDate.now(clock);
        if ((from == null) != (to == null)) throw bad("开始日期和结束日期必须同时填写");
        if (from == null) {
            to=today;
            from=days == -1 ? today.withDayOfMonth(1) : today.minusDays(Math.min(days <= 0 ? 30 : days,365)-1L);
        }
        long count=ChronoUnit.DAYS.between(from,to)+1;
        if (from.isBefore(LocalDate.of(1970,1,1)) || count<1 || count>366 || to.isAfter(today))
            throw bad("日期范围需在 1970 年至今天之间，且不超过 366 天");
        source=blankToNull(source);
        // 空模型名仍是合法的“未记录”筛选项，null 才代表全部。
        provider=provider == null ? "" : provider;
        var all=store.aggregates(from,to,clock.getZone());
        var sources=all.stream().map(TokenUsageStore.Aggregate::source).distinct().sorted().toList();
        var models=all.stream().map(a->new StatsDtos.Model(a.provider(),a.model())).distinct()
                .sorted(Comparator.comparing(StatsDtos.Model::model).thenComparing(StatsDtos.Model::provider)).toList();
        var total=new Accumulator();
        var bySource=new LinkedHashMap<String,Accumulator>();
        var byModel=new LinkedHashMap<StatsDtos.Model,Accumulator>();
        var daily=new LinkedHashMap<String,Accumulator>();
        for (LocalDate day=from; !day.isAfter(to); day=day.plusDays(1)) daily.put(day.toString(),new Accumulator());
        for (var a:all) {
            if (source != null && !source.equals(a.source())) continue;
            if (model != null && (!model.equals(a.model()) || !provider.equals(a.provider()))) continue;
            total.add(a);
            bySource.computeIfAbsent(a.source(),k->new Accumulator()).add(a);
            byModel.computeIfAbsent(new StatsDtos.Model(a.provider(),a.model()),k->new Accumulator()).add(a);
            daily.get(a.day()).add(a);
        }
        var prices=store.prices();
        var calls=store.recent(from.atStartOfDay(clock.getZone()).toInstant().toEpochMilli(),
                to.plusDays(1).atStartOfDay(clock.getZone()).toInstant().toEpochMilli(),source,provider,model)
                .stream().map(r->{
                    var price=prices.stream().filter(p->p.provider().equals(r.provider()) && p.model().equals(r.model())
                            && p.effectiveFrom()<=r.createdAt()).findFirst().orElse(null);
                    BigDecimal cost=cost(price,r.quality(),r.status(),r.promptTokens(),r.completionTokens(),
                            r.totalTokens(),r.cachedTokens());
                    return new StatsDtos.Call(r.id(),r.createdAt(),r.source(),r.provider(),r.model(),r.promptTokens(),
                            r.completionTokens(),r.totalTokens(),r.quality(),r.status(),r.cachedTokens(),r.reasoningTokens(),
                            cost == null ? null : price.currency(),cost);
                }).toList();
        return new StatsDtos.StatsView(total.totals(),
                bySource.entrySet().stream().map(e->e.getValue().bucket(e.getKey(),"","")).sorted(BY_TOKENS).toList(),
                byModel.entrySet().stream().map(e->e.getValue().bucket(e.getKey().provider()+"\n"+e.getKey().model(),
                        e.getKey().provider(),e.getKey().model())).sorted(BY_TOKENS).toList(),
                daily.entrySet().stream().map(e->e.getValue().bucket(e.getKey(),"","")).toList(),
                (int)count,from.toString(),to.toString(),clock.getZone().getId(),today.toString(),clock.millis(),
                sources,models,prices.stream().map(p->new StatsDtos.Price(p.id(),p.provider(),p.model(),
                java.time.Instant.ofEpochMilli(p.effectiveFrom()).atZone(clock.getZone()).toLocalDate().toString(),
                p.currency(),p.input(),p.output(),p.cached())).toList(),calls,recordingFailures.get());
    }

    @Transactional
    public void savePrice(StatsDtos.PriceRequest request) {
        if (request == null) throw bad("请填写价格");
        String provider=blankToNull(request.provider());
        if (provider != null && provider.length()>2000) throw bad("供应商端点不能超过 2000 字符");
        if (provider != null) {
            provider=providerOf(provider);
            if (provider.isEmpty()) throw bad("供应商端点需为有效的 http/https 地址");
        } else provider=""; // 明确允许给供应商未知的历史记录单独定价
        String model=blankToNull(request.model());
        if (model == null || model.length()>300) throw bad("请填写不超过 300 字符的模型名");
        String currency=request.currency() == null ? "" : request.currency().strip().toUpperCase(Locale.ROOT);
        try { Currency.getInstance(currency); } catch (Exception e) { throw bad("请填写有效币种，例如 CNY 或 USD"); }
        validatePrice(request.inputPerMillion(),false);
        validatePrice(request.outputPerMillion(),false);
        validatePrice(request.cachedPerMillion(),true);
        LocalDate date;
        try { date=LocalDate.parse(request.effectiveFrom()); } catch (Exception e) { throw bad("请填写有效的价格生效日期"); }
        if (date.isBefore(LocalDate.of(1970,1,1))) throw bad("价格生效日期不能早于 1970 年");
        store.savePrice(provider,model,date.atStartOfDay(clock.getZone()).toInstant().toEpochMilli(),
                currency,request.inputPerMillion(),request.outputPerMillion(),request.cachedPerMillion());
    }

    @Transactional
    public void deletePrice(long id) {
        if (!store.deletePrice(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"价格版本不存在");
    }

    private static void validatePrice(BigDecimal price,boolean optional) {
        if (price==null && optional) return;
        if (price==null || price.signum()<0 || price.compareTo(new BigDecimal("1000000000"))>0 || price.scale()>8)
            throw bad("价格需为 0 至 10 亿的数字，最多 8 位小数；留空不代表免费");
    }
    private static String blankToNull(String s) { return s==null || s.isBlank() ? null : s.strip(); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static final Comparator<StatsDtos.Bucket> BY_TOKENS=Comparator.comparingLong(StatsDtos.Bucket::totalTokens).reversed();

    /** 部分/失败记录不计价；缓存单价未知时也不把缓存当成普通输入收费。 */
    private static BigDecimal cost(PriceRow p,String quality,String status,long input,long output,long total,long cached) {
        if (p==null || "partial".equals(quality) || !"success".equals(status) || input+output!=total
                || cached>input || cached>0 && p.cached()==null) return null;
        return p.input().multiply(BigDecimal.valueOf(input-cached))
                .add(p.output().multiply(BigDecimal.valueOf(output)))
                .add(cached>0 ? p.cached().multiply(BigDecimal.valueOf(cached)) : BigDecimal.ZERO).divide(MILLION);
    }
    private static final class Accumulator {
        long input,output,total,calls,reported,estimated,partial,unsuccessful,cached,reasoning,unpriced;
        final Map<String,BigDecimal> costs=new TreeMap<>();
        void add(TokenUsageStore.Aggregate a) {
            input+=a.promptTokens(); output+=a.completionTokens(); total+=a.totalTokens(); calls+=a.calls();
            cached+=a.cachedTokens(); reasoning+=a.reasoningTokens();
            switch(a.quality()) { case "reported"->reported+=a.calls(); case "estimated"->estimated+=a.calls(); default->partial+=a.calls(); }
            if (!"success".equals(a.status())) unsuccessful+=a.calls();
            var amount=cost(a.price(),a.quality(),a.status(),a.promptTokens(),a.completionTokens(),a.totalTokens(),a.cachedTokens());
            if (amount==null) unpriced+=a.calls();
            else costs.merge(a.price().currency(),amount,BigDecimal::add);
        }
        List<StatsDtos.Money> money() { return costs.entrySet().stream().map(e->new StatsDtos.Money(e.getKey(),e.getValue())).toList(); }
        StatsDtos.Totals totals() { return new StatsDtos.Totals(input,output,total,calls,reported,estimated,partial,unsuccessful,cached,reasoning,unpriced,money()); }
        StatsDtos.Bucket bucket(String key,String provider,String model) { return new StatsDtos.Bucket(key,provider,model,input,output,total,calls,unpriced,money()); }
    }

    public static long estimateTokens(String text) {
        if (text==null || text.isEmpty()) return 0;
        int cjk=0;
        for(int i=0;i<text.length();i++) if(text.charAt(i)>=0x3400 && text.charAt(i)<=0x9FFF) cjk++;
        return cjk+(long)Math.ceil((text.length()-cjk)/4.0);
    }
}
