package cc.ginpika.minis.ai.comfyui;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** ComfyUI 配置与生成产物的正本；图片随应用数据库备份，不依赖远端输出目录。 */
@Repository
public class ComfyUiStore {
    private final JdbcTemplate jdbc;
    public ComfyUiStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<String> endpoints() { return configs("comfyui_endpoint"); }
    public List<String> profiles() { return configs("comfyui_profile"); }
    private List<String> configs(String table) {
        return jdbc.query("SELECT config FROM " + table + " ORDER BY rowid", (r, n) -> r.getString(1));
    }
    public void saveEndpoint(String id, String config) {
        jdbc.update("INSERT INTO comfyui_endpoint(id,config) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET config=excluded.config", id, config);
    }
    public void saveProfile(String id, String config) {
        jdbc.update("INSERT INTO comfyui_profile(id,config) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET config=excluded.config", id, config);
    }
    public void deleteEndpoint(String id) { jdbc.update("DELETE FROM comfyui_endpoint WHERE id=?", id); }
    public void deleteProfile(String id) { jdbc.update("DELETE FROM comfyui_profile WHERE id=?", id); }
    public String defaultProfile() {
        return jdbc.query("SELECT value FROM comfyui_setting WHERE key='default_profile'", (r,n) -> r.getString(1)).stream().findFirst().orElse(null);
    }
    public void select(String id) {
        if (id == null || id.isBlank()) jdbc.update("DELETE FROM comfyui_setting WHERE key='default_profile'");
        else jdbc.update("INSERT INTO comfyui_setting(key,value) VALUES('default_profile',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", id);
    }
    public String start(String profileId, String prompt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO comfyui_generation(id,profile_id,prompt,status) VALUES(?,?,?,'submitting')", id, profileId, prompt);
        return id;
    }
    public void submitted(String id, String promptId) {
        jdbc.update("UPDATE comfyui_generation SET prompt_id=?,status='waiting' WHERE id=?", promptId, id);
    }
    public void failed(String id, String error) {
        jdbc.update("UPDATE comfyui_generation SET status='failed',error=? WHERE id=?", error, id);
    }
    @Transactional
    public List<ComfyUiService.Image> complete(String generationId, List<ComfyUiClient.ImageData> images) {
        var result = images.stream().map(image -> {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO comfyui_image(id,generation_id,media_type,content) VALUES(?,?,?,?)", id, generationId, image.mediaType(), image.bytes());
            return new ComfyUiService.Image(id, "/api/comfyui/images/" + id);
        }).toList();
        jdbc.update("UPDATE comfyui_generation SET status='completed',error=NULL WHERE id=?", generationId);
        return result;
    }
    public ComfyUiClient.ImageData image(String id) {
        return jdbc.query("SELECT media_type,content FROM comfyui_image WHERE id=?", (r,n) -> new ComfyUiClient.ImageData(r.getString(1), r.getBytes(2)), id)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "图片不存在"));
    }
}
