package com.example.demo.bench.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 가짜 상품 카탈로그 (src/main/resources/bench/products.json). */
@Component
public class ProductCatalog {

    private final Map<String, Product> byId = new LinkedHashMap<>();

    public ProductCatalog(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("bench/products.json").getInputStream()) {
            List<Product> products = mapper.readValue(in, new TypeReference<List<Product>>() {
            });
            products.forEach(p -> byId.put(p.id(), p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Product> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * 실제 쇼핑몰 검색처럼 검색어를 단어로 나눠 하나라도 맞으면 결과에 포함하고, 많이 맞은 순으로 정렬한다.
     * "1kg", "10구", "2대" 같은 용량·수량 단어는 무시한다. 모두 무시되면 카테고리만으로 검색한다.
     */
    public List<Product> search(String category, String keyword) {
        List<String> tokens = keyword == null ? List.of() : Arrays.stream(keyword.split("[\\s,/]+"))
                .map(ProductCatalog::normalize)
                .filter(t -> !t.isEmpty() && !QUANTITY.matcher(t).matches())
                .toList();
        return byId.values().stream()
                .filter(p -> category == null || category.isBlank() || p.category().equals(category))
                .map(p -> Map.entry(p, matchCount(p, tokens)))
                .filter(e -> tokens.isEmpty() || e.getValue() > 0)
                .sorted(Map.Entry.<Product, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static final Pattern QUANTITY = Pattern.compile("\\d+(\\.\\d+)?[a-z가-힣]{0,3}");

    private static int matchCount(Product p, List<String> tokens) {
        List<String> fields = Stream.of(Stream.of(p.name(), p.category()), p.tags().stream(), p.reviewKeywords().stream())
                .flatMap(s -> s)
                .map(ProductCatalog::normalize)
                .toList();
        return (int) tokens.stream()
                .filter(t -> fields.stream().anyMatch(s -> s.contains(t) || (s.length() >= 2 && t.contains(s))))
                .count();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace(" ", "").toLowerCase(Locale.ROOT);
    }
}
