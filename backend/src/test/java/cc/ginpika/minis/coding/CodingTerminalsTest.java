package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.computer.terminal.ExecTarget;
import cc.ginpika.minis.coding.api.CodingDtos.CodingProject;
import cc.ginpika.minis.coding.runner.CodingTerminals;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingTerminalsTest {
    private final WorkspaceProjects projects=mock(WorkspaceProjects.class);
    private CodingTerminals terminals;
    private final java.util.List<FakeShell> shells=new java.util.ArrayList<>();
    static class FakeShell implements ExecTarget.Shell {
        final PipedInputStream output=new PipedInputStream(65536);
        final PipedOutputStream feed;
        final ByteArrayOutputStream input=new ByteArrayOutputStream();
        int cols,rows;volatile boolean closed;
        FakeShell() throws IOException{feed=new PipedOutputStream(output);}
        public InputStream output(){return output;}
        public OutputStream input(){return input;}
        public void resize(int cols,int rows){this.cols=cols;this.rows=rows;}
        public void close() throws IOException{closed=true;feed.close();output.close();}
    }
    @BeforeEach void setup() throws Exception {
        when(projects.project(anyString(),anyString())).thenAnswer(inv->new CodingProject(inv.getArgument(1),"fixture","w","workspace","ssh","app",null,true,0));
        when(projects.openShell(anyString(),anyString(),anyInt(),anyInt())).thenAnswer(inv->{var shell=new FakeShell();shells.add(shell);return shell;});
        terminals=new CodingTerminals(projects);
    }
    @AfterEach void close(){terminals.close();}
    @Test void terminalsKeepInputOutputAndResizeIndependentAndDeletionClosesTheProcess() throws Exception {
        var first=terminals.create("alice","p",80,24);var second=terminals.create("alice","p",80,24);
        terminals.input("alice","p",first.id(),"cd src\r中文\u0003");
        assertEquals("cd src\r中文\u0003",shells.getFirst().input.toString(StandardCharsets.UTF_8));assertEquals(0,shells.get(1).input.size());
        shells.getFirst().feed.write("你好\r\n".getBytes(StandardCharsets.UTF_8));shells.getFirst().feed.flush();
        CodingTerminals.Output result=awaitOutput(first.id(),0);
        assertEquals("你好\r\n",new String(Base64.getDecoder().decode(result.data()),StandardCharsets.UTF_8));
        assertEquals("",terminals.read("alice","p",first.id(),result.nextOffset()).data());
        assertEquals("",terminals.read("alice","p",second.id(),0).data());
        terminals.resize("alice","p",first.id(),120,35);assertEquals(120,shells.getFirst().cols);assertEquals(35,shells.getFirst().rows);
        terminals.delete("alice","p",first.id());assertTrue(shells.getFirst().closed);assertFalse(shells.get(1).closed);
        assertEquals(1,terminals.list("alice","p").size());
        assertEquals("T1",terminals.create("alice","p",80,24).title());
        terminals.delete("alice","p",first.id()); // Retrying a lost DELETE response is harmless.
    }
    @Test void projectAndOwnerAreCheckedBeforeTouchingAnotherShell(){
        var terminal=terminals.create("alice","p",80,24);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,()->terminals.input("bob","p",terminal.id(),"rm x")).getStatusCode());
        assertThrows(ResponseStatusException.class,()->terminals.read("alice","other-project",terminal.id(),0));
        assertThrows(ResponseStatusException.class,()->terminals.delete("bob","p",terminal.id()));assertFalse(shells.getFirst().closed);
        when(projects.project("eve","p")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThrows(ResponseStatusException.class,()->terminals.create("eve","p",80,24));assertEquals(1,shells.size());
    }
    @Test void invalidSizesInputAndOffsetsAreRejectedAndDisabledTargetsCanStillBeDeleted(){
        assertThrows(ResponseStatusException.class,()->terminals.create("alice","p",0,24));
        var terminal=terminals.create("alice","p",80,24);
        assertThrows(ResponseStatusException.class,()->terminals.resize("alice","p",terminal.id(),80,1000));
        assertThrows(ResponseStatusException.class,()->terminals.input("alice","p",terminal.id(),"x".repeat(65537)));
        assertThrows(ResponseStatusException.class,()->terminals.read("alice","p",terminal.id(),-1));
        when(projects.project("alice","p")).thenReturn(new CodingProject("p","p","w","w","ssh","app",null,false,0));
        assertThrows(ResponseStatusException.class,()->terminals.input("alice","p",terminal.id(),"echo x"));assertTrue(shells.getFirst().closed);
        terminals.delete("alice","p",terminal.id());assertTrue(terminals.list("alice","p").isEmpty());
    }
    @Test void outputIsBoundedAndOverflowHasAnExplicitResetCursor() throws Exception {
        var terminal=terminals.create("alice","p",80,24);byte[] bytes=new byte[1200000];java.util.Arrays.fill(bytes,(byte)'x');
        shells.getFirst().feed.write(bytes);shells.getFirst().feed.flush();
        long deadline=System.currentTimeMillis()+3000;CodingTerminals.Output result;
        do{result=terminals.read("alice","p",terminal.id(),0);if(result.reset())break;Thread.sleep(5);}while(System.currentTimeMillis()<deadline);
        assertTrue(result.reset());assertTrue(Base64.getDecoder().decode(result.data()).length<=65536);assertTrue(result.nextOffset()>65536);
    }
    @Test void admissionLimitIncludesAllProjectsForTheSameOwner(){
        for(int i=0;i<8;i++){terminals.create("alice","p"+i,80,24);}
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,assertThrows(ResponseStatusException.class,()->terminals.create("alice","other",80,24)).getStatusCode());
    }
    private CodingTerminals.Output awaitOutput(String id,long after) throws InterruptedException {
        long deadline=System.currentTimeMillis()+3000;CodingTerminals.Output result;
        do{result=terminals.read("alice","p",id,after);if(!result.data().isEmpty())return result;Thread.sleep(5);}while(System.currentTimeMillis()<deadline);
        fail("PTY output was not pumped");return null;
    }
}
