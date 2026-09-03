package com.example.URLShortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.example.URLShortener.validation.ValidHttpUrl;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class URLRequest {

    @NotBlank(message = "Destination URL is required")
    @Size(max = 2048, message = "Destination URL cannot exceed 2048 characters")
    @ValidHttpUrl
    private String url;

    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "Alias can only contain letters, numbers, dashes, and underscores")
    @Size(max = 8, message = "Custom alias cannot exceed 8 characters")
    private String customAlias;

    private LocalDateTime expiresAt;
}
