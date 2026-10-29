package cc.ginpika.minis.coding;

import cc.ginpika.minis.coding.api.CodingDtos.Repository;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CodingWorkspaceTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private CodingWorkspace files;
    private Path source;
    private String id;
    private Repository repository;
    @BeforeEach void prepare() throws Exception {
        CodingProps props=new CodingProps();props.setDataDir(temp.resolve("managed").toString());files=new CodingWorkspace(props,json);
        source=Files.createDirectories(temp.resolve("repo"));
        Files.writeString(source.resolve("hello.java"),"one\r\ntwo\r\n");
        Files.writeString(source.resolve(".gitignore"),"node_modules/\n");
        Files.writeString(source.resolve(".env"),"PRIVATE_TEST_FIXTURE=excluded\n");
        files.git(source,"init","--quiet","--separate-git-dir",temp.resolve("source-metadata").toString());files.git(source,"add","--all");files.git(source,"-c","user.name=Test","-c","user.email=test@localhost","commit","--quiet","--no-verify","-m","initial");
        repository=new Repository("repo","owner","Example",source.toString(),null,null,0);id=UUID.randomUUID().toString();
    }
    @Test void snapshotPreservesDirtyAndUntrackedFilesAndExcludesSecrets() throws Exception {
        Files.writeString(source.resolve("hello.java"),"dirty\r\n");Files.writeString(source.resolve("new.txt"),"untracked");
        files.prepare(id,repository);
        assertEquals("dirty\r\n",Files.readString(files.checkout(id).resolve("hello.java")));
        assertEquals("untracked",Files.readString(files.checkout(id).resolve("new.txt")));
        assertFalse(Files.exists(files.checkout(id).resolve(".env")));
        assertEquals("dirty\r\n",Files.readString(source.resolve("hello.java")));
        assertTrue(files.diff(id).changes().isEmpty());
    }
    @Test void patchRequiresActualHashAndPrevalidatesWholeBatch() throws Exception {
        files.prepare(id,repository);String old=Files.readString(files.checkout(id).resolve("hello.java"));
        String hash=CodingWorkspace.hash(old.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var patches=json.createArrayNode();patches.addObject().put("path","hello.java").put("expectedHash",hash).put("oldText","one").put("newText","changed");
        patches.addObject().put("path","missing.txt").put("expectedHash","wrong").put("newText","created");
        assertThrows(IllegalArgumentException.class,()->files.patch(id,patches));
        assertEquals(old,Files.readString(files.checkout(id).resolve("hello.java")));
        patches.remove(1);files.patch(id,patches);
        assertEquals("changed\r\ntwo\r\n",Files.readString(files.checkout(id).resolve("hello.java")));
        assertThrows(IllegalArgumentException.class,()->files.patch(id,patches));
        assertEquals(old,Files.readString(source.resolve("hello.java")));
    }
    @Test void acceptRejectsConcurrentSourceChanges() throws Exception {
        files.prepare(id,repository);Files.writeString(files.checkout(id).resolve("hello.java"),"agent change");
        var artifact=files.diff(id);Files.writeString(source.resolve("hello.java"),"user change");
        assertThrows(IllegalArgumentException.class,()->files.accept(id,repository,artifact.artifactHash()));
        assertEquals("user change",Files.readString(source.resolve("hello.java")));
    }
    @Test void acceptIncludesNewFilesAndChecksArtifactVersion() throws Exception {
        files.prepare(id,repository);Files.writeString(files.checkout(id).resolve("created.txt"),"new code");
        var artifact=files.diff(id);assertTrue(artifact.changes().stream().anyMatch(c->c.path().equals("created.txt")));
        assertTrue(artifact.patch().contains("created.txt"));
        assertThrows(IllegalArgumentException.class,()->files.accept(id,repository,"old-review-hash"));
        files.accept(id,repository,artifact.artifactHash());assertEquals("new code",Files.readString(source.resolve("created.txt")));
    }
    @Test void existingContentConfirmationRequiresTheWholeAfterImage() throws Exception {
        files.prepare(id,repository);Files.writeString(files.checkout(id).resolve("created.txt"),"new code");
        var diff=files.diff(id);var expected=files.appliedManifest(id,diff);
        assertTrue(files.matchesApplied(id,diff,expected));
        var partial=new java.util.TreeMap<>(expected);partial.remove("created.txt");
        assertFalse(files.matchesApplied(id,diff,partial));
        var altered=new java.util.TreeMap<>(expected);altered.put("hello.java",new cc.ginpika.minis.coding.repository.CodingWorkspace.FileStamp("user-change",1,false));
        assertFalse(files.matchesApplied(id,diff,altered),"未改动的基准文件也必须一致");
        Files.writeString(source.resolve("created.txt"),"new code");Files.writeString(source.resolve("hello.java"),"user change");
        assertThrows(IllegalArgumentException.class,()->files.accept(id,repository,diff.artifactHash()));
        assertEquals("user change",Files.readString(source.resolve("hello.java")));
    }
    @Test void privateGitMetadataIsNotControlledByTaskCommands() throws Exception {
        files.prepare(id,repository);Files.delete(files.checkout(id).resolve(".git"));Files.createDirectory(files.checkout(id).resolve(".git"));
        Files.writeString(files.checkout(id).resolve(".git/config"),"[filter \"fixture\"]\n clean = command-that-does-not-exist\n required = true\n");
        Files.writeString(files.checkout(id).resolve(".gitattributes"),"*.java filter=fixture\n");
        Files.writeString(files.checkout(id).resolve("hello.java"),"updated");
        assertDoesNotThrow(()->files.diff(id));
    }
    @Test void traversalAndSecretPathsAreRejected() throws Exception {
        files.prepare(id,repository);
        for(String path:new String[]{"../outside","src/../hello.java",".git/config",".env","/absolute","C:/absolute"}) {
            assertThrows(IllegalArgumentException.class,()->files.resolve(files.checkout(id),path,true),path);
        }
    }
    @Test void invalidUtf8IsNotSilentlyRewritten() {
        assertTrue(CodingWorkspace.binary(new byte[]{(byte)0xff}));
        assertTrue(CodingWorkspace.binary(new byte[]{0}));
        assertFalse(CodingWorkspace.binary("代码\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void disjointParallelCopiesIntegrateWithoutOverwritingAnAcceptedChange() throws Exception {
        String other=UUID.randomUUID().toString();files.prepare(id,repository);files.prepare(other,repository);
        Files.writeString(files.checkout(id).resolve("hello.java"),"first worker\n");
        Files.writeString(files.checkout(other).resolve("feature.txt"),"second worker\n");
        String previousHash=files.diff(other).artifactHash();
        files.accept(id,repository,files.diff(id).artifactHash());
        assertTrue(files.integrate(other,repository));
        assertEquals("first worker\n",Files.readString(files.checkout(other).resolve("hello.java")));
        var merged=files.diff(other);
        assertNotEquals(previousHash,merged.artifactHash(),"整合改变代码树，旧验证必须失效");
        assertEquals(java.util.List.of("feature.txt"),merged.changes().stream().map(c->c.path()).toList());
        assertFalse(merged.patch().contains("first worker"),"已接受的其他成果属于新基准，不应重复提交");
        files.accept(other,repository,merged.artifactHash());
        assertEquals("first worker\n",Files.readString(source.resolve("hello.java")));
        assertEquals("second worker\n",Files.readString(source.resolve("feature.txt")));
    }
    @Test void parallelEditsToOnePathFailBeforeChangingEitherCopy() throws Exception {
        String other=UUID.randomUUID().toString();files.prepare(id,repository);files.prepare(other,repository);
        Files.writeString(files.checkout(id).resolve("hello.java"),"accepted worker");
        Files.writeString(files.checkout(other).resolve("hello.java"),"conflicting worker");
        files.accept(id,repository,files.diff(id).artifactHash());
        String hash=files.diff(other).artifactHash();
        var error=assertThrows(IllegalArgumentException.class,()->files.integrate(other,repository));
        assertTrue(error.getMessage().contains("APPLICATION_CONFLICT"));
        assertEquals("conflicting worker",Files.readString(files.checkout(other).resolve("hello.java")));
        assertEquals(hash,files.diff(other).artifactHash());
        assertEquals("accepted worker",Files.readString(source.resolve("hello.java")));
    }
    @Test void integrationTracksSourceAdditionsAndDeletionsAndKeepsOwnDeletion() throws Exception {
        Files.writeString(source.resolve("obsolete.txt"),"delete by source");files.prepare(id,repository);
        Files.delete(files.checkout(id).resolve("hello.java"));
        Files.delete(source.resolve("obsolete.txt"));Files.writeString(source.resolve("fresh.txt"),"new source");
        assertTrue(files.integrate(id,repository));
        assertFalse(Files.exists(files.checkout(id).resolve("obsolete.txt")));
        assertFalse(Files.exists(files.checkout(id).resolve("hello.java")));
        assertEquals("new source",Files.readString(files.checkout(id).resolve("fresh.txt")));
        assertEquals(java.util.List.of("hello.java"),files.diff(id).changes().stream().map(c->c.path()).toList());
        files.accept(id,repository,files.diff(id).artifactHash());
        assertFalse(Files.exists(source.resolve("hello.java")));
    }
    @Test void interruptedIntegrationCannotBeAppliedOrResumed() throws Exception {
        files.prepare(id,repository);Files.writeString(files.task(id).resolve("integration-journal.json"),"{}");
        assertThrows(IllegalStateException.class,()->files.diff(id));
        assertThrows(IllegalStateException.class,()->files.prepare(id,repository));
        assertEquals("one\r\ntwo\r\n",Files.readString(source.resolve("hello.java")));
    }
}
