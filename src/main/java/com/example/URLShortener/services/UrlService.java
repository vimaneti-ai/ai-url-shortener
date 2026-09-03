package com.example.URLShortener.services;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.dto.URLUpdateRequest;
import com.example.URLShortener.models.URL;
import com.example.URLShortener.repository.ClickEventRepository;
import com.example.URLShortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int CACHE_MAX_ATTEMPTS = 3;
    private static final long CACHE_BACKOFF_MS = 50;
    private final UrlRepository urlRepository;
    private final ClickEventRepository clickEventRepository;
    private final StringRedisTemplate redisTemplate;
    @Value("${app.base-url:http://localhost:8080/api/v1}")
    private String baseUrl;

    private String shortKey(String shortCode) {
        return "short:" + shortCode;
    }

    private String longKey(String longUrl) {
        return "long:" + longUrl;
    }

    @Transactional
    public URLResponse createShortUrl(URLRequest request) {
        String longUrl = request.getLongUrl();
        LocalDateTime expirationTime = request.getExpiresAt();

        Optional<URL> duplicate = findActiveDuplicate(longUrl);
        if (duplicate.isPresent()) {
            URL existing = duplicate.get();
            if (isExpired(existing)) {
                deactivate(existing);
                evictCache(existing.getShortUrl(), longUrl);
            } else {
                cacheMapping(existing);
                return toResponse(existing);
            }
        }

        String desiredShortCode = request.getCustomAlias();
        if (desiredShortCode != null && !desiredShortCode.isBlank()) {
            if (urlRepository.existsByShortUrl(desiredShortCode)) {
                throw new AliasAlreadyExistsException("Custom alias already exists: " + desiredShortCode);
            }
        }

        URL url = URL.builder()
                .longUrl(longUrl)
                .expiresAt(expirationTime)
                .active(true)
                .shortUrl(desiredShortCode != null && !desiredShortCode.isBlank() ? desiredShortCode : "T" + (int)(Math.random() * 9000000 + 1000000))
                .build();

        // first save to generate numeric ID
        URL saved = urlRepository.save(url);

        String shortCode = desiredShortCode;
        if (shortCode == null || shortCode.isBlank()) {
            shortCode = Base62Encoder.encode(saved.getId());
        }

        saved.setShortUrl(shortCode);
        log.debug("Generated short code={}", shortCode);
        URL finalEntity = urlRepository.save(saved);

        cacheMapping(finalEntity);

        return toResponse(finalEntity);
    }

    @Transactional
    public String resolveLongUrl(String shortCode) {
        // check cache first
        String cachedLong = readCache(shortKey(shortCode));
        if (cachedLong != null) {
            // even on cache hit, verify the URL has not expired
            Optional<URL> optionalUrl = urlRepository.findByShortUrlAndActiveTrue(shortCode);
            if (optionalUrl.isPresent() && isExpired(optionalUrl.get())) {
                deactivate(optionalUrl.get());
                evictCache(shortCode, cachedLong);
                throw new UrlExpiredException("Short URL has expired: " + shortCode);
            }
            return cachedLong;
        }

        Optional<URL> optionalUrl = urlRepository.findByShortUrlAndActiveTrue(shortCode);
        URL url = optionalUrl.orElseThrow(() -> new UrlNotFoundException("Short URL not found: " + shortCode));

        if (isExpired(url)) {
            // mark inactive and throw dedicated exception
            deactivate(url);
            throw new UrlExpiredException("Short URL has expired: " + shortCode);
        }

        cacheMapping(url);
        return url.getLongUrl();
    }

    @Transactional
    public URLResponse updateShortUrl(String shortCode, URLUpdateRequest request) {
        URL url = urlRepository.findByShortUrl(shortCode)
                .orElseThrow(() -> new UrlNotFoundException("Short URL not found: " + shortCode));

        String oldLongUrl = url.getLongUrl();
        evictCache(shortCode, oldLongUrl);

        url.setLongUrl(request.getLongUrl());
        url.setExpiresAt(request.getExpiresAt());
        url.setActive(request.getExpiresAt() == null || LocalDateTime.now().isBefore(request.getExpiresAt()));

        URL saved = urlRepository.save(url);
        cacheMapping(saved);

        return toResponse(saved);
    }

    @Transactional
    public void deleteShortUrl(String shortCode) {
        URL url = urlRepository.findByShortUrl(shortCode)
                .orElseThrow(() -> new UrlNotFoundException("Short URL not found: " + shortCode));

        clickEventRepository.deleteByShortUrl(shortCode);
        urlRepository.delete(url);
        evictCache(shortCode, url.getLongUrl());
    }

    /**
     * Finds an existing active mapping for longUrl, checking Redis first as a
     * fast path and falling back to a direct DB lookup by longUrl. This makes
     * duplicate detection authoritative on the database rather than dependent
     * on the 5-minute cache TTL still being warm (e.g. after a Redis restart,
     * or simply after the TTL has elapsed since the URL was first shortened).
     */
    private Optional<URL> findActiveDuplicate(String longUrl) {
        String cachedShortCode = readCache(longKey(longUrl));
        if (cachedShortCode != null) {
            Optional<URL> cached = urlRepository.findByShortUrlAndActiveTrue(cachedShortCode);
            if (cached.isPresent()) {
                return cached;
            }
            // stale cache entry pointing at a URL that no longer exists or is inactive
            deleteCache(longKey(longUrl));
        }
        return urlRepository.findByLongUrlAndActiveTrue(longUrl);
    }

    private void cacheMapping(URL url) {
        if (!url.isActive()) {
            return;
        }
        String code = url.getShortUrl();
        String longUrl = url.getLongUrl();
        writeCache(shortKey(code), longUrl);
        writeCache(longKey(longUrl), code);
    }

    private boolean isExpired(URL url) {
        LocalDateTime expiresAt = url.getExpiresAt();
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    private void deactivate(URL url) {
        if (url.isActive()) {
            url.setActive(false);
            urlRepository.save(url);
        }
    }

    private void evictCache(String shortCode, String longUrl) {
        deleteCache(shortKey(shortCode));
        deleteCache(longKey(longUrl));
    }

    private String readCache(String key) {
        return withCacheRetry("read", key, () -> redisTemplate.opsForValue().get(key), null);
    }

    private void writeCache(String key, String value) {
        withCacheRetry("write", key, () -> {
            redisTemplate.opsForValue().set(key, value, CACHE_TTL);
            return null;
        }, null);
    }

    private void deleteCache(String key) {
        withCacheRetry("delete", key, () -> redisTemplate.delete(key), null);
    }

    private <T> T withCacheRetry(String operation, String key, Supplier<T> action, T fallback) {
        for (int attempt = 1; attempt <= CACHE_MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (attempt == CACHE_MAX_ATTEMPTS) {
                    log.warn("Redis cache {} failed after {} attempts for key={}: {}",
                            operation, CACHE_MAX_ATTEMPTS, key, e.getMessage());
                    return fallback;
                }
                try {
                    Thread.sleep(CACHE_BACKOFF_MS * (1L << (attempt - 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private URLResponse toResponse(URL url) {
        String shortCode = url.getShortUrl();
        String fullShortUrl = baseUrl.endsWith("/")
                ? baseUrl + shortCode
                : baseUrl + "/" + shortCode;

        return URLResponse.builder()
                .shortUrl(fullShortUrl)
                .shortCode(shortCode)
                .longUrl(url.getLongUrl())
                .expiresAt(url.getExpiresAt())
                .build();
    }

    public static class AliasAlreadyExistsException extends RuntimeException {
        public AliasAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class UrlNotFoundException extends RuntimeException {
        public UrlNotFoundException(String message) {
            super(message);
        }
    }

    public static class UrlExpiredException extends RuntimeException {
        public UrlExpiredException(String message) {
            super(message);
        }
    }
}
