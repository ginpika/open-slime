package cc.ginpika.minis.ai.knowledge.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cc.ginpika.minis.web.controller.WebSnapshotController;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebSnapshotControllerTest {
    @Test void closedBrowserReturnsParseableJsonNull() throws Exception {
        var browser = mock(WebBrowserService.class);
        var controller = new WebSnapshotController(browser,mock(WebSnapshotService.class),mock(WebSnapshotStore.class),new ObjectMapper());
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/web/browser")
                .requestAttr(SsoInterceptor.ATTR,SsoUser.dev()).contentType("application/json")
                .content("{\"browserId\":\"owned-browser\",\"action\":\"close\"}"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string("null"));
        verify(browser).command(anyString(),isNull(),argThat(command->command.action().equals("close")&&command.browserId().equals("owned-browser")),eq(true));
    }

    @Test void imageCatalogAndZipAreServedFromSavedSnapshotAndEmptyZipIsRejected() throws Exception {
        var store=mock(WebSnapshotStore.class);
        var controller=new WebSnapshotController(mock(WebBrowserService.class),mock(WebSnapshotService.class),store,new ObjectMapper());
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        when(store.get("saved-snapshot")).thenReturn(SnapshotImagesTest.snapshot("<img src='data:image/png;base64,YWJj' alt='Portrait.png'>"));
        mvc.perform(get("/api/web/snapshots/saved-snapshot/images")).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageCount").value(1)).andExpect(jsonPath("$.totalBytes").value(3));
        var result=mvc.perform(get("/api/web/snapshots/saved-snapshot/images.zip"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/zip"))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"snapshot-saved-snapshot-images.zip\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        when(store.get("saved-snapshot")).thenReturn(SnapshotImagesTest.snapshot("<p>No saved image</p>"));
        mvc.perform(get("/api/web/snapshots/saved-snapshot/images.zip")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("该快照没有可导出的离线图片"));
    }

    @Test void renameReturnsSmallMetadataResponseAndInvalidTitlesAreReported() throws Exception {
        var store=mock(WebSnapshotStore.class);
        var controller=new WebSnapshotController(mock(WebBrowserService.class),mock(WebSnapshotService.class),store,new ObjectMapper());
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        when(store.rename("saved-snapshot","New name")).thenReturn(new WebDtos.Renamed("saved-snapshot","New name"));
        mvc.perform(patch("/api/web/snapshots/saved-snapshot/title").contentType("application/json").content("{\"title\":\"New name\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("New name"))
                .andExpect(jsonPath("$.sourceHtml").doesNotExist());
        when(store.rename("saved-snapshot","")).thenThrow(new IllegalArgumentException("快照名称不能为空"));
        mvc.perform(patch("/api/web/snapshots/saved-snapshot/title").contentType("application/json").content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("快照名称不能为空"));
    }
}
