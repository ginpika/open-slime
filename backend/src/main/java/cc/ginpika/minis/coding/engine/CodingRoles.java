package cc.ginpika.minis.coding.engine;

import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.context.CodingPrompts;
import cc.ginpika.minis.config.SettingsService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** User-editable model/prompts, server-defined read-only capabilities. */
@Component
public class CodingRoles {
    private static final List<String> READ=List.of("list_files","read_file","search_text","git_status","git_diff");
    private static final Map<String,Role> DEFAULTS=new LinkedHashMap<>();
    static {
        DEFAULTS.put("explore",new Role("explore","Explore · 探索","定位代码、符号、引用与项目入口",null,
                CodingPrompts.EXPLORE,2,READ));
        DEFAULTS.put("planner",new Role("planner","Planner · 规划","分析现有结构，形成实施步骤与取舍",null,
                CodingPrompts.PLANNER,2,READ));
        DEFAULTS.put("reviewer",new Role("reviewer","Reviewer · 审阅","独立检查具体版本的代码与变更",null,
                CodingPrompts.REVIEWER,2,READ));
    }
    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    public CodingRoles(JdbcTemplate jdbc,SettingsService settings){this.jdbc=jdbc;this.settings=settings;}
    public List<Role> list(String owner){return DEFAULTS.keySet().stream().map(id->get(owner,id)).toList();}
    public Role get(String owner,String id){
        Role base=DEFAULTS.get(id);if(base==null){throw new IllegalArgumentException("未知编码角色");}
        return jdbc.query("SELECT * FROM coding_role_config WHERE owner_id=? AND role=?",(r,n)->
                new Role(id,base.name(),base.description(),r.getString("model_id"),r.getString("instructions"),r.getInt("version"),READ),owner,id).stream().findFirst().orElse(base);
    }
    public Role save(String owner,String id,RoleConfig config){
        Role base=DEFAULTS.get(id);if(base==null){throw new IllegalArgumentException("未知编码角色");}
        if(config==null || config.instructions()==null || config.instructions().isBlank() || config.instructions().length()>20_000){throw new IllegalArgumentException("角色提示词不能为空且不得超过 20000 字符");}
        String model=config.modelId()==null || config.modelId().isBlank()?null:config.modelId();
        if(model!=null && !settings.resolveChatModel(model).configured()){throw new IllegalArgumentException("角色模型尚未配置");}
        jdbc.update("INSERT INTO coding_role_config(owner_id,role,model_id,instructions,version,updated_at) VALUES(?,?,?,?,?,?) "
                +"ON CONFLICT(owner_id,role) DO UPDATE SET model_id=excluded.model_id,instructions=excluded.instructions,version=coding_role_config.version+1,updated_at=excluded.updated_at",
                owner,id,model,config.instructions().trim(),base.version()+1,System.currentTimeMillis());
        return get(owner,id);
    }
}
