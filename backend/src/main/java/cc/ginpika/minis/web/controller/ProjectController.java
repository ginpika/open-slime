package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.project.ProjectStore.ProjectRow;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 项目 API（memory-v5 §10）：项目的增删改查。项目是记忆归属的作用域锚点——会话绑项目后，
 * 其记忆按项目隔离；创作类项目（narrative=1）的域内记忆永不升级为用户画像。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectStore projects;

    public ProjectController(ProjectStore projects) {
        this.projects = projects;
    }

    @GetMapping
    public List<ProjectRow> list() {
        return projects.list();
    }

    @GetMapping("/{id}")
    public ProjectRow get(@PathVariable String id) {
        return projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在: " + id));
    }

    @PostMapping
    public ProjectRow create(@RequestBody ProjectRequest req) {
        String name = req == null || req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目名不能为空");
        }
        String id = UUID.randomUUID().toString();
        String kind = req.kind() == null || req.kind().isBlank() ? "other" : req.kind().trim();
        boolean narrative = req.narrative() != null ? req.narrative() : ProjectKinds.narrative(kind);
        projects.insert(id, null, name, kind, narrative, "active", req.note());
        return projects.findById(id).orElseThrow();
    }

    @PutMapping("/{id}")
    public ProjectRow update(@PathVariable String id, @RequestBody ProjectRequest req) {
        require(id);
        String name = req == null ? null : blank(req.name());
        String kind = req == null ? null : blank(req.kind());
        String status = req == null ? null : blank(req.status());
        Boolean narrative = req == null ? null : req.narrative();
        String note = req == null ? null : req.note();
        projects.update(id, name, kind, narrative, status, note);
        return require(id);
    }

    @DeleteMapping("/{id}")
    public CountResult delete(@PathVariable String id) {
        return new CountResult(projects.delete(id));
    }

    private ProjectRow require(String id) {
        return projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在: " + id));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 新建 / 编辑请求体 */
    public record ProjectRequest(String name, String kind, Boolean narrative, String status, String note) {
    }

    /** 变更条数结果 */
    public record CountResult(int changed) {
    }

    /** 由 kind 推断是否创作 / 虚构类 */
    static final class ProjectKinds {
        private ProjectKinds() {
        }

        static boolean narrative(String kind) {
            if (kind == null) {
                return false;
            }
            return switch (kind.toLowerCase()) {
                case "novel", "fiction", "rpg", "story" -> true;
                default -> false;
            };
        }
    }
}
