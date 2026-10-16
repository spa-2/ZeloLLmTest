package com.example.demo.bench;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** benchmark.run-on-startup=true 이면 기동 직후 한 번 실행. */
@Component
@ConditionalOnProperty(name = "benchmark.run-on-startup", havingValue = "true")
public class BenchmarkStartupRunner implements ApplicationRunner {

    private final BenchmarkService service;

    public BenchmarkStartupRunner(BenchmarkService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.run(null, null, null);
    }
}
