package com.example.URLShortener.services;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.dto.URLUpdateRequest;
import com.example.URLShortener.models.URL;
import com.example.URLShortener.repository.ClickEventRepository;
import com.example.URLShortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UrlServiceTest {

    private UrlRepository urlRepository;
    private ClickEventRepository clickEventRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        clickEventRepository = mock(ClickEventRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        urlService = new UrlService(urlRepository, clickEventRepository, redisTemplate);
        org.springframework.test.util.ReflectionTestUtils.setField(urlService, "baseUrl", "http://localhost:8080");
    }

    @Test
    void createShortUrl_generatesBase62FromId_whenNoCustomAlias() {
        URLRequest request = new URLRequest();
        request.setUrl("https://example.com");

        when(urlRepository.save(any(URL.class))).thenAnswer(invocation -> {
            URL u = invocation.getArgument(0);
            if (u.getId() == null) {
                u.setId(1);
            }
            return u;
        });

        URLResponse response = urlService.createShortUrl(request);

        assertThat(response.getShortCode()).isNotBlank();
        assertThat(response.getLongUrl()).isEqualTo("https://example.com");
        verify(urlRepository, times(2)).save(any(URL.class));
        verify(valueOperations, atLeastOnce()).set(startsWith("short:"), eq("https://example.com"), any());
        verify(valueOperations, atLeastOnce()).set(startsWith("long:"), anyString(), any());
    }

    @Test
    void createShortUrl_throwsConflict_whenCustomAliasExists() {
        URLRequest request = new URLRequest();
        request.setUrl("https://example.com");
        request.setCustomAlias("alias");

        when(urlRepository.existsByShortUrl("alias")).thenReturn(true);

        assertThrows(UrlService.AliasAlreadyExistsException.class, () -> urlService.createShortUrl(request));
        verify(urlRepository).existsByShortUrl("alias");
    }

    @Test
    void createShortUrl_returnsExisting_whenCacheHitsActiveDuplicate() {
        URL existing = URL.builder().id(1).shortUrl("abc123").longUrl("https://example.com").active(true).build();
        when(valueOperations.get("long:https://example.com")).thenReturn("abc123");
        when(urlRepository.findByShortUrlAndActiveTrue("abc123")).thenReturn(Optional.of(existing));

        URLRequest request = new URLRequest();
        request.setUrl("https://example.com");

        URLResponse response = urlService.createShortUrl(request);

        assertThat(response.getShortCode()).isEqualTo("abc123");
        verify(urlRepository, never()).save(any(URL.class));
        verify(urlRepository, never()).findByLongUrlAndActiveTrue(anyString());
    }

    @Test
    void createShortUrl_returnsExisting_viaDbLookup_whenCacheMiss() {
        URL existing = URL.builder().id(1).shortUrl("abc123").longUrl("https://example.com").active(true).build();
        when(valueOperations.get("long:https://example.com")).thenReturn(null);
        when(urlRepository.findByLongUrlAndActiveTrue("https://example.com")).thenReturn(Optional.of(existing));

        URLRequest request = new URLRequest();
        request.setUrl("https://example.com");

        URLResponse response = urlService.createShortUrl(request);

        assertThat(response.getShortCode()).isEqualTo("abc123");
        verify(urlRepository, never()).save(any(URL.class));
        verify(valueOperations).set(eq("long:https://example.com"), eq("abc123"), any());
    }

    @Test
    void createShortUrl_evictsStaleCache_andCreatesNew_whenCachedShortCodeIsGone() {
        when(valueOperations.get("long:https://example.com")).thenReturn("stale123");
        when(urlRepository.findByShortUrlAndActiveTrue("stale123")).thenReturn(Optional.empty());
        when(urlRepository.findByLongUrlAndActiveTrue("https://example.com")).thenReturn(Optional.empty());
        when(urlRepository.save(any(URL.class))).thenAnswer(invocation -> {
            URL u = invocation.getArgument(0);
            if (u.getId() == null) {
                u.setId(5);
            }
            return u;
        });

        URLRequest request = new URLRequest();
        request.setUrl("https://example.com");

        URLResponse response = urlService.createShortUrl(request);

        verify(redisTemplate).delete("long:https://example.com");
        assertThat(response.getShortCode()).isNotBlank();
        verify(urlRepository, times(2)).save(any(URL.class));
    }

    @Test
    void createShortUrl_createsNew_whenExistingDuplicateIsExpired() {
        URL expired = URL.builder().id(1).shortUrl("old123").longUrl("https://example.com")
                .expiresAt(LocalDateTime.now().minusMinutes(1)).active(true).build();
        when(valueOperations.get("long:https://example.com")).thenReturn(null);
        when(urlRepository.findByLongUrlAndActiveTrue("https://example.com")).thenReturn(Optional.of(expired));
        when(urlRepository.save(any(URL.class))).thenAnswer(invocation -> {
            URL u = invocation.getArgument(0);
            if (u.getId() == null) {
                u.setId(9);
            }
            return u;
        });

        URLRequest request = new URLRequest();
        request.setUrl("https://example.com");

        URLResponse response = urlService.createShortUrl(request);

        assertThat(response.getShortCode()).isNotEqualTo("old123");
        verify(redisTemplate).delete("short:old123");
        verify(redisTemplate).delete("long:https://example.com");
        verify(urlRepository, times(3)).save(any(URL.class));
    }

    @Test
    void resolveLongUrl_returnsFromCache_whenPresent() {
        when(valueOperations.get("short:code")).thenReturn("https://cached.com");

        String result = urlService.resolveLongUrl("code");

        assertThat(result).isEqualTo("https://cached.com");
        verifyNoInteractions(urlRepository);
    }

    @Test
    void resolveLongUrl_retriesRedisThenFallsBackToDatabase() {
        URL entity = URL.builder().id(1).shortUrl("code").longUrl("https://database.com").active(true).build();
        when(valueOperations.get("short:code"))
                .thenThrow(new RuntimeException("Redis temporarily unavailable"))
                .thenThrow(new RuntimeException("Redis temporarily unavailable"))
                .thenReturn(null);
        when(urlRepository.findByShortUrlAndActiveTrue("code")).thenReturn(Optional.of(entity));

        assertThat(urlService.resolveLongUrl("code")).isEqualTo("https://database.com");
        verify(valueOperations, times(3)).get("short:code");
    }

    @Test
    void resolveLongUrl_throwsExpired_whenPastExpiration() {
        URL entity = URL.builder()
                .id(1)
                .shortUrl("code")
                .longUrl("https://expired.com")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .active(true)
                .build();

        when(urlRepository.findByShortUrlAndActiveTrue("code")).thenReturn(Optional.of(entity));

        assertThrows(UrlService.UrlExpiredException.class, () -> urlService.resolveLongUrl("code"));
        ArgumentCaptor<URL> captor = ArgumentCaptor.forClass(URL.class);
        verify(urlRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void resolveLongUrl_throwsNotFound_whenMissing() {
        when(urlRepository.findByShortUrlAndActiveTrue("missing")).thenReturn(Optional.empty());

        assertThrows(UrlService.UrlNotFoundException.class, () -> urlService.resolveLongUrl("missing"));
    }

    @Test
    void updateShortUrl_updatesLongUrlAndExpiry_whenFound() {
        URL entity = URL.builder().id(1).shortUrl("code").longUrl("https://old.com").active(true).build();
        when(urlRepository.findByShortUrl("code")).thenReturn(Optional.of(entity));
        when(urlRepository.save(any(URL.class))).thenAnswer(invocation -> invocation.getArgument(0));

        URLUpdateRequest request = new URLUpdateRequest();
        request.setUrl("https://new.com");
        LocalDateTime future = LocalDateTime.now().plusDays(1);
        request.setExpiresAt(future);

        URLResponse response = urlService.updateShortUrl("code", request);

        assertThat(response.getLongUrl()).isEqualTo("https://new.com");
        assertThat(response.getExpiresAt()).isEqualTo(future);
        assertThat(response.getShortCode()).isEqualTo("code");
        verify(redisTemplate).delete("short:code");
        verify(redisTemplate).delete("long:https://old.com");
        verify(valueOperations).set(eq("short:code"), eq("https://new.com"), any());
    }

    @Test
    void updateShortUrl_reactivates_whenExpiryMovedToFuture() {
        URL entity = URL.builder().id(1).shortUrl("code").longUrl("https://old.com")
                .expiresAt(LocalDateTime.now().minusMinutes(1)).active(false).build();
        when(urlRepository.findByShortUrl("code")).thenReturn(Optional.of(entity));
        when(urlRepository.save(any(URL.class))).thenAnswer(invocation -> invocation.getArgument(0));

        URLUpdateRequest request = new URLUpdateRequest();
        request.setUrl("https://old.com");
        request.setExpiresAt(null);

        urlService.updateShortUrl("code", request);

        ArgumentCaptor<URL> captor = ArgumentCaptor.forClass(URL.class);
        verify(urlRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void updateShortUrl_throwsNotFound_whenMissing() {
        when(urlRepository.findByShortUrl("missing")).thenReturn(Optional.empty());

        URLUpdateRequest request = new URLUpdateRequest();
        request.setUrl("https://new.com");

        assertThrows(UrlService.UrlNotFoundException.class, () -> urlService.updateShortUrl("missing", request));
    }

    @Test
    void deleteShortUrl_deletesUrlAndAnalytics_whenFound() {
        URL entity = URL.builder().id(1).shortUrl("code").longUrl("https://old.com").active(true).build();
        when(urlRepository.findByShortUrl("code")).thenReturn(Optional.of(entity));

        urlService.deleteShortUrl("code");

        verify(clickEventRepository).deleteByShortUrl("code");
        verify(urlRepository).delete(entity);
        verify(redisTemplate).delete("short:code");
        verify(redisTemplate).delete("long:https://old.com");
    }

    @Test
    void deleteShortUrl_throwsNotFound_whenMissing() {
        when(urlRepository.findByShortUrl("missing")).thenReturn(Optional.empty());

        assertThrows(UrlService.UrlNotFoundException.class, () -> urlService.deleteShortUrl("missing"));
        verifyNoInteractions(clickEventRepository);
    }
}
