package com.example.demo.bench.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class TestCaseRepository {

    private final List<TestCase> cases;

    public TestCaseRepository(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("bench/test-cases.json").getInputStream()) {
            cases = mapper.readValue(in, new TypeReference<List<TestCase>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ids가 비어 있으면 전체. */
    public List<TestCase> select(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return cases;
        }
        return cases.stream().filter(c -> ids.contains(c.id())).toList();
    }
}
