package com.example.URLShortener.controllers;

import com.example.URLShortener.config.KafkaConfig;
import com.example.URLShortener.dto.ClickEventMessage;
import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.dto.URLUpdateRequest;
import com.example.URLShortener.services.UrlService;
import com.example.URLShortener.services.AnalyticsService;
import com.example.URLShortener.dto.AnalyticsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "URL Shortener", description = "Create, redirect, update, delete, and track click analytics for short links")
public class urlController {

    private final UrlService urlService;
    private final AnalyticsService analyticsService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Redirect to the destination URL for a short code",
            description = "Publishes a click event to Kafka for background analytics processing before redirecting.")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "Redirects to the destination URL"),
            @ApiResponse(responseCode = "404", description = "Short code does not exist"),
            @ApiResponse(responseCode = "410", description = "Short code has expired")
    })
    @GetMapping("/{shortUrl}")
    public ResponseEntity<Void> getLongURLByShortURL(@NotNull @PathVariable("shortUrl") String shortUrl,
            HttpServletRequest request) {
        String longUrl = urlService.resolveLongUrl(shortUrl);

        // Browsers and link scanners may prefetch a visible URL before the user
        // activates it. Redirect normally, but don't count that speculative request.
        if (!isPrefetchRequest(request)) {
            // Fire-and-forget async publish to Kafka. Analytics are persisted by
            // ClickEventConsumer in the background.
            try {
                ClickEventMessage clickEvent = ClickEventMessage.builder()
                        .shortUrl(shortUrl)
                        .ipAddress(resolveClientIp(request))
                        .userAgent(request.getHeader("User-Agent"))
                        .clickedAt(LocalDateTime.now())
                        .build();
                String json = objectMapper.writeValueAsString(clickEvent);
                kafkaTemplate.send(KafkaConfig.CLICK_EVENTS_TOPIC, shortUrl, json);
            } catch (Exception e) {
                // Kafka publish failure should NOT block the redirect
                log.warn("Failed to publish click event to Kafka for shortUrl={}: {}", shortUrl, e.getMessage());
            }
        }

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(longUrl)).build();
    }

    @Operation(summary = "Create a short URL",
            description = "Shortening the same longUrl twice returns the existing active short code instead of minting a new one.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Short URL created",
                    content = @Content(schema = @Schema(implementation = URLResponse.class))),
            @ApiResponse(responseCode = "409", description = "Custom alias already in use"),
            @ApiResponse(responseCode = "400", description = "Validation failed (e.g. longUrl missing http(s):// prefix)")
    })
    @PostMapping("/shorten")
    public ResponseEntity<URLResponse> createShortURL(@Valid @RequestBody URLRequest urlRequest) {
        URLResponse response = urlService.createShortUrl(urlRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get click analytics for a short code",
            description = "Returns total clicks, unique visitors, a country breakdown, and the most recent clicks.")
    @ApiResponse(responseCode = "200", description = "Analytics for the short code",
            content = @Content(schema = @Schema(implementation = AnalyticsResponse.class)))
    @GetMapping("/analytics/{code}")
    public AnalyticsResponse getAnalytics(@PathVariable String code) {
        return analyticsService.getStats(code);
    }

    @Operation(summary = "Update a short URL's destination and/or expiry",
            description = "The short code itself cannot be changed. Moving expiresAt into the future (or clearing it) reactivates a previously expired link.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(schema = @Schema(implementation = URLResponse.class))),
            @ApiResponse(responseCode = "404", description = "Short code does not exist"),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    @PutMapping("/{code}")
    public ResponseEntity<URLResponse> updateShortURL(@PathVariable String code,
            @Valid @RequestBody URLUpdateRequest urlUpdateRequest) {
        URLResponse response = urlService.updateShortUrl(code, urlUpdateRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Permanently delete a short URL and its click history",
            description = "Cascades to click_events. This cannot be undone.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "Short code does not exist")
    })
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> deleteShortURL(@PathVariable String code) {
        urlService.deleteShortUrl(code);
        return ResponseEntity.noContent().build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isPrefetchRequest(HttpServletRequest request) {
        return containsPrefetch(request.getHeader("Sec-Purpose"))
                || containsPrefetch(request.getHeader("Purpose"))
                || containsPrefetch(request.getHeader("X-Purpose"));
    }

    private boolean containsPrefetch(String header) {
        return header != null && (header.toLowerCase().contains("prefetch")
                || header.toLowerCase().contains("preview"));
    }
}
