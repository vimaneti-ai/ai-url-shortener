package com.example.URLShortener.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeoIpServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private HttpClient httpClient;
    private GeoIpService geoIpService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        httpClient = mock(HttpClient.class);

        geoIpService = new GeoIpService(redisTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(geoIpService, "httpClient", httpClient);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private boolean isIpwhoIs(HttpRequest request) {
        return request.uri().getHost().contains("ipwho.is");
    }

    @Test
    void resolveCountry_returnsUnknown_whenIpIsNull() {
        assertThat(geoIpService.resolveCountry(null)).isEqualTo("Unknown");
        verifyNoInteractions(httpClient);
    }

    @Test
    void resolveCountry_returnsUnknown_whenIpIsBlank() {
        assertThat(geoIpService.resolveCountry("  ")).isEqualTo("Unknown");
        verifyNoInteractions(httpClient);
    }

    @Test
    void resolveCountry_returnsUnknown_forLoopbackAddress() {
        assertThat(geoIpService.resolveCountry("127.0.0.1")).isEqualTo("Unknown");
        assertThat(geoIpService.resolveCountry("::1")).isEqualTo("Unknown");
        verifyNoInteractions(httpClient);
    }

    @Test
    void resolveCountry_returnsUnknown_forPrivateNetworkAddress() {
        assertThat(geoIpService.resolveCountry("10.0.0.5")).isEqualTo("Unknown");
        assertThat(geoIpService.resolveCountry("192.168.1.100")).isEqualTo("Unknown");
        verifyNoInteractions(httpClient);
    }

    @Test
    void resolveCountry_returnsCachedValue_withoutCallingHttpClient() throws Exception {
        when(valueOperations.get("geoip:8.8.8.8")).thenReturn("United States");

        assertThat(geoIpService.resolveCountry("8.8.8.8")).isEqualTo("United States");
        verifyNoInteractions(httpClient);
    }

    @Test
    void resolveCountry_usesPrimaryProvider_andCachesResult() throws Exception {
        doReturn(mockResponse(200, "{\"success\":true,\"country\":\"Germany\"}"))
                .when(httpClient).send(any(HttpRequest.class), any());

        String result = geoIpService.resolveCountry("8.8.8.8");

        assertThat(result).isEqualTo("Germany");
        verify(valueOperations).set(eq("geoip:8.8.8.8"), eq("Germany"), eq(Duration.ofHours(24)));
    }

    @Test
    void resolveCountry_fallsBackToSecondaryProvider_whenPrimaryFails() throws Exception {
        doReturn(mockResponse(500, "")).when(httpClient).send(argThat(this::isIpwhoIs), any());
        doReturn(mockResponse(200, "{\"status\":\"success\",\"country\":\"Japan\"}"))
                .when(httpClient).send(argThat(req -> !isIpwhoIs(req)), any());

        String result = geoIpService.resolveCountry("8.8.8.8");

        assertThat(result).isEqualTo("Japan");
        verify(valueOperations).set(eq("geoip:8.8.8.8"), eq("Japan"), eq(Duration.ofHours(24)));
    }

    @Test
    void resolveCountry_returnsUnknown_whenBothProvidersFail() throws Exception {
        doThrow(new RuntimeException("network down")).when(httpClient).send(any(HttpRequest.class), any());

        String result = geoIpService.resolveCountry("8.8.8.8");

        assertThat(result).isEqualTo("Unknown");
        verify(valueOperations).set(eq("geoip:8.8.8.8"), eq("Unknown"), eq(Duration.ofHours(24)));
    }

    @Test
    void resolveCountry_treatsUnsuccessfulPayload_asFailure() throws Exception {
        doReturn(mockResponse(200, "{\"success\":false,\"message\":\"Reserved range\"}"))
                .when(httpClient).send(argThat(this::isIpwhoIs), any());
        doReturn(mockResponse(200, "{\"status\":\"fail\",\"message\":\"reserved range\"}"))
                .when(httpClient).send(argThat(req -> !isIpwhoIs(req)), any());

        assertThat(geoIpService.resolveCountry("8.8.8.8")).isEqualTo("Unknown");
    }

    @Test
    void resolveCountry_retriesTransientProviderFailure() throws Exception {
        doReturn(mockResponse(503, ""), mockResponse(200, "{\"success\":true,\"country\":\"Canada\"}"))
                .when(httpClient).send(argThat(this::isIpwhoIs), any());

        assertThat(geoIpService.resolveCountry("8.8.8.8")).isEqualTo("Canada");
        verify(httpClient, times(2)).send(argThat(this::isIpwhoIs), any());
    }
}
