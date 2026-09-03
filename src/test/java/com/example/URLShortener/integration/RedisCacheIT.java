package com.example.URLShortener.integration;

import com.example.URLShortener.dto.URLUpdateRequest;
import com.example.URLShortener.models.URL;
import com.example.URLShortener.repository.ClickEventRepository;
import com.example.URLShortener.repository.UrlRepository;
import com.example.URLShortener.services.UrlService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
class RedisCacheIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("url_shortener_cache_test")
            .withUsername("test_user")
            .withPassword("test_password");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private UrlService urlService;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private ClickEventRepository clickEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void cleanState() throws Exception {
        clickEventRepository.deleteAll();
        urlRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        REDIS.execInContainer("redis-cli", "CONFIG", "RESETSTAT");
        statistics().clear();
    }

    @Test
    void firstLookupMissesAndSecondLookupHitsRedisWithoutAnotherDatabaseQuery() throws Exception {
        saveUrl("cache1", "https://example.com/cached", null);
        statistics().clear();

        assertThat(urlService.resolveLongUrl("cache1")).isEqualTo("https://example.com/cached");
        long queriesAfterMiss = statistics().getQueryExecutionCount();

        assertThat(urlService.resolveLongUrl("cache1")).isEqualTo("https://example.com/cached");

        Map<String, Long> redisStats = redisStats();
        assertThat(queriesAfterMiss).isEqualTo(1);
        assertThat(statistics().getQueryExecutionCount()).isEqualTo(queriesAfterMiss);
        assertThat(redisStats.get("keyspace_misses")).isEqualTo(1);
        assertThat(redisStats.get("keyspace_hits")).isEqualTo(1);
        assertThat(hitRatio(redisStats)).isEqualTo(0.5);
    }

    @Test
    void cacheEntryExpiresNoLaterThanTheShortLink() {
        saveUrl("ttl1", "https://example.com/temporary", LocalDateTime.now().plusSeconds(2));

        assertThat(urlService.resolveLongUrl("ttl1")).isEqualTo("https://example.com/temporary");
        Long ttlSeconds = redisTemplate.getExpire("short:ttl1");

        assertThat(ttlSeconds).isNotNull().isPositive().isLessThanOrEqualTo(2);
        await().atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(redisTemplate.hasKey("short:ttl1")).isFalse());
    }

    @Test
    void updateEvictsOldMappingAndCachesUpdatedDestination() {
        saveUrl("edit1", "https://example.com/old", null);
        urlService.resolveLongUrl("edit1");

        URLUpdateRequest update = new URLUpdateRequest();
        update.setUrl("https://example.com/new");
        urlService.updateShortUrl("edit1", update);

        assertThat(redisTemplate.hasKey("long:https://example.com/old")).isFalse();
        assertThat(redisTemplate.opsForValue().get("short:edit1")).isEqualTo("https://example.com/new");
        assertThat(redisTemplate.opsForValue().get("long:https://example.com/new")).isEqualTo("edit1");
    }

    @Test
    void deleteEvictsBothCacheDirections() {
        saveUrl("delete1", "https://example.com/delete", null);
        urlService.resolveLongUrl("delete1");

        urlService.deleteShortUrl("delete1");

        assertThat(redisTemplate.hasKey("short:delete1")).isFalse();
        assertThat(redisTemplate.hasKey("long:https://example.com/delete")).isFalse();
    }

    private URL saveUrl(String shortCode, String longUrl, LocalDateTime expiresAt) {
        return urlRepository.saveAndFlush(URL.builder()
                .shortUrl(shortCode)
                .longUrl(longUrl)
                .expiresAt(expiresAt)
                .active(true)
                .build());
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private Map<String, Long> redisStats() throws Exception {
        String output = REDIS.execInContainer("redis-cli", "INFO", "stats").getStdout();
        java.util.HashMap<String, Long> values = new java.util.HashMap<>();
        output.lines()
                .filter(line -> line.startsWith("keyspace_hits:") || line.startsWith("keyspace_misses:"))
                .forEach(line -> {
                    String[] parts = line.trim().split(":", 2);
                    values.put(parts[0], Long.parseLong(parts[1]));
                });
        return values;
    }

    private double hitRatio(Map<String, Long> stats) {
        long hits = stats.getOrDefault("keyspace_hits", 0L);
        long misses = stats.getOrDefault("keyspace_misses", 0L);
        return (double) hits / (hits + misses);
    }
}
