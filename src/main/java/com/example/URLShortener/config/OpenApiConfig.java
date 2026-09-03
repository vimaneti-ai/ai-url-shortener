package com.example.URLShortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI urlShortenerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .description("Create, redirect, update, delete, and track click analytics for short links.")
                        .version("v1"));
    }
}
