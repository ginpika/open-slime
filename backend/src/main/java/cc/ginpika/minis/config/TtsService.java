package cc.ginpika.minis.config;

import cc.ginpika.minis.config.store.TtsStore;
import cc.ginpika.minis.config.dto.SettingsDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TtsService {
    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    public record Endpoint(String id, String name, String baseUrl, String apiKey) {}
    public record Profile(String id, String name, String endpointId, String model, String referenceId, ObjectNode parameters) {}
    public record View(List<Endpoint> endpoints, List<Profile> profiles, String defaultProfileId) {}
    private final TtsStore store;
    private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public TtsService(TtsStore store, ObjectMapper om) { this.store=store; this.om=om; }
    private Profile decode(String json) {
        try { return om.readValue(json, Profile.class); } catch (Exception e) { throw new IllegalStateException("TTS 档案数据无效", e); }
    }
    public View view() { return new View(endpoints(), store.profiles().stream().map(this::decode).toList(), store.selection("default")); }
    private List<Endpoint> endpoints() {
        return store.endpoints().stream().map(json -> {
            try { return om.readValue(json,Endpoint.class); } catch(Exception e) { throw new IllegalStateException("TTS 端点数据无效",e); }
        }).toList();
    }
    private Endpoint endpoint(String id) {
        return endpoints().stream().filter(e -> e.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"TTS API 端点不存在"));
    }
    @Transactional
    public View saveEndpoint(String id, Endpoint p) {
        require(p!=null && p.name()!=null && !p.name().isBlank() && p.apiKey()!=null && !p.apiKey().isBlank(),"请填写端点名称和 API Key");
        validateConnection(p);
        if(id!=null) endpoint(id);
        String actual=id==null ? UUID.randomUUID().toString() : id;
        try { store.saveEndpoint(actual,om.writeValueAsString(new Endpoint(actual,p.name().strip(),p.baseUrl().strip().replaceAll("/+$",""),p.apiKey().strip()))); }
        catch(Exception e) { throw new IllegalStateException(e); }
        return view();
    }
    private static void validateConnection(Endpoint p) {
        require(p!=null && p.apiKey()!=null && !p.apiKey().isBlank(),"请填写 API Key");
        try {
            URI uri=URI.create(p.baseUrl().strip());
            require(uri.getHost()!=null && List.of("https","http").contains(uri.getScheme()),"Base URL 无效");
        } catch(IllegalArgumentException | NullPointerException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Base URL 无效"); }
    }
    public SettingsDtos.TestView testEndpoint(String id) { return testEndpoint(endpoint(id)); }
    /** Tests connectivity and authentication without synthesis or modifying the saved configuration. */
    public SettingsDtos.TestView testEndpoint(Endpoint api) {
        validateConnection(api);
        long start=System.nanoTime();
        try {
            String base=api.baseUrl().strip().replaceAll("/+$", "");
            if(base.endsWith("/v1")) base=base.substring(0,base.length()-3);
            HttpRequest req=HttpRequest.newBuilder(URI.create(base+"/wallet/self/api-credit"))
                .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+api.apiKey().strip())
                .header("Accept","application/json").GET().build();
            var response=http.send(req,HttpResponse.BodyHandlers.ofString());
            int status=response.statusCode();
            if(status!=200) {
                String error=switch(status) {
                    case 401,403 -> "API Key 无效或没有访问权限";
                    case 404 -> "鉴权测试接口不存在，请检查 Base URL 是否指向 Fish Audio API";
                    case 429 -> "请求过于频繁，请稍后重试";
                    default -> "Fish Audio 测试失败";
                };
                return new SettingsDtos.TestView(false,elapsedMs(start),null,error+"（HTTP "+status+"）");
            }
            var body=om.readTree(response.body());
            if(body==null || !body.isObject() || !body.hasNonNull("credit"))
                return new SettingsDtos.TestView(false,elapsedMs(start),null,"响应格式无效，请检查 Base URL 是否指向 Fish Audio API");
            return new SettingsDtos.TestView(true,elapsedMs(start),"连接和 API Key 验证通过",null);
        } catch(HttpTimeoutException e) {
            return new SettingsDtos.TestView(false,elapsedMs(start),null,"超过 5s 未响应");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SettingsDtos.TestView(false,elapsedMs(start),null,"端点测试已中断");
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            return new SettingsDtos.TestView(false,elapsedMs(start),null,"响应不是有效 JSON，请检查 Base URL");
        } catch(Exception e) {
            return new SettingsDtos.TestView(false,elapsedMs(start),null,"连接失败，请检查 Base URL 或网络");
        }
    }
    private static long elapsedMs(long start) { return (System.nanoTime()-start)/1_000_000; }
    @Transactional
    public View deleteEndpoint(String id) {
        endpoint(id);
        require(view().profiles().stream().noneMatch(p -> id.equals(p.endpointId())),"该 API 端点仍被 TTS 档案引用，请先修改或删除相关档案");
        store.deleteEndpoint(id);
        return view();
    }
    public String selection(String agent) { return store.selection("agent:" + agent); }
    public ObjectNode defaults() {
        return om.createObjectNode().put("temperature",0.7).put("top_p",0.7).put("format","mp3")
            .put("chunk_length",300).put("normalize",true).put("latency","normal")
            .set("prosody", om.createObjectNode().put("speed",1).put("volume",0).put("normalize_loudness",true));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    @Transactional
    public View save(String id, Profile p) {
        require(p != null && p.name()!=null && !p.name().isBlank() && p.model()!=null && !p.model().isBlank() && p.referenceId()!=null && !p.referenceId().isBlank(), "请填写档案名称、合成模型和语音模型 ID");
        endpoint(p.endpointId());
        if (id!=null) require(store.exists(id), "TTS 档案不存在");
        ObjectNode params=defaults();
        if(p.parameters()!=null) params.setAll(p.parameters());
        require(!params.has("text") && !params.has("reference_id") && !params.has("references"), "参数不能覆盖文字或语音模型 ID");
        require(List.of("mp3","wav","opus").contains(params.path("format").asText()), "播放格式仅支持 mp3、wav、opus");
        for(String key:List.of("temperature","top_p")) require(params.path(key).isNumber() && params.path(key).asDouble()>=0 && params.path(key).asDouble()<=1,key+" 必须在 0 到 1 之间");
        String actual=id==null ? UUID.randomUUID().toString() : id;
        try { store.save(actual,om.writeValueAsString(new Profile(actual,p.name().strip(),p.endpointId(),p.model().strip(),p.referenceId().strip(),params))); }
        catch(Exception e) { throw new IllegalStateException(e); }
        if(view().defaultProfileId()==null) store.select("default",actual);
        return view();
    }
    @Transactional
    public View delete(String id) { store.delete(id); if(view().defaultProfileId()==null && !view().profiles().isEmpty()) store.select("default",view().profiles().getFirst().id()); return view(); }
    public void select(String agent,String id) { require(id==null || id.isBlank() || store.exists(id),"TTS 档案不存在"); store.select(agent,id); }
    public Profile resolve(String agent) {
        String chosen=selection(agent==null ? "builtin" : agent);
        String id=chosen==null ? view().defaultProfileId() : chosen;
        Profile profile=view().profiles().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"未配置 TTS 档案，请先在设置页配置"));
        log.info("TTS selection: agent={}, profile={}, model={}, referenceId={}",agent,profile.id(),profile.model(),profile.referenceId());
        return profile;
    }
    public byte[] speak(Profile p,String text) {
        require(text!=null && !text.isBlank() && text.length()<=20000,"朗读文字不能为空且不能超过 20000 字符");
        Endpoint api=endpoint(p.endpointId());
        try {
            ObjectNode body=p.parameters().deepCopy().put("text",text).put("reference_id",p.referenceId());
            String base=api.baseUrl();
            String endpoint=base.endsWith("/v1") ? base+"/tts" : base+"/v1/tts";
            HttpRequest req=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(120))
                .header("Authorization","Bearer "+api.apiKey()).header("model",p.model()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body))).build();
            HttpResponse<byte[]> response=http.send(req,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200) {
                String message=synthesisError(response.statusCode(),response.body(),api.apiKey());
                log.warn("Fish Audio synthesis failed: profile={}, model={}, upstreamStatus={}",p.id(),p.model(),response.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,message);
            }
            if(response.body().length==0) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Fish Audio 返回了空音频");
            String contentType=response.headers().firstValue("Content-Type").orElse("");
            if(contentType.toLowerCase(java.util.Locale.ROOT).contains("json"))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,synthesisError(200,response.body(),api.apiKey()));
            return response.body();
        } catch(ResponseStatusException e) { throw e; }
        catch(HttpTimeoutException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Fish Audio 合成超时（120s），请稍后重试或缩短朗读文字"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"语音合成已中断"); }
        catch(Exception e) {
            log.warn("Fish Audio request failed: profile={}, model={}, exception={}",p.id(),p.model(),e.getClass().getSimpleName());
            String reason=e instanceof javax.net.ssl.SSLException ? "TLS 连接失败，请检查证书或代理设置"
                : e instanceof java.net.ConnectException ? "无法连接，请检查网络或代理设置"
                : "请求失败，请检查配置或网络";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Fish Audio "+reason);
        }
    }
    private String synthesisError(int status,byte[] body,String key) {
        String hint=switch(status) {
            case 401,403 -> "API Key 无效或无权使用此模型";
            case 402 -> "余额不足或当前账号无法使用所选合成模型，请检查 API 额度与模型权限";
            case 404 -> "合成接口或语音模型不存在，请检查 Base URL 和 reference_id";
            case 400,422 -> "合成请求无效，请检查档案中的模型、reference_id 和参数 JSON";
            case 429 -> "请求过于频繁，请稍后重试";
            case 500,502,503,504 -> "合成服务暂时不可用，请稍后重试";
            default -> "未返回有效音频";
        };
        String detail="";
        try {
            var json=om.readTree(new String(body,StandardCharsets.UTF_8));
            if(json!=null && json.isObject()) {
                for(String field:List.of("message","reason","detail")) {
                    var value=json.get(field);
                    if(value==null || value.isNull()) continue;
                    String part=value.isTextual() ? value.asText() : value.toString();
                    if(!part.isBlank()) detail+=(detail.isBlank() ? "" : "；")+part;
                }
            }
        } catch(Exception ignored) { /* HTML proxy errors must not appear as raw markup. */ }
        if(key!=null && !key.isBlank()) detail=detail.replace(key,"[已隐藏]");
        detail=detail.replaceAll("(?i)Bearer\\s+[^\\s\"']+","Bearer [已隐藏]").replaceAll("\\s+"," ").strip();
        if(detail.length()>500) detail=detail.substring(0,500)+"…";
        return "Fish Audio 合成失败（HTTP "+status+"）："+hint+(detail.isBlank() ? "" : "。"+detail);
    }
}
