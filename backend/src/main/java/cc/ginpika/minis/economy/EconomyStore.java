package cc.ginpika.minis.economy;

import static cc.ginpika.minis.economy.EconomyModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EconomyStore {
    private static final String STUDY = "SELECT s.id,s.instrument_id,i.kind,i.symbol,s.trade_date,s.created_at FROM economy_study s JOIN economy_instrument i ON i.id=s.instrument_id ";
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    public EconomyStore(JdbcTemplate jdbc, ObjectMapper om) { this.jdbc=jdbc; this.om=om; }

    @Transactional
    public Study create(String owner, NewStudy input) {
        if(input==null)throw new IllegalArgumentException("请填写观测标的和日期");
        String symbol=EconomyService.text(input.symbol(),160,"标的");
        if(symbol.isBlank() || !Set.of("market","stock").contains(Objects.toString(input.kind(),""))) throw new IllegalArgumentException("请选择大盘/个股并填写标的");
        String date=EconomyService.tradeDate(input.tradeDate());
        long now=System.currentTimeMillis();
        String key=symbol.toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO economy_instrument(id,owner,kind,symbol,symbol_key,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(owner,kind,symbol_key) DO NOTHING",
                UUID.randomUUID().toString(),owner,input.kind(),symbol,key,now);
        String instrument=jdbc.queryForObject("SELECT id FROM economy_instrument WHERE owner=? AND kind=? AND symbol_key=?",String.class,owner,input.kind(),key);
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO economy_study(id,owner,instrument_id,trade_date,created_at) VALUES(?,?,?,?,?)",id,owner,instrument,date,now);
        return study(owner,id);
    }
    public Study study(String owner,String id) {
        return jdbc.query(STUDY+"WHERE s.owner=? AND s.id=?",EconomyStore::studyRow,owner,id).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("观测记录不存在或不属于当前用户"));
    }
    private static Study studyRow(ResultSet rs,int row) throws SQLException {
        return new Study(rs.getString("id"),rs.getString("instrument_id"),rs.getString("kind"),rs.getString("symbol"),rs.getString("trade_date"),rs.getLong("created_at"));
    }
    public StudyDetail detail(String owner,String id) {
        Study study=study(owner,id);
        var observations=jdbc.query("SELECT * FROM economy_observation WHERE owner=? AND study_id=? ORDER BY created_at DESC,rowid DESC",this::observationRow,owner,id);
        var reports=jdbc.query("SELECT body FROM economy_evaluation WHERE owner=? AND study_id=? ORDER BY created_at DESC,rowid DESC",(rs,n)->read(rs.getString(1),Report.class),owner,id);
        return new StudyDetail(study,observations,reports);
    }
    public Observation observation(String owner,String id) {
        return jdbc.query("SELECT * FROM economy_observation WHERE owner=? AND id=?",this::observationRow,owner,id).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("截图记录不存在或不属于当前用户"));
    }
    private Observation observationRow(ResultSet rs,int n) throws SQLException {
        String analysis=rs.getString("analysis");
        return new Observation(rs.getString("id"),rs.getString("study_id"),rs.getString("timeframe"),rs.getLong("captured_at"),rs.getLong("created_at"),
                rs.getString("source_title"),rs.getString("source_url"),rs.getString("image_hash"),rs.getString("agent_id"),rs.getString("agent_name"),
                rs.getString("model"),rs.getString("notes"),rs.getString("status"),analysis==null?null:read(analysis,StepAnalysis.class),rs.getString("error"));
    }
    public ImageData image(String owner,String id) {
        return jdbc.query("SELECT mime_type,image FROM economy_observation WHERE owner=? AND id=?",(rs,n)->new ImageData(rs.getString(1),rs.getBytes(2)),owner,id)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("截图不存在或不属于当前用户"));
    }
    public Observation insertObservation(String owner, Study study, String timeframe, long capturedAt,
            String title,String url,ImageData image,String hash,String agentId,String agentName,String model,String notes) {
        String id=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO economy_observation(id,study_id,owner,timeframe,captured_at,created_at,source_title,source_url,mime_type,image,image_hash,agent_id,agent_name,model,notes,status)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ANALYZING')
                """,id,study.id(),owner,timeframe,capturedAt,System.currentTimeMillis(),title,url,image.mimeType(),image.bytes(),hash,agentId,agentName,model,notes);
        return observation(owner,id);
    }
    public void complete(String owner,String id,String model,StepAnalysis analysis) {
        jdbc.update("UPDATE economy_observation SET status='COMPLETE',model=?,analysis=?,error=NULL WHERE owner=? AND id=? AND status='ANALYZING'",model,json(analysis),owner,id);
    }
    public void fail(String owner,String id,String error) {
        jdbc.update("UPDATE economy_observation SET status='FAILED',error=? WHERE owner=? AND id=? AND status='ANALYZING'",error,owner,id);
    }
    public void retry(String owner,String id) {
        if(jdbc.update("UPDATE economy_observation SET status='ANALYZING',error=NULL WHERE owner=? AND id=? AND status='FAILED'",owner,id)!=1)
            throw new IllegalArgumentException("仅失败的分步分析可以重试；完成的步骤请重新截图分析");
    }
    @Transactional
    public void saveReport(String owner,Report report) {
        jdbc.update("INSERT INTO economy_evaluation(id,owner,study_id,instrument_id,trade_date,created_at,body) VALUES(?,?,?,?,?,?,?)",
                report.id(),owner,report.studyId(),report.instrumentId(),report.tradeDate(),report.analyzedAt(),json(report));
        for(String id:report.observationIds()) jdbc.update("INSERT INTO economy_evidence(evaluation_id,observation_id) VALUES(?,?)",report.id(),id);
    }
    public List<Report> previous(String owner,Study study) {
        return jdbc.query("SELECT body FROM economy_evaluation WHERE owner=? AND instrument_id=? AND trade_date<? AND study_id<>? ORDER BY trade_date DESC,created_at DESC LIMIT 3",
                (rs,n)->read(rs.getString(1),Report.class),owner,study.instrumentId(),study.tradeDate(),study.id());
    }
    public StudyPage history(String owner,String kind,String symbol,String from,String to,int page) {
        if(kind!=null&&!kind.isBlank()&&!Set.of("market","stock").contains(kind))throw new IllegalArgumentException("无效标的类型");
        List<Object> args=new ArrayList<>(List.of(owner));
        StringBuilder where=new StringBuilder(" WHERE s.owner=?");
        if(kind!=null&&!kind.isBlank()){where.append(" AND i.kind=?");args.add(kind);}
        if(symbol!=null&&!symbol.isBlank()){where.append(" AND instr(upper(i.symbol),?)>0");args.add(EconomyService.text(symbol,160,"标的查询").toUpperCase(Locale.ROOT));}
        if(from!=null&&!from.isBlank()){where.append(" AND s.trade_date>=?");args.add(EconomyService.tradeDate(from));}
        if(to!=null&&!to.isBlank()){where.append(" AND s.trade_date<=?");args.add(EconomyService.tradeDate(to));}
        if(from!=null&&to!=null&&!from.isBlank()&&!to.isBlank()&&from.compareTo(to)>0)throw new IllegalArgumentException("开始日期不能晚于结束日期");
        String join=" FROM economy_study s JOIN economy_instrument i ON i.id=s.instrument_id";
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*)"+join+where,Long.class,args.toArray()));
        int selected=Math.max(1,Math.min(page,Math.max(1,(int)((total+19)/20))));
        String sql="""
                SELECT s.id,s.instrument_id,i.kind,i.symbol,s.trade_date,s.created_at,
                  (SELECT count(DISTINCT timeframe) FROM economy_observation o WHERE o.study_id=s.id AND o.status='COMPLETE') completed,
                  (SELECT count(*) FROM economy_observation o WHERE o.study_id=s.id) observations,
                  (SELECT count(*) FROM economy_evaluation e WHERE e.study_id=s.id) reports,
                  (SELECT body FROM economy_evaluation e WHERE e.study_id=s.id ORDER BY created_at DESC,rowid DESC LIMIT 1) latest
                """+join+where+" ORDER BY s.trade_date DESC,s.created_at DESC,s.rowid DESC LIMIT 20 OFFSET ?";
        args.add((selected-1)*20);
        var items=jdbc.query(sql,(rs,n)->{
            String latest=rs.getString("latest"); Report r=latest==null?null:read(latest,Report.class);
            return new StudySummary(studyRow(rs,n),rs.getInt("completed"),rs.getInt("observations"),rs.getInt("reports"),r==null?null:r.summary(),r==null?null:r.score());
        },args.toArray());
        return new StudyPage(items,total,selected,20);
    }
    /** The old single-image records remain readable; they are never promoted to three-image evidence. */
    public List<JsonNode> legacy(String owner) {
        return jdbc.query("SELECT body FROM economy_report WHERE owner=? ORDER BY created_at DESC",(rs,n)->read(rs.getString(1),JsonNode.class),owner);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        jdbc.update("UPDATE economy_observation SET status='FAILED',error='服务已重启，截图已保存，请重试本步分析' WHERE status='ANALYZING'");
    }
    String json(Object value) { try{return om.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("观测数据序列化失败",e);} }
    private <T>T read(String json,Class<T> type) { try{return om.readValue(json,type);}catch(Exception e){throw new IllegalStateException("读取观测数据失败",e);} }
}
