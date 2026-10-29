package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.project.ProjectKnowledgeStore;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectKnowledgeControllerTest {
    @Test void authenticationIsRequiredForReadsAndWritesAndHumanEditsHaveAttribution() throws Exception {
        var knowledge=mock(ProjectKnowledgeStore.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ProjectKnowledgeController(knowledge)).build();
        for(String path:new String[]{"","/progress","/documents/overview","/documents/overview/history"})
            mvc.perform(get("/api/projects/p/knowledge"+path)).andExpect(status().isUnauthorized());
        String body="{\"kind\":\"overview\",\"title\":\"现状\",\"content\":\"尚未验证\",\"expectedVersion\":0,\"retired\":false}";
        mvc.perform(put("/api/projects/p/knowledge/documents/overview").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        verifyNoInteractions(knowledge);
        var alice=new SsoUser("alice","alice","用户","","user","");
        mvc.perform(put("/api/projects/p/knowledge/documents/overview").requestAttr(SsoInterceptor.ATTR,alice).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(knowledge).save(eq("p"),eq("overview"),any(),isNull(),eq("user:alice"));
    }
}
