package com.example.URLShortener.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves an IP address to a country name for analytics breakdowns.
 *
 * Tries ipwho.is first, falling back to ip-api.com if it fails. (ipapi.co,
 * the original provider, now returns a Cloudflare bot-challenge page to
 * server-side clients regardless of User-Agent and is unusable from a
 * backend service.) Results are cached in Redis for 24h so repeat visitors
 * don't trigger a fresh external lookup on every click. Private/loopback
 * addresses and total lookup failure fall back to "Unknown" so a
 * geolocation hiccup never breaks click recording.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeoIpService {

    private static final String UNKNOWN = "Unknown";
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public String resolveCountry(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || isPrivateOrLoopback(ipAddress)) {
            return UNKNOWN;
        }

        String cacheKey = "geoip:" + ipAddress;
        String cached = withRetry("Redis GeoIP read", () -> redisTemplate.opsForValue().get(cacheKey), null);
        if (cached != null) {
            return cached;
        }

        String country = lookupIpwhoIs(ipAddress);
        if (country == null) {
            country = lookupIpApi(ipAddress);
        }
        if (country == null) {
            country = UNKNOWN;
        }

        String resolvedCountry = country;
        withRetry("Redis GeoIP write", () -> {
            redisTemplate.opsForValue().set(cacheKey, resolvedCountry, CACHE_TTL);
            return null;
        }, null);
        return country;
    }

    private String lookupIpwhoIs(String ip) {
        try {
            String body = get("https://ipwho.is/" + ip + "?fields=success,country");
            if (body == null) {
                return null;
            }
            Map<?, ?> json = objectMapper.readValue(body, Map.class);
            if (Boolean.TRUE.equals(json.get("success")) && json.get("country") instanceof String country
                    && !country.isBlank()) {
                return country;
            }
        } catch (Exception e) {
            log.debug("ipwho.is lookup failed for {}: {}", ip, e.getMessage());
        }
        return null;
    }

    private String lookupIpApi(String ip) {
        try {
            String body = get("http://ip-api.com/json/" + ip + "?fields=status,country");
            if (body == null) {
                return null;
            }
            Map<?, ?> json = objectMapper.readValue(body, Map.class);
            if ("success".equals(json.get("status")) && json.get("country") instanceof String country
                    && !country.isBlank()) {
                return country;
            }
        } catch (Exception e) {
            log.debug("ip-api.com lookup failed for {}: {}", ip, e.getMessage());
        }
        return null;
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                if (response.statusCode() < 500 && response.statusCode() != 429) {
                    return null;
                }
                lastFailure = new IllegalStateException("HTTP " + response.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                lastFailure = e;
            }

            if (attempt < MAX_ATTEMPTS) {
                backoff(attempt);
            }
        }
        throw lastFailure;
    }

    private <T> T withRetry(String operation, Supplier<T> action, T fallback) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("{} failed after {} attempts: {}", operation, MAX_ATTEMPTS, e.getMessage());
                    return fallback;
                }
                backoff(attempt);
            }
        }
        return fallback;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(INITIAL_BACKOFF_MS * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isPrivateOrLoopback(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isLinkLocalAddress();
        } catch (Exception e) {
            return true;
        }
    }
}
