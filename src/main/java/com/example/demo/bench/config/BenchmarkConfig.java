package com.example.demo.bench.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({BenchmarkProperties.class, OpenAiProperties.class})
public class BenchmarkConfig {
}
