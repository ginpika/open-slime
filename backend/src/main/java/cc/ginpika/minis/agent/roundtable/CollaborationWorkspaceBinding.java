package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.ai.core.UserScope;
import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.coding.api.CodingDtos;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.project.ProjectWorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Reuses workspace-project path validation and identity; collaboration never chooses another repository itself. */
@Service
public class CollaborationWorkspaceBinding {
    public record Location(String projectId, String repositoryId, String workspaceId, String workspaceName,
                           String directory, String root, boolean enabled) {}
    private final ProjectWorkspaceStore store;
    private final WorkspaceProjects workspaces;
    private final ProjectStore projects;
    public CollaborationWorkspaceBinding(ProjectWorkspaceStore store, WorkspaceProjects workspaces, ProjectStore projects) {
        this.store=store; this.workspaces=workspaces; this.projects=projects;
    }
    public List<CodingDtos.Workspace> workspaces() { return workspaces.workspaces(); }
    public List<Location> list() {
        return store.list().stream().map(b -> {
            try { return location(b); }
            catch (ResponseStatusException error) { return new Location(b.projectId(),b.repositoryId(),null,"绑定已失效",null,null,false); }
        }).toList();
    }
    public Location bind(String project, CodingDtos.OpenProject request) {
        projects.findById(project).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"项目不存在"));
        String owner=UserScope.get();
        if (owner==null || owner.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"缺少用户身份");
        if (request==null || request.directory()==null || request.directory().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请明确选择工作空间内的项目目录");
        var selected=workspaces.open(owner,new CodingDtos.OpenProject(request.workspaceId(),request.directory(),
                request.create() ? request.name() : null,request.create(),request.validationCommand()));
        if(!selected.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,"所选项目的工作空间绑定已失效，请先核对原工作空间连接配置");
        store.bind(project,selected.id());
        return location(store.find(project).orElseThrow());
    }
    private Location location(ProjectWorkspaceStore.Binding binding) {
        var p=workspaces.project(UserScope.get(),binding.repositoryId());
        var b=workspaces.binding(binding.repositoryId());
        return new Location(binding.projectId(),p.id(),p.workspaceId(),p.workspaceName(),p.directory(),b.root(),p.enabled());
    }
    public Location forSession(String session) {
        var scope=store.scope(session);
        if (scope.repositoryId()==null) return null;
        return location(new ProjectWorkspaceStore.Binding(scope.projectId(),scope.repositoryId()));
    }
    public String context(String session) {
        try {
            if(!store.scope(session).constrained()) return "";
            var p=forSession(session);
            if(p==null) return "\n【项目工作目录未绑定】只能讨论与规划；请用户在项目页设置工作目录，禁止列举、挑选或借用其他现有项目，禁止执行代码命令。";
            return "\n【本项目唯一工作目录】工作空间："+p.workspaceName()+"；相对目录："+p.directory()+"；实际根路径："+p.root()
                    +"；coding_task repositoryId="+p.repositoryId()+"。所有成员在此项目内工作，不读取其他项目作为当前上下文，不自行切换目录。"
                    +(p.enabled()?"":" 当前绑定不可用，请用户检查工作空间配置后重新绑定。");
        } catch(ResponseStatusException error) { return "\n【项目工作目录不可用】请用户重新核对绑定；禁止回退到其他项目。"; }
    }
    public String terminal(JsonNode call,String session,Set<String> allowed,TerminalService terminal) {
        try {
            if(!store.scope(session).constrained()) return terminal.run(call,TerminalService.SOURCE_CHAT,allowed).content();
            var p=forSession(session);
            if(p==null) return "项目未绑定工作目录，未执行。请用户在协作项目页设置工作目录，不能自行选择其他项目。";
            if(!p.enabled()) return "项目绑定已失效或工作空间未启用，未执行；请用户重新核对绑定。";
            return terminal.runScoped(call,TerminalService.SOURCE_CHAT,allowed,p.workspaceId(),p.root()).content();
        } catch(ResponseStatusException error) { return "项目工作目录不可用，未执行："+error.getReason(); }
    }
}
