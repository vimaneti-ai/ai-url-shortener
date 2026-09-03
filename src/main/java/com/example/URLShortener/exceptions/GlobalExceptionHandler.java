package com.example.URLShortener.exceptions;

import com.example.URLShortener.dto.ApiError;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
import com.example.URLShortener.services.UrlService.UrlExpiredException;
import com.example.URLShortener.services.UrlService.UrlNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AliasAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleAliasConflict(AliasAlreadyExistsException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, null);
    }

    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(UrlNotFoundException exception,
            HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, null);
    }

    @ExceptionHandler(UrlExpiredException.class)
    public ResponseEntity<ApiError> handleExpired(UrlExpiredException exception,
            HttpServletRequest request) {
        return response(HttpStatus.GONE, exception.getMessage(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage(), request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure on {}", request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, null);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String message,
            HttpServletRequest request, Map<String, String> fieldErrors) {
        ApiError body = ApiError.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .fieldErrors(fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
