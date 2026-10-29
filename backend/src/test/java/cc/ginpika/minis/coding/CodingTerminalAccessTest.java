package cc.ginpika.minis.coding;

import cc.ginpika.minis.coding.api.CodingDtos.Capabilities;
import cc.ginpika.minis.coding.api.CodingTerminalController;
import cc.ginpika.minis.coding.api.CodingTerminalTickets;
import cc.ginpika.minis.coding.runner.CodingTerminals;
import cc.ginpika.minis.web.sso.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CodingTerminalAccessTest {
    private final CodingTerminals terminals=mock(CodingTerminals.class);
    private final CodingFacade coding=mock(CodingFacade.class);
    private final CodingTerminalTickets tickets=mock(CodingTerminalTickets.class);
    private MockMvc mvc;
    private final SsoUser user=new SsoUser("alice","alice","Alice","","user","");
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new CodingTerminalController(terminals,coding,tickets)).build();when(coding.capabilities()).thenReturn(new Capabilities(true,false,"",60,30,"workspace"));}
    @Test void allTerminalOperationsRequireAuthentication() throws Exception {
        for(var request:java.util.List.of(get("/api/coding/projects/p/terminals"),get("/api/coding/projects/p/terminals/t"),post("/api/coding/projects/p/terminals").contentType(MediaType.APPLICATION_JSON).content("{\"cols\":80,\"rows\":24}"),post("/api/coding/projects/p/terminals/t/input").contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"pwd\\r\"}"),post("/api/coding/projects/p/terminals/t/resize").contentType(MediaType.APPLICATION_JSON).content("{\"cols\":80,\"rows\":24}"),delete("/api/coding/projects/p/terminals/t"))){mvc.perform(request).andExpect(status().isUnauthorized());}
        verifyNoInteractions(terminals);
        mvc.perform(post("/api/coding/projects/p/terminals/t/connect").contentType(MediaType.APPLICATION_JSON).content("{\"after\":0}"))
                .andExpect(status().isUnauthorized());verifyNoInteractions(tickets);
    }
    @Test void humanInputAndProjectIdentityReachTheServiceWithoutUsingTheAgentRunner() throws Exception {
        mvc.perform(post("/api/coding/projects/p/terminals/t/input").requestAttr(SsoInterceptor.ATTR,user).contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"pwd\\r\"}")).andExpect(status().isOk());
        verify(terminals).input("alice","p","t","pwd\r");
        mvc.perform(delete("/api/coding/projects/p/terminals/t").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());verify(terminals).delete("alice","p","t");
    }
    @Test void invalidSessionIdentitiesReturnUnauthorizedInsteadOfCrashing() throws Exception {
        for(Object identity:java.util.List.of("alice",new SsoUser(null,"alice","Alice","","user",""),new SsoUser(" ","alice","Alice","","user",""))){
            mvc.perform(get("/api/coding/projects/p/terminals").requestAttr(SsoInterceptor.ATTR,identity))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("需要登录"));
        }
        verifyNoInteractions(terminals);
    }
    @Test void authenticatedListCreateReadAndResizeUseTheSessionOwner() throws Exception {
        when(terminals.create("alice","p",80,24)).thenReturn(new CodingTerminals.View("t","终端 1",true,null));
        when(terminals.read("alice","p","t",3)).thenReturn(new CodingTerminals.Output("",3,false,true,null));
        mvc.perform(get("/api/coding/projects/p/terminals").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        mvc.perform(post("/api/coding/projects/p/terminals").requestAttr(SsoInterceptor.ATTR,user).contentType(MediaType.APPLICATION_JSON).content("{\"cols\":80,\"rows\":24}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("t"));
        mvc.perform(get("/api/coding/projects/p/terminals/t?after=3").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isOk());
        mvc.perform(post("/api/coding/projects/p/terminals/t/resize").requestAttr(SsoInterceptor.ATTR,user).contentType(MediaType.APPLICATION_JSON).content("{\"cols\":100,\"rows\":30}"))
                .andExpect(status().isOk());
        verify(terminals).list("alice","p");verify(terminals).create("alice","p",80,24);verify(terminals).read("alice","p","t",3);verify(terminals).resize("alice","p","t",100,30);
    }
    @Test void connectionTicketsAreBoundToVerifiedOwnerTerminalCursorAndBrowserOrigin() throws Exception {
        mvc.perform(post("/api/coding/projects/p/terminals/t/connect").requestAttr(SsoInterceptor.ATTR,user).header("Origin","http://localhost:5173").contentType(MediaType.APPLICATION_JSON).content("{\"after\":42}"))
                .andExpect(status().isOk());
        verify(terminals).checkAccess("alice","p","t",42);verify(tickets).issue("alice","p","t",42,"http://localhost:5173");
    }
    @Test void disabledModuleAndRemoteDevelopmentIdentityCannotOpenAConnection() throws Exception {
        when(coding.capabilities()).thenReturn(new Capabilities(false,false,"",60,30,"workspace"));
        mvc.perform(get("/api/coding/projects/p/terminals").requestAttr(SsoInterceptor.ATTR,user)).andExpect(status().isServiceUnavailable());
        var dev=new SsoUser("local-dev","dev","Dev","","admin","");
        mvc.perform(get("/api/coding/projects/p/terminals").requestAttr(SsoInterceptor.ATTR,dev).with(request->{request.setRemoteAddr("192.168.1.5");return request;})).andExpect(status().isForbidden());verifyNoInteractions(terminals);
    }
}
