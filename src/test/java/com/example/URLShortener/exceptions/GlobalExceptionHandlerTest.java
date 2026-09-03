package com.example.URLShortener.exceptions;

import com.example.URLShortener.dto.ApiError;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
import com.example.URLShortener.services.UrlService.UrlExpiredException;
import com.example.URLShortener.services.UrlService.UrlNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/test");
    }

    @Test
    void mapsAliasConflictTo409() {
        ResponseEntity<ApiError> response = handler.handleAliasConflict(
                new AliasAlreadyExistsException("Alias exists"), request);
        assertError(response, HttpStatus.CONFLICT, "Alias exists");
    }

    @Test
    void mapsMissingUrlTo404() {
        ResponseEntity<ApiError> response = handler.handleNotFound(
                new UrlNotFoundException("URL missing"), request);
        assertError(response, HttpStatus.NOT_FOUND, "URL missing");
    }

    @Test
    void mapsExpiredUrlTo410() {
        ResponseEntity<ApiError> response = handler.handleExpired(
                new UrlExpiredException("URL expired"), request);
        assertError(response, HttpStatus.GONE, "URL expired");
    }

    @Test
    void hidesInternalDetailsForUnexpectedErrors() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new RuntimeException("database password leaked"), request);
        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private void assertError(ResponseEntity<ApiError> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(status.value());
        assertThat(response.getBody().getMessage()).isEqualTo(message);
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/test");
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }
}
