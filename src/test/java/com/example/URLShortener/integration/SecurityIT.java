package com.example.URLShortener.integration;

import com.example.URLShortener.models.URL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.rate-limit.requests-per-minute=3")
class SecurityIT extends IntegrationTestBase {

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-url",
            "https://",
            "ftp://example.com/file",
            "javascript:alert(1)"
    })
    void invalidAndUnsafeUrlsAreRejected(String destination) throws Exception {
        String clientIp = "198.51.100." + (100 + Math.floorMod(destination.hashCode(), 100));
        mockMvc.perform(post("/api/v1/shorten")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"%s","customAlias":"safe1"}
                                """.formatted(destination)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.url").exists());

        assertThat(urlRepository.count()).isZero();
    }

    @Test
    void sqlInjectionPayloadIsHandledAsDataAndSchemaRemainsIntact() throws Exception {
        String injection = "https://example.com/search?q='OR'1'='1";

        mockMvc.perform(post("/api/v1/shorten")
                        .header("X-Forwarded-For", "198.51.100.11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"%s","customAlias":"sqlsafe"}
                                """.formatted(injection)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("sqlsafe"));

        assertThat(urlRepository.count()).isEqualTo(1);
        assertThat(urlRepository.findByShortUrl("sqlsafe").orElseThrow().getLongUrl())
                .isEqualTo(injection);

        mockMvc.perform(post("/api/v1/shorten")
                        .header("X-Forwarded-For", "198.51.100.12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.org","customAlias":"' OR 1=1--"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(urlRepository.count()).isEqualTo(1);
    }

    @Test
    void xssPayloadsAreRejectedAndNeverPersisted() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .header("X-Forwarded-For", "198.51.100.13")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/<script>alert(1)</script>","customAlias":"safe2"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.fieldErrors.url").exists());

        mockMvc.perform(post("/api/v1/shorten")
                        .header("X-Forwarded-For", "198.51.100.14")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com","customAlias":"<img>"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.customAlias").exists());

        assertThat(urlRepository.count()).isZero();
    }

    @Test
    void expiredLinkReturnsGoneWithoutPublishingClickEvent() throws Exception {
        urlRepository.saveAndFlush(URL.builder()
                .shortUrl("oldlink")
                .longUrl("https://example.com/expired")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .active(true)
                .build());

        mockMvc.perform(get("/oldlink"))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(410));

        verifyNoInteractions(kafkaTemplate);
        assertThat(urlRepository.findByShortUrl("oldlink").orElseThrow().isActive()).isFalse();
    }

    @Test
    void requestsOverConfiguredLimitReturn429AndRetryHeaders() throws Exception {
        String clientIp = "198.51.100.20";
        for (int request = 1; request <= 3; request++) {
            mockMvc.perform(post("/api/v1/shorten")
                            .header("X-Forwarded-For", clientIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"url":"https://example.com/rate/%d","customAlias":"rate%d"}
                                    """.formatted(request, request)))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("X-Rate-Limit-Remaining"));
        }

        mockMvc.perform(post("/api/v1/shorten")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/rate/4","customAlias":"rate4"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("X-Rate-Limit-Remaining", "0"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(60));

        assertThat(urlRepository.count()).isEqualTo(3);
    }
}
