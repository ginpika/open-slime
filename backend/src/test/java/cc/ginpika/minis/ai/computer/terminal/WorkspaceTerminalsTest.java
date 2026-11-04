package cc.ginpika.minis.ai.computer.terminal;

import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore;
import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore.TargetRow;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkspaceTerminalsTest {
    private final TerminalStore store=mock(TerminalStore.class);
    private final ExecTarget target=mock(ExecTarget.class);
    private final List<Shell> shells=new ArrayList<>();
    private WorkspaceTerminals terminals;
    static class Shell implements ExecTarget.Shell {
        final PipedInputStream output=new PipedInputStream();
        final PipedOutputStream feed;
        final ByteArrayOutputStream input=new ByteArrayOutputStream();
        boolean closed;int cols,rows;
        Shell() throws IOException {feed=new PipedOutputStream(output);}
        public InputStream output(){return output;}
        public OutputStream input(){return input;}
        public void resize(int cols,int rows){this.cols=cols;this.rows=rows;}
        public void close() throws IOException {closed=true;feed.close();output.close();}
    }
    static TargetRow row(boolean enabled,String workdir){return new TargetRow("w","fixture","ssh",enabled,workdir,30000L,100000,null,null,null,null,null,"host",22,"user","password","secret",null,null,null,null,null,0,0);}
    @BeforeEach void setup() throws Exception {
        when(store.targetById("w")).thenReturn(Optional.of(row(true,"/work/a'b")));
        when(target.kind()).thenReturn("ssh");
        when(target.openShell(any(),anyString(),anyInt(),anyInt())).thenAnswer(inv->{var shell=new Shell();shells.add(shell);return shell;});
        terminals=new WorkspaceTerminals(store,List.of(target));
    }
    @AfterEach void close(){terminals.close();}
    @Test void opensAtQuotedWorkspaceDirectoryAndPreservesIndependentSessions() throws Exception {
        var first=terminals.create("alice","w",80,24);var second=terminals.create("alice","w",80,24);
        verify(target,times(2)).openShell(eq(row(true,"/work/a'b")),eq("cd -- '/work/a'\\''b' || exit 72; if command -v bash >/dev/null 2>&1; then exec bash -i; else exec sh -i; fi"),eq(80),eq(24));
        terminals.input("alice","w",first.id(),"cd /tmp\rexport A=中文\r\u0003");
        assertTrue(shells.getFirst().input.toString(java.nio.charset.StandardCharsets.UTF_8).contains("中文"));assertEquals(0,shells.get(1).input.size());
        terminals.resize("alice","w",first.id(),100,30);assertEquals(100,shells.getFirst().cols);assertEquals(30,shells.getFirst().rows);
        assertThrows(ResponseStatusException.class,()->terminals.input("bob","w",first.id(),"bad"));
        assertThrows(ResponseStatusException.class,()->terminals.input("alice","other",first.id(),"bad"));
        assertFalse(shells.getFirst().closed);assertEquals(2,terminals.list("alice","w").size());
        terminals.delete("alice","w",second.id());assertTrue(shells.get(1).closed);assertFalse(shells.getFirst().closed);
    }
    @Test void disablingOrChangingConnectionClosesOldShellAndAllowsCleanup(){
        var terminal=terminals.create("alice","w",80,24);
        when(store.targetById("w")).thenReturn(Optional.of(row(true,"/changed")));
        assertThrows(ResponseStatusException.class,()->terminals.checkAccess("alice","w",terminal.id(),0));assertTrue(shells.getFirst().closed);
        when(store.targetById("w")).thenReturn(Optional.of(row(false,"/changed")));
        assertThrows(ResponseStatusException.class,()->terminals.create("alice","w",80,24));assertEquals(1,shells.size());
        when(store.targetById("w")).thenReturn(Optional.empty());
        terminals.delete("alice","w",terminal.id());assertTrue(terminals.list("alice","w").isEmpty());
        assertThrows(ResponseStatusException.class,()->terminals.create("alice","w",80,24));
    }
}
