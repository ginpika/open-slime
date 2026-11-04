package cc.ginpika.minis.ai.computer.window;

import cc.ginpika.minis.ai.core.tool.ToolOutput;
import cc.ginpika.minis.config.DesktopProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs({OS.MAC, OS.LINUX})
class WindowCaptureTest {
    @TempDir Path directory;
    @Test void captureOnlySubprocessReturnsAnImageAndRevalidatesWindowIdentity() throws Exception {
        var path = directory.resolve("capture helper with spaces");
        Files.writeString(path, """
                #!/bin/sh
                if [ "$1" != '--computer-use' ]; then exit 2; fi
                if [ "$2" = 'list' ]; then
                  printf '%s' '{"ok":true,"result":[{"id":4,"pid":7,"title":"fixture","app":"fixture"}]}'
                elif [ "$2" = 'capture' ] && [ "$3" = '4' ] && [ "$4" = '7' ]; then
                  printf '%s' '{"ok":true,"result":{"image":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=","title":"fixture","capturedAt":100}}'
                else
                  printf '%s' '{"ok":false,"error":"窗口已关闭或进程不匹配"}'
                  exit 1
                fi
                """);
        assertTrue(path.toFile().setExecutable(true));
        var desktop = new DesktopProps(); desktop.setEnabled(true); desktop.setLocalToken("test-token");
        var capture = new WindowCapture(path.toString(), new ObjectMapper(), desktop);
        assertEquals(4, capture.windows().get(0).path("id").asInt());
        var content = ToolOutput.decode(capture.capture(4, 7));
        assertEquals(1, content.images().size()); assertTrue(content.text().contains("window_id"));
        assertThrows(IllegalStateException.class, () -> capture.capture(4, 8));
        assertThrows(IllegalArgumentException.class, () -> capture.capture(-1, 7));
    }
}
