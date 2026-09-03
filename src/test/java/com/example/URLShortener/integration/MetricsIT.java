package com.example.URLShortener.integration;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetricsIT extends IntegrationTestBase {

    @Test
    void healthExposesOnlyTheAggregateStatus() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$").value(hasKey("status")))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void metricsEndpointListsRuntimeAndHttpMetrics() throws Exception {
        // Generate one application request so the HTTP meter is registered.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names", hasItem("jvm.memory.used")))
                .andExpect(jsonPath("$.names", hasItem("process.cpu.usage")))
                .andExpect(jsonPath("$.names", hasItem("http.server.requests")));
    }

    @Test
    void prometheusEndpointExportsMachineReadableMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("process_cpu_usage")));
    }
}
