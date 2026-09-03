package com.example.URLShortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class URLUpdateRequest {

    @NotBlank(message = "Destination URL is required")
    @Pattern(regexp = "^(https?://).*$", message = "URL must start with http:// or https://")
    private String url;

    private LocalDateTime expiresAt;
}
