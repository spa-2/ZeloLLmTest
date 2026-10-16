package com.example.demo.bench.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("openai")
public record OpenAiProperties(
        String apiKey,
        @DefaultValue("https://api.openai.com/v1/responses") String apiUrl,
        @DefaultValue("180") int timeoutSeconds) {
}
