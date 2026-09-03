package com.example.URLShortener.integration;

import com.example.URLShortener.models.ClickEvent;
import com.example.URLShortener.models.URL;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UrlApiIT extends IntegrationTestBase {

    @Test
    void createPersistsUrlAndReturnsAssessmentContract() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/articles/1","customAlias":"article1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("article1"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/article1"))
                .andExpect(jsonPath("$.longUrl").value("https://example.com/articles/1"));

        URL persisted = urlRepository.findByShortUrl("article1").orElseThrow();
        assertThat(persisted.getLongUrl()).isEqualTo("https://example.com/articles/1");
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void duplicateAliasReturnsStructuredConflict() throws Exception {
        saveUrl("existing", "https://example.com/first", null, true);

        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/second","customAlias":"existing"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/api/v1/shorten"));

        assertThat(urlRepository.count()).isEqualTo(1);
    }

    @Test
    void invalidRequestReturnsValidationDetailsWithoutWriting() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"not-a-url","customAlias":"alias too long"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.url").exists())
                .andExpect(jsonPath("$.fieldErrors.customAlias").exists());

        assertThat(urlRepository.count()).isZero();
    }

    @Test
    void redirectUsesPersistedDestination() throws Exception {
        saveUrl("go123", "https://example.com/destination", null, true);

        mockMvc.perform(get("/go123").header("Purpose", "prefetch"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/destination"));
    }

    @Test
    void unknownAndExpiredLinksReturnDomainErrors() throws Exception {
        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        saveUrl("expired", "https://example.com/old", LocalDateTime.now().minusMinutes(1), true);

        mockMvc.perform(get("/expired"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410));

        assertThat(urlRepository.findByShortUrl("expired").orElseThrow().isActive()).isFalse();
    }

    @Test
    void updateChangesDatabaseAndDeleteRemovesUrlAndClicks() throws Exception {
        saveUrl("manage1", "https://example.com/original", null, true);
        clickEventRepository.save(ClickEvent.builder()
                .shortUrl("manage1")
                .ipAddress("192.0.2.10")
                .userAgent("Integration Test")
                .country("US")
                .clickedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(put("/api/v1/shorten/manage1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/updated","expiresAt":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/updated"));

        assertThat(urlRepository.findByShortUrl("manage1").orElseThrow().getLongUrl())
                .isEqualTo("https://example.com/updated");

        mockMvc.perform(delete("/api/v1/shorten/manage1"))
                .andExpect(status().isNoContent());

        assertThat(urlRepository.findByShortUrl("manage1")).isEmpty();
        assertThat(clickEventRepository.countByShortUrl("manage1")).isZero();
    }

    @Test
    void analyticsReadsClickRecordsFromPostgres() throws Exception {
        saveUrl("stats1", "https://example.com/stats", null, true);
        clickEventRepository.save(ClickEvent.builder()
                .shortUrl("stats1")
                .ipAddress("192.0.2.1")
                .userAgent("Browser A")
                .country("US")
                .clickedAt(LocalDateTime.now().minusSeconds(1))
                .build());
        clickEventRepository.save(ClickEvent.builder()
                .shortUrl("stats1")
                .ipAddress("192.0.2.2")
                .userAgent("Browser B")
                .country("CA")
                .clickedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/analytics/stats1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("stats1"))
                .andExpect(jsonPath("$.clicks").value(2))
                .andExpect(jsonPath("$.uniqueVisitors").value(2))
                .andExpect(jsonPath("$.countries.US").value(1))
                .andExpect(jsonPath("$.countries.CA").value(1))
                .andExpect(jsonPath("$.recentClicks.length()").value(2));
    }

    private URL saveUrl(String shortCode, String longUrl, LocalDateTime expiresAt, boolean active) {
        return urlRepository.saveAndFlush(URL.builder()
                .shortUrl(shortCode)
                .longUrl(longUrl)
                .expiresAt(expiresAt)
                .active(active)
                .build());
    }
}
