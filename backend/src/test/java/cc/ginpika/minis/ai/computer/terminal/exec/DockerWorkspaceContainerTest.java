package cc.ginpika.minis.ai.computer.terminal.exec;

import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore.TargetRow;
import cc.ginpika.minis.config.Branding;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.MountType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DockerWorkspaceContainerTest {
    static TargetRow target(String source, String workdir) {
        return new TargetRow("id", "fixture", "docker", true, workdir, null, null, null, null,
                null, "alpine:latest", null, null, null, null, null, null, null, null, null, null, source, 0, 0);
    }

    @Test void bindsDirectoryAndReusesContainerButRejectsChangedBinding() {
        var docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.listContainersCmd().withShowAll(true).withNameFilter(anyList()).exec()).thenReturn(List.of());
        var create = mock(CreateContainerCmd.class, RETURNS_SELF);
        var response = new CreateContainerResponse(); response.setId("created");
        when(docker.createContainerCmd("alpine:latest")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        var transport = new DockerExecTarget(new Branding());
        assertEquals("created", transport.ensureContainer(docker, target("D:\\中文 项目", "/work")));
        var host = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(host.capture());
        var mount = host.getValue().getMounts().getFirst();
        assertEquals(MountType.BIND, mount.getType());
        assertEquals("D:\\中文 项目", mount.getSource());
        assertEquals("/work", mount.getTarget());
        assertFalse(mount.getReadOnly());
        verify(create).withWorkingDir("/work");
        verify(create).withCmd("sh", "-c", "while :; do sleep 3600; done");
        var labels = ArgumentCaptor.forClass(Map.class);
        verify(create).withLabels(labels.capture());
        var existing = mock(Container.class);
        when(existing.getNames()).thenReturn(new String[]{"/" + new Branding().slug() + "-term-fixture"});
        when(existing.getLabels()).thenReturn(labels.getValue());
        when(existing.getId()).thenReturn("created");
        when(existing.getState()).thenReturn("running");
        when(docker.listContainersCmd().withShowAll(true).withNameFilter(anyList()).exec()).thenReturn(List.of(existing));
        assertEquals("created", transport.ensureContainer(docker, target("D:\\中文 项目", "/work")));
        assertThrows(IllegalStateException.class, () -> transport.ensureContainer(docker, target("/different", "/work")));
        assertThrows(IllegalStateException.class, () -> transport.ensureContainer(docker, target(null, "/work")));
        verify(create, times(1)).exec();
        verify(docker, never()).removeContainerCmd(anyString());
    }
}
