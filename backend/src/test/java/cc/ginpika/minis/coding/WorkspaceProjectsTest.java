package cc.ginpika.minis.coding;

import cc.ginpika.minis.agent.workspace.WorkspaceService;
import cc.ginpika.minis.ai.terminal.ExecTarget;
import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.ai.terminal.store.TerminalStore;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.runner.WorkspaceCommands;
import cc.ginpika.minis.coding.store.CodingStore;
import cc.ginpika.minis.coding.workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkspaceProjectsTest {
    private WorkspaceProjects projects;private TerminalStore terminals;private ExecTarget exec;private CodingStore store;
    private final ObjectMapper json=new ObjectMapper();
    private SingleConnectionDataSource source;
    private TerminalStore.TargetRow target(boolean enabled){return new TerminalStore.TargetRow("w1","Docker Dev","docker",enabled,"/workspace",60000L,40000,"[]","[]",null,"node:22","dev",null,null,null,null,"credential-must-not-leak",null,null,null,null,0,0);}
    @BeforeEach void setup(){
        source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);var jdbc=new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("coding/schema.sql")).execute(source);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));store=new CodingStore(jdbc,tx,json);
        terminals=mock(TerminalStore.class);exec=mock(ExecTarget.class);when(exec.kind()).thenReturn("docker");
        when(terminals.targets()).thenReturn(List.of(target(true)));when(terminals.targetById("w1")).thenReturn(Optional.of(target(true)));
        when(exec.run(any(),anyString(),any())).thenReturn(new ExecTarget.ExecOutcome(0,"/workspace/app\n","",1,false,false));
        projects=new WorkspaceProjects(terminals,mock(WorkspaceService.class),List.of(exec),jdbc,tx,store,mock(CodingWorkspace.class),new CodingProps(),json);
    }
    @AfterEach void close(){source.destroy();}
    @Test void projectBindingsReuseWorkspaceAndAreOwned(){
        var request=new OpenProject("w1","app",null,false,null);var first=projects.open("alice",request);
        assertEquals(first.id(),projects.open("alice",request).id());assertEquals("app",first.directory());assertEquals("w1",first.workspaceId());
        assertEquals(1,projects.projects("alice").size());assertTrue(projects.projects("bob").isEmpty());assertThrows(Exception.class,()->projects.project("bob",first.id()));
        assertEquals("workspace://w1/app",store.repository(first.id(),"alice").path());
    }
    @Test void disabledWorkspaceCannotBeOpened(){
        when(terminals.targetById("w1")).thenReturn(Optional.of(target(false)));
        assertThrows(Exception.class,()->projects.open("alice",new OpenProject("w1","app",null,false,null)));verify(exec,never()).run(any(),anyString(),any());
    }
    @Test void creationUsesOneQuotedChildDirectory(){
        projects.open("alice",new OpenProject("w1",".","App's demo; name",true,null));
        verify(exec).run(any(),argThat(command->command.contains("mkdir -- "+ExecTarget.quote("App's demo; name"))),any());
    }
    @Test void invalidPathsAreRejectedBeforeTransport(){
        for(String path:List.of("../app","app/../../outside","/root","C:/root","app\\file","app\nfile",".ssh",".minis-coding")){
            assertThrows(Exception.class,()->projects.open("alice",new OpenProject("w1",path,null,false,null)));
        }
        verify(exec,never()).run(any(),anyString(),any());
    }
    @Test void workspacePayloadNeverContainsStoredCredentials() throws Exception {
        assertFalse(json.writeValueAsString(projects.workspaces()).contains("credential-must-not-leak"));
    }
    @Test void uncertainDirectoryProbeDoesNotCreateABinding(){
        when(exec.run(any(),anyString(),any())).thenReturn(new ExecTarget.ExecOutcome(-1,"partial","",1,true,true));
        assertThrows(Exception.class,()->projects.open("alice",new OpenProject("w1","app",null,false,null)));assertTrue(projects.projects("alice").isEmpty());
    }
    @Test void archivePreservesBytesAndExecutableModeAndExcludesSecrets() throws Exception {
        var content=Map.of("src/main.bin",new byte[]{0,1,(byte)255},"run.sh","echo test\r\n".getBytes(),".env","secret".getBytes());
        var snapshot=WorkspaceArchive.unpack(WorkspaceArchive.pack(content,Set.of("run.sh")),new CodingProps());
        assertArrayEquals(content.get("src/main.bin"),snapshot.files().get("src/main.bin"));assertTrue(snapshot.executable().contains("run.sh"));assertFalse(snapshot.files().containsKey(".env"));
    }
    @Test void archiveRejectsTraversalAndLinks() throws Exception {
        assertThrows(IOException.class,()->WorkspaceArchive.unpack(archive("../outside",false),new CodingProps()));
        assertThrows(IOException.class,()->WorkspaceArchive.unpack(archive("src/link",true),new CodingProps()));
    }
    @Test void decompressedArchiveEnforcesByteBudget() throws Exception {
        var props=new CodingProps();props.setMaxWorkspaceBytes(2);
        byte[] packed=WorkspaceArchive.pack(Map.of("large.txt",new byte[3]),Set.of());
        assertThrows(IOException.class,()->WorkspaceArchive.unpack(packed,props));
    }
    @Test void modelCommandsMustPassExistingWorkspaceRulesBeforeSynchronizing() throws Exception {
        var bridge=mock(WorkspaceProjects.class);var terminal=mock(TerminalService.class);
        when(bridge.binding("repo")).thenReturn(new WorkspaceProjects.Binding("repo","w1","app","/workspace/app","fingerprint"));
        when(terminal.commandRejection("w1","denied command")).thenReturn("denied");
        var runner=new WorkspaceCommands(bridge,terminal,terminals,store,mock(CodingWorkspace.class),new CodingProps(),json);
        var run=new Run("00000000-0000-0000-0000-000000000000",1,"alice","repo","goal",null,Mode.EXECUTE,Runner.WORKSPACE,"RUNNING",1,0,null,null,"NOT_RUN",null,0,0);
        assertThrows(IllegalArgumentException.class,()->runner.start(run,"denied command",60));verify(bridge,never()).pushExecution(any());
    }
    @Test void workspaceModesDoNotInheritWindowsExecutableDefaults(@TempDir Path directory) throws Exception {
        var props=new CodingProps();props.setDataDir(directory.toString());var workspace=new CodingWorkspace(props,json);
        String id=UUID.randomUUID().toString();Files.createDirectories(workspace.checkout(id));
        Files.writeString(workspace.checkout(id).resolve("run.sh"),"echo run");Files.writeString(workspace.checkout(id).resolve("source.ts"),"const value = 1");
        json.writeValue(workspace.task(id).resolve("workspace-modes.json").toFile(),Set.of("run.sh"));
        assertTrue(workspace.manifest(id).get("run.sh").executable());assertFalse(workspace.manifest(id).get("source.ts").executable());
        workspace.replaceImported(id,Map.of("source.ts","const value = 2".getBytes()),Set.of());
        assertFalse(Files.exists(workspace.checkout(id).resolve("run.sh")));assertFalse(workspace.manifest(id).get("source.ts").executable());
    }
    private static byte[] archive(String name,boolean link) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var tar=new TarArchiveOutputStream(new GZIPOutputStream(bytes))){var entry=link?new TarArchiveEntry(name,TarConstants.LF_SYMLINK):new TarArchiveEntry(name);if(link){entry.setLinkName("/etc/passwd");}entry.setSize(0);tar.putArchiveEntry(entry);tar.closeArchiveEntry();}
        return bytes.toByteArray();
    }
}
