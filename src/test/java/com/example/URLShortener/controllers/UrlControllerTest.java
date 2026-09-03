package com.example.URLShortener.controllers;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.dto.URLUpdateRequest;
import com.example.URLShortener.services.UrlService;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
import com.example.URLShortener.services.UrlService.UrlExpiredException;
import com.example.URLShortener.services.UrlService.UrlNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class UrlControllerTest {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @SuppressWarnings("unchecked")
        private KafkaTemplate<String, String> mockKafkaTemplate() {
                return Mockito.mock(KafkaTemplate.class);
        }

        @Test
        void requestContract_deserializesUrlField() throws Exception {
                URLRequest request = objectMapper.readValue(
                                "{\"url\":\"https://example.com\"}", URLRequest.class);

                assertThat(request.getUrl()).isEqualTo("https://example.com");
        }

        @Test
        void analyticsContract_serializesClicksField() throws Exception {
                com.example.URLShortener.dto.AnalyticsResponse response =
                                com.example.URLShortener.dto.AnalyticsResponse.builder()
                                                .clicks(12)
                                                .build();

                String json = objectMapper.writeValueAsString(response);

                assertThat(json).contains("\"clicks\":12");
                assertThat(json).doesNotContain("totalClicks");
        }

        @Test
        void endpointContract_exposesAssessmentPaths() throws Exception {
                GetMapping redirect = urlController.class
                                .getDeclaredMethod("getLongURLByShortURL", String.class, HttpServletRequest.class)
                                .getAnnotation(GetMapping.class);
                PutMapping update = urlController.class
                                .getDeclaredMethod("updateShortURL", String.class, URLUpdateRequest.class)
                                .getAnnotation(PutMapping.class);
                DeleteMapping delete = urlController.class
                                .getDeclaredMethod("deleteShortURL", String.class)
                                .getAnnotation(DeleteMapping.class);

                assertThat(Arrays.asList(redirect.value()))
                                .contains("/{shortUrl:[a-zA-Z0-9_-]{1,8}}");
                assertThat(Arrays.asList(update.value()))
                                .contains("/api/v1/shorten/{code}");
                assertThat(Arrays.asList(delete.value()))
                                .contains("/api/v1/shorten/{code}");

                Arrays.stream(redirect.value()).forEach(path -> new PathPatternParser().parse(path));
        }

        @Test
        void getLongURLByShortURL_redirectsWhenFound() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
                HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                when(urlService.resolveLongUrl("code")).thenReturn("https://example.com");
                when(request.getHeader("X-Forwarded-For")).thenReturn(null);
                when(request.getRemoteAddr()).thenReturn("127.0.0.1");
                when(request.getHeader("User-Agent")).thenReturn("TestAgent");

                ResponseEntity<Void> response = controller.getLongURLByShortURL("code", request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
                assertThat(response.getHeaders().getLocation()).hasToString("https://example.com");

                // Verify Kafka publish was called with correct topic and key
                verify(kafkaTemplate).send(eq("url-click-events"), eq("code"), anyString());
        }

        @Test
        void getLongURLByShortURL_publishesCorrectClickEventToKafka() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
                HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                when(urlService.resolveLongUrl("abc")).thenReturn("https://google.com");
                when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100");
                when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0");

                controller.getLongURLByShortURL("abc", request);

                // Verify a JSON string containing the short URL was published
                verify(kafkaTemplate).send(eq("url-click-events"), eq("abc"), contains("\"shortUrl\":\"abc\""));
        }

        @Test
        void getLongURLByShortURL_doesNotCountBrowserPrefetch() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
                HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
                urlController controller = new urlController(urlService,
                                mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                when(urlService.resolveLongUrl("abc")).thenReturn("https://example.com");
                when(request.getHeader("Sec-Purpose")).thenReturn("prefetch");

                ResponseEntity<Void> response = controller.getLongURLByShortURL("abc", request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
                assertThat(response.getHeaders().getLocation()).hasToString("https://example.com");
                verifyNoInteractions(kafkaTemplate);
        }

        @Test
        void getLongURLByShortURL_propagatesExpiredForGlobalHandler() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
                HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                Mockito.doThrow(new UrlExpiredException("expired")).when(urlService).resolveLongUrl("expired");

                assertThrows(UrlExpiredException.class,
                                () -> controller.getLongURLByShortURL("expired", request));

                // Verify Kafka was NOT called when URL is expired
                verifyNoInteractions(kafkaTemplate);
        }

        @Test
        void getLongURLByShortURL_propagatesNotFoundForGlobalHandler() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
                HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                Mockito.doThrow(new UrlNotFoundException("missing")).when(urlService).resolveLongUrl("missing");

                assertThrows(UrlNotFoundException.class,
                                () -> controller.getLongURLByShortURL("missing", request));

                // Verify Kafka was NOT called when URL is not found
                verifyNoInteractions(kafkaTemplate);
        }

        @Test
        void getLongURLByShortURL_stillRedirectsWhenKafkaFails() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
                HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                when(urlService.resolveLongUrl("abc")).thenReturn("https://google.com");
                when(request.getHeader("X-Forwarded-For")).thenReturn(null);
                when(request.getRemoteAddr()).thenReturn("10.0.0.1");
                when(request.getHeader("User-Agent")).thenReturn("Chrome");

                // Simulate Kafka failure
                when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                        .thenThrow(new RuntimeException("Kafka broker unavailable"));

                // Redirect should still succeed despite Kafka failure
                ResponseEntity<Void> response = controller.getLongURLByShortURL("abc", request);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
                assertThat(response.getHeaders().getLocation()).hasToString("https://google.com");
        }

        @Test
        void createShortURL_returnsCreatedOnSuccess() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                URLResponse response = URLResponse.builder()
                                .shortUrl("http://localhost:8080/code")
                                .shortCode("code")
                                .longUrl("https://example.com")
                                .build();

                when(urlService.createShortUrl(any(URLRequest.class))).thenReturn(response);

                URLRequest request = new URLRequest();
                request.setUrl("https://example.com");

                ResponseEntity<URLResponse> resp = controller.createShortURL(request);

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(resp.getBody()).isNotNull();
                assertThat(resp.getBody().getShortCode()).isEqualTo("code");
        }

        @Test
        void createShortURL_propagatesAliasConflictForGlobalHandler() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                Mockito.doThrow(new AliasAlreadyExistsException("alias exists"))
                                .when(urlService).createShortUrl(any(URLRequest.class));

                URLRequest request = new URLRequest();
                request.setUrl("https://example.com");
                request.setCustomAlias("alias");

                assertThrows(AliasAlreadyExistsException.class, () -> controller.createShortURL(request));
        }

        @Test
        void updateShortURL_returnsOkOnSuccess() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                URLResponse response = URLResponse.builder()
                                .shortUrl("http://localhost:8080/code")
                                .shortCode("code")
                                .longUrl("https://updated.com")
                                .build();

                when(urlService.updateShortUrl(eq("code"), any(URLUpdateRequest.class))).thenReturn(response);

                URLUpdateRequest request = new URLUpdateRequest();
                request.setUrl("https://updated.com");

                ResponseEntity<URLResponse> resp = controller.updateShortURL("code", request);

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(resp.getBody()).isNotNull();
                assertThat(resp.getBody().getLongUrl()).isEqualTo("https://updated.com");
        }

        @Test
        void updateShortURL_propagatesNotFoundForGlobalHandler() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                Mockito.doThrow(new UrlNotFoundException("missing"))
                                .when(urlService).updateShortUrl(eq("missing"), any(URLUpdateRequest.class));

                URLUpdateRequest request = new URLUpdateRequest();
                request.setUrl("https://updated.com");

                assertThrows(UrlNotFoundException.class,
                                () -> controller.updateShortURL("missing", request));
        }

        @Test
        void deleteShortURL_returnsNoContentOnSuccess() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                ResponseEntity<Void> resp = controller.deleteShortURL("code");

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                verify(urlService).deleteShortUrl("code");
        }

        @Test
        void deleteShortURL_propagatesNotFoundForGlobalHandler() {
                UrlService urlService = Mockito.mock(UrlService.class);
                KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
        urlController controller = new urlController(urlService, mock(com.example.URLShortener.services.AnalyticsService.class), kafkaTemplate, objectMapper);

                Mockito.doThrow(new UrlNotFoundException("missing")).when(urlService).deleteShortUrl("missing");

                assertThrows(UrlNotFoundException.class, () -> controller.deleteShortURL("missing"));
        }
}
