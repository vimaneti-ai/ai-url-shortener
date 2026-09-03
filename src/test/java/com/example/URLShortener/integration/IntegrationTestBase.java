package com.example.URLShortener.integration;

import com.example.URLShortener.repository.ClickEventRepository;
import com.example.URLShortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("url_shortener_test")
            .withUsername("test_user")
            .withPassword("test_password");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UrlRepository urlRepository;

    @Autowired
    protected ClickEventRepository clickEventRepository;

    @MockitoBean
    protected StringRedisTemplate redisTemplate;

    @MockitoBean
    protected KafkaTemplate<String, String> kafkaTemplate;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void resetDatabaseAndExternalMocks() {
        clickEventRepository.deleteAll();
        urlRepository.deleteAll();

        reset(redisTemplate, kafkaTemplate);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }
}
