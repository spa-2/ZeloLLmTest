package com.example.demo.bench.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 실행 1건 동안만 쓰는 견적 저장소. 실제 서비스의 Redis(TTL 10분)를 흉내 낸다. */
public class QuoteStore {

    public static final Duration TTL = Duration.ofMinutes(10);

    public record Line(String productId, String name, int unitPrice, int quantity, int amount) {
    }

    public record Quote(String id, List<Line> lines, int total, Instant expiresAt) {

        /** 상품ID → 수량 */
        public Map<String, Integer> quantities() {
            Map<String, Integer> m = new LinkedHashMap<>();
            lines.forEach(l -> m.merge(l.productId(), l.quantity(), Integer::sum));
            return m;
        }
    }

    private final Map<String, Quote> quotes = new LinkedHashMap<>();
    private int seq;

    public Quote save(List<Line> lines) {
        int total = lines.stream().mapToInt(Line::amount).sum();
        Quote q = new Quote("Q" + (++seq), List.copyOf(lines), total, Instant.now().plus(TTL));
        quotes.put(q.id(), q);
        return q;
    }

    public Optional<Quote> find(String id) {
        return Optional.ofNullable(id == null ? null : quotes.get(id))
                .filter(q -> q.expiresAt().isAfter(Instant.now()));
    }

    public int size() {
        return quotes.size();
    }
}
