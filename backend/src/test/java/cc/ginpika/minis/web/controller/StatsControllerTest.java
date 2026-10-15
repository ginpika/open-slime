package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.stats.TokenUsageService;
import cc.ginpika.minis.stats.dto.StatsDtos;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StatsControllerTest {
    @Test void rejectedPriceAndMissingVersionReturnActionableMessages() throws Exception {
        var usage=mock(TokenUsageService.class);
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,"价格不能为负数")).when(usage).savePrice(any(StatsDtos.PriceRequest.class));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"价格版本不存在")).when(usage).deletePrice(42);
        var mvc=MockMvcBuilders.standaloneSetup(new StatsController(usage)).build();
        mvc.perform(put("/api/stats/prices").contentType("application/json").content("{\"provider\":\"\",\"model\":\"m\",\"effectiveFrom\":\"2026-10-01\",\"currency\":\"CNY\",\"inputPerMillion\":\"-1\",\"outputPerMillion\":\"0\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("价格不能为负数"));
        mvc.perform(delete("/api/stats/prices/42")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("价格版本不存在"));
    }
    @Test void calendarRangeAndProviderModelFiltersArePassedTogether() throws Exception {
        var usage=mock(TokenUsageService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new StatsController(usage)).build();
        mvc.perform(get("/api/stats").param("from","2026-10-01").param("to","2026-10-15").param("source","coding")
                .param("model","m").param("provider","https://fixture.invalid/v1")).andExpect(status().isOk());
        verify(usage).view(30,java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-10-15"),"coding","https://fixture.invalid/v1","m");
        mvc.perform(get("/api/stats").param("from","invalid-date")).andExpect(status().isBadRequest());
    }
}

