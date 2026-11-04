package cc.ginpika.minis.ai.computer.terminal.exec;

import cc.ginpika.minis.ai.computer.terminal.ExecTarget;
import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore.TargetRow;
import cc.ginpika.minis.config.Branding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in test: creates and removes only its own container and fixture files. */
@EnabledIfEnvironmentVariable(named = "MINIS_TEST_DOCKER", matches = "true")
class DockerWorkspaceIntegrationTest {
    @Test void managedContainerSharesFilesWithHostAndStartsShellInBoundDirectory() throws Exception {
        String name = "mount-test-" + UUID.randomUUID();
        // Keep bind source inside the checkout so Docker Desktop can share an authorized directory.
        Path directory = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "workspace-mount-中文-");
        Files.writeString(directory.resolve("host.txt"), "host-content");
        String image = System.getenv().getOrDefault("MINIS_TEST_DOCKER_IMAGE", "alpine:latest");
        var target = new TargetRow(name, name, "docker", true, "/workspace", null, null, null, null,
                null, image, null, null, null, null, null, null, null, null, null, null, directory.toString(), 0, 0);
        var transport = new DockerExecTarget(new Branding());
        try {
            var result = transport.run(target, "cat host.txt; printf container-content > container.txt; pwd",
                    new ExecTarget.ExecOptions("/workspace", 10000L, 10000));
            assertTrue(result.ok(), result.stderr());
            assertTrue(result.stdout().contains("host-content"));
            assertTrue(result.stdout().contains("/workspace"));
            assertEquals("container-content", Files.readString(directory.resolve("container.txt")));
            // The second execution must reuse the same container and see the previous write.
            assertTrue(transport.run(target, "cat container.txt", new ExecTarget.ExecOptions("/workspace", 10000L, 10000)).stdout().contains("container-content"));
            try (var shell = transport.openShell(target, "cd /workspace && exec sh -i", 80, 24)) {
                shell.input().write("printf '\\nBOUND:%s\\n' \"$PWD\"\r".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                shell.input().flush();
                var output = new java.io.ByteArrayOutputStream();
                var reader = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    var read = reader.submit(() -> {
                        byte[] chunk = new byte[4096]; int count;
                        while ((count = shell.output().read(chunk)) >= 0) {
                            output.write(chunk, 0, count);
                            if (output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("BOUND:/workspace")) return true;
                        }
                        return false;
                    });
                    assertTrue(read.get(10, TimeUnit.SECONDS));
                } finally { reader.shutdownNow(); }
            }
        } finally {
            try {
                var cleanup = new ProcessBuilder("docker", "rm", "-f", new Branding().slug() + "-term-" + name).redirectErrorStream(true).start();
                if (!cleanup.waitFor(15, TimeUnit.SECONDS)) cleanup.destroyForcibly();
            } finally {
                transport.shutdown();
                Files.deleteIfExists(directory.resolve("host.txt"));
                Files.deleteIfExists(directory.resolve("container.txt"));
                Files.deleteIfExists(directory);
            }
        }
    }
}
