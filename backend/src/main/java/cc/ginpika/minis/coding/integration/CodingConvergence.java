package cc.ginpika.minis.coding.integration;

import cc.ginpika.minis.coding.api.CodingDtos.Run;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Detect repeated identical failed evidence, never stop useful work merely because attempts increased. */
public final class CodingConvergence {
    private final CodingStore store;
    private final ObjectMapper json;
    public CodingConvergence(CodingStore store,ObjectMapper json){this.store=store;this.json=json;}
    public static String digest(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((text==null?"":text.strip()).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    public void completed(Run run){
        String instruction=store.latestInstruction(run.id());
        String failure="FAILED".equals(run.validationStatus())?"VALIDATION_FAILED":
                "FAILED".equals(run.status())?String.valueOf(run.error()):"";
        var validation=store.validationEvidence(run.id());
        var diagnostic=json.createObjectNode();
        if(validation!=null) {
            diagnostic.set("command",validation.path("command"));diagnostic.set("error",validation.path("error"));
            diagnostic.set("exitCode",validation.path("process").path("exitCode"));diagnostic.set("output",validation.path("process").path("output"));
        }
        store.event(run.id(),"attempt.completed",Map.of("attempt",run.attempt(),"artifactHash",run.artifactHash()==null?"":run.artifactHash(),
                "instructionDigest",digest(instruction),"failure",failure,"validationHash",String.valueOf(store.validationHash(run.id())),"failureEvidence",digest(diagnostic.toString())));
    }
    public Map<String,Object> check(Run run,String input){
        if(input==null || input.isBlank() || run.artifactHash()==null || !store.pendingInputs(run.id()).isEmpty())return null;
        var evidence=store.recentEvidence(run.id());if(evidence.size()<2)return null;
        try{
            var a=json.readTree(evidence.get(0));var b=json.readTree(evidence.get(1));
            String hash=a.path("artifactHash").asText(),failure=a.path("failure").asText();
            if(hash.isBlank() || !hash.equals(run.artifactHash()) || failure.isBlank() || !digest(input).equals(a.path("instructionDigest").asText()))return null;
            for(String key:new String[]{"artifactHash","instructionDigest","failure","validationHash","failureEvidence"})
                if(!a.path(key).equals(b.path(key)))return null;
            return Map.of("code","NEEDS_DIAGNOSIS","runId",run.id(),"artifactHash",hash,
                    "reason","相同补充要求、相同产物和相同失败证据重复出现；请检查参数、权限、文件落点及失败原因后再派工。",
                    "failure",failure);
        }catch(Exception error){throw new IllegalStateException("历史执行证据不可读，请先诊断记录",error);}
    }
}
