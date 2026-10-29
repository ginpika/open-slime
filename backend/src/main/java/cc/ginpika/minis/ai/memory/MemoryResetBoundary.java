package cc.ginpika.minis.ai.memory;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Content-free reset boundary. It survives cursor cleanup so old asynchronous work cannot repopulate memory. */
public record MemoryResetBoundary(long generation, long at, long chatCutoff, long taskCutoff) {
    public static MemoryResetBoundary read(JdbcTemplate jdbc) {
        Map<String,Long> values=new HashMap<>();
        jdbc.query("SELECT session_id,last_event_id FROM memory_cursor WHERE session_id LIKE 'reset:%'",rs->{
            while(rs.next()) values.put(rs.getString(1),rs.getLong(2)); return null;
        });
        return new MemoryResetBoundary(values.getOrDefault("reset:generation",0L),values.getOrDefault("reset:at",0L),
                values.getOrDefault("reset:chat",0L),values.getOrDefault("reset:task",0L));
    }
    public boolean allows(String refs) {
        if(generation==0) return true;
        var sources=MemoryEvidence.references(refs);
        boolean freshChat=sources.stream().anyMatch(ref->id(ref,"chat_message:")>chatCutoff);
        for(String ref:sources) {
            long chat=id(ref,"chat_message:"),task=id(ref,"task:");
            if(chat>0 && chat<=chatCutoff) return false;
            // Session tasks can be reused by a new user message; fresh message evidence takes precedence.
            if(task>0 && task<=taskCutoff && !freshChat) return false;
        }
        return true;
    }
    private static long id(String ref,String prefix) {
        try { return ref.startsWith(prefix)?Long.parseLong(ref.substring(prefix.length())):0; }
        catch(NumberFormatException e) { return 0; }
    }
}
