package cc.ginpika.minis.ai.computer.terminal.exec;

import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore.TargetRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.SessionChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshExecTargetTest {
    private static final TargetRow TARGET = new TargetRow("fixture", "fixture", "ssh", true,
            "/work", 30000L, 100000, null, null, null, null, null,
            "host", 22, "user", "password", "secret", null, null, "accept-all", null, null, 0, 0);

    @Test void opensIndependentPtySessionsWithInputOutputResizeAndCleanup() throws Exception {
        var channel = mock(SessionChannel.class);
        var output = new ByteArrayInputStream(new byte[]{1, 2, 3});
        var input = new ByteArrayOutputStream();
        when(channel.exec(anyString())).thenReturn(channel);
        when(channel.getInputStream()).thenReturn(output);
        when(channel.getOutputStream()).thenReturn(input);
        try (var clients = mockConstruction(SSHClient.class, (ssh, context) -> when(ssh.startSession()).thenReturn(channel))) {
            var target = new SshExecTarget();
            var first = target.openShell(TARGET, "exec bash -i", 80, 24);
            var second = target.openShell(TARGET, "exec bash -i", 100, 30);
            assertEquals(2, clients.constructed().size(), "each human shell owns its SSH connection");
            assertSame(output, first.output());
            assertSame(input, first.input());
            verify(channel).allocatePTY("xterm-256color", 80, 24, 0, 0, Map.of());
            verify(channel, times(2)).exec("exec sh -lc 'exec bash -i'");
            first.resize(120, 35);
            verify(channel).changeWindowDimensions(120, 35, 0, 0);
            first.close();
            verify(clients.constructed().getFirst()).close();
            verify(clients.constructed().getLast(), never()).close();
            second.close();
            verify(clients.constructed().getLast()).close();
        }
    }

    @Test void closesConnectionWhenSessionCloseFails() throws Exception {
        var channel = mock(SessionChannel.class);
        when(channel.exec(anyString())).thenReturn(channel);
        doThrow(new IOException("session close failed")).when(channel).close();
        try (var clients = mockConstruction(SSHClient.class, (ssh, context) -> when(ssh.startSession()).thenReturn(channel))) {
            var shell = new SshExecTarget().openShell(TARGET, "exec sh -i", 80, 24);
            assertThrows(IOException.class, shell::close);
            verify(clients.constructed().getFirst()).close();
        }
    }

    @Test void preservesStartupFailureWhenConnectionCleanupAlsoFails() throws Exception {
        var channel = mock(SessionChannel.class);
        var failure = new net.schmizz.sshj.connection.ConnectionException("PTY denied");
        when(channel.exec(anyString())).thenThrow(failure);
        var cleanup = new IOException("connection close failed");
        try (var clients = mockConstruction(SSHClient.class, (ssh, context) -> {
            when(ssh.startSession()).thenReturn(channel);
            doThrow(cleanup).when(ssh).close();
        })) {
            var error = assertThrows(IOException.class,
                    () -> new SshExecTarget().openShell(TARGET, "exec sh -i", 80, 24));
            assertSame(failure, error);
            assertArrayEquals(new Throwable[]{cleanup}, error.getSuppressed());
            verify(clients.constructed().getFirst()).close();
        }
    }
}
