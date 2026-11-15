package com.live_jgv.dashboard.infrastructure.web;

import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void invalidUpdateReturnsProblemDetailWithoutLeakingInput() throws Exception {
        mvc.perform(get("/test-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid score update"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void preservesMethodNotAllowedStatus() throws Exception {
        mvc.perform(post("/test-invalid")).andExpect(status().isMethodNotAllowed());
    }

    @RestController
    static class TestController {
        @GetMapping("/test-invalid")
        public void invalid() {
            throw new InvalidScoreUpdateException("Sensitive input must not be exposed");
        }
    }
}
