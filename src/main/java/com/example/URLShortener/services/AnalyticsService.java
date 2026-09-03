package com.example.URLShortener.services;

import com.example.URLShortener.dto.AnalyticsResponse;
import com.example.URLShortener.models.ClickEvent;
import com.example.URLShortener.repository.ClickEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final ClickEventRepository clickEventRepository;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Transactional
    public void recordClick(String shortUrl, HttpServletRequest request) {
        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }

        String userAgent = request.getHeader("User-Agent");

        ClickEvent event = ClickEvent.builder()
                .shortUrl(shortUrl)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .clickedAt(LocalDateTime.now())
                .build();

        clickEventRepository.save(event);
    }

    public AnalyticsResponse getStats(String shortCode) {
        long totalClicks = clickEventRepository.countByShortUrl(shortCode);

        List<ClickEvent> allEvents = clickEventRepository.findByShortUrlOrderByClickedAtDesc(shortCode);

        long uniqueVisitors = allEvents.stream()
                .map(ClickEvent::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .distinct()
                .count();

        Map<String, Long> countries = allEvents.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getCountry() == null || e.getCountry().isBlank() ? "Unknown" : e.getCountry(),
                        TreeMap::new,
                        Collectors.counting()));

        List<AnalyticsResponse.ClickDetails> details = allEvents.stream()
                .limit(50) // only show last 50 clicks in UI for performance
                .map(e -> AnalyticsResponse.ClickDetails.builder()
                        .ipAddress(e.getIpAddress())
                        .userAgent(e.getUserAgent())
                        .country(e.getCountry())
                        .clickedAt(e.getClickedAt().format(FORMATTER))
                        .build())
                .collect(Collectors.toList());

        String fullShortUrl = baseUrl.endsWith("/") ? baseUrl + shortCode : baseUrl + "/" + shortCode;

        return AnalyticsResponse.builder()
                .shortUrl(fullShortUrl)
                .shortCode(shortCode)
                .clicks(totalClicks)
                .uniqueVisitors(uniqueVisitors)
                .countries(countries)
                .recentClicks(details)
                .build();
    }
}
