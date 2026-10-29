package cc.ginpika.minis.web.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cc.ginpika.minis.todo.TodoService;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class PersonalTodoControllerTest {
    final TodoService service=mock(TodoService.class);
    final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new PersonalTodoController(service)).build();
    final SsoUser user=new SsoUser("person","name","名字","","user","");

    @Test void requiresAuthenticatedIdentityAndNeverAcceptsClientOwner() throws Exception {
        mvc.perform(get("/api/todos/overview")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/todos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"想法\",\"ownerId\":\"another-person\"}")
                .requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        verify(service).create(eq("person"),argThat(req->req.title().equals("想法")));
        mvc.perform(get("/api/todos/overview").param("zone","Asia/Shanghai").param("projectId","p")
                .requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        verify(service).overview("person","Asia/Shanghai","p");
        mvc.perform(get("/api/todos/review").param("month","2026-10").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        verify(service).review("person","2026-10","UTC",null);
        mvc.perform(get("/api/todos/projects").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        verify(service).projectSummaries("person");
        mvc.perform(get("/api/todos/day").param("day","2026-10-15").param("zone","Asia/Shanghai").param("projectId","p")
                .requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        verify(service).day("person","2026-10-15","Asia/Shanghai","p");
    }

    @Test void scopedErrorsAreReadableAndMutationsUseAuthenticatedOwner() throws Exception {
        when(service.detail("person","hidden")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"事项不存在"));
        mvc.perform(get("/api/todos/hidden").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("事项不存在"));
        mvc.perform(post("/api/todos/t/start").requestAttr(SsoInterceptor.ATTR,user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedActiveId\":null}")).andExpect(status().isOk());
        verify(service).start(eq("person"),eq("t"),any());
        mvc.perform(post("/api/focus-sessions/f/stop").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        verify(service).stop("person","f");
        mvc.perform(delete("/api/focus-sessions/f").param("version","3").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        verify(service).deleteFocus("person","f",3);
        mvc.perform(put("/api/day-notes/2026-10-16").requestAttr(SsoInterceptor.ATTR,user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"留一句话\",\"version\":-1}")).andExpect(status().isOk());
        verify(service).saveNote(eq("person"),eq("2026-10-16"),eq("UTC"),any());
    }
}
