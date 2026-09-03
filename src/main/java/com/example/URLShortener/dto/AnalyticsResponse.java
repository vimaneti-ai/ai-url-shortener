package com.example.URLShortener.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AnalyticsResponse {
    private String shortUrl;
    private String shortCode;
    private long totalClicks;
    private long uniqueVisitors;
    private Map<String, Long> countries;
    private List<ClickDetails> recentClicks;

    @Data
    @Builder
    public static class ClickDetails {
        private String ipAddress;
        private String userAgent;
        private String country;
        private String clickedAt;
    }
}
