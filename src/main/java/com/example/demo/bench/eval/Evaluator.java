package com.example.demo.bench.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.demo.bench.agent.AgentRun;
import com.example.demo.bench.agent.QuoteStore;
import com.example.demo.bench.catalog.Product;
import com.example.demo.bench.catalog.ProductCatalog;
import com.example.demo.bench.catalog.TestCase;

import tools.jackson.databind.JsonNode;

/**
 * 정답지 기반 자동 채점.
 *
 * <p>score(0~100) 가중치:
 * 상품 정확도(recall) 35 · 수량 15 · 위반 없음 25 · 견적/합계 일치 10 · 예산 판단 5 · 제외/미판매 안내 5 · 추천 재료 5.
 * pass 는 위 항목이 모두 만점일 때만 true (추가 상품 extraIds 는 감점하지 않음).
 */
@Component
public class Evaluator {

    private final ProductCatalog catalog;

    public Evaluator(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    public CaseEvaluation evaluate(TestCase tc, AgentRun run) {
        JsonNode s = run.submitted();
        if (s == null) {
            return new CaseEvaluation(false, false, 0, 0, 0, List.of("NOT_SUBMITTED"), List.of(), Map.of(), List.of(),
                    false, null, 0, false, null, false, false, false, false);
        }

        Map<String, Integer> selected = new LinkedHashMap<>();
        for (JsonNode item : s.path("items")) {
            selected.merge(item.path("product_id").asString(""), item.path("quantity").asInt(0), Integer::sum);
        }
        List<String> suggestionIds = new ArrayList<>();
        for (JsonNode item : s.path("suggestions")) {
            suggestionIds.add(item.path("product_id").asString(""));
        }

        // 1) 정답 재료 매칭
        Set<String> consumed = new HashSet<>();
        int hits = 0;
        int qtyOk = 0;
        for (TestCase.ExpectedItem exp : tc.expectedItems()) {
            Optional<String> hit = exp.accept().keySet().stream()
                    .filter(selected::containsKey)
                    .filter(id -> !consumed.contains(id))
                    .findFirst();
            if (hit.isPresent()) {
                String id = hit.get();
                consumed.add(id);
                hits++;
                if (selected.get(id).equals(exp.accept().get(id))) {
                    qtyOk++;
                }
            }
        }
        int expectedCount = tc.expectedItems().size();
        double recall = expectedCount == 0 ? 1 : (double) hits / expectedCount;
        double qtyAcc = hits == 0 ? 0 : (double) qtyOk / hits;

        // 2) 위반 검사
        List<String> violations = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        int actualTotal = 0;
        for (Map.Entry<String, Integer> e : selected.entrySet()) {
            String id = e.getKey();
            Optional<Product> p = catalog.find(id);
            if (p.isEmpty()) {
                violations.add("HALLUCINATED:" + id);
                continue;
            }
            actualTotal += p.get().price() * e.getValue();
            productViolations(tc, p.get(), "").forEach(violations::add);
            if (tc.avoidIds().containsKey(id)) {
                violations.add("AVOID:" + id + "(" + tc.avoidIds().get(id) + ")");
            }
            if (!consumed.contains(id) && !tc.optionalIds().contains(id) && !tc.avoidIds().containsKey(id)) {
                extra.add(id);
            }
        }
        boolean suggestionsOk = true;
        for (String id : suggestionIds) {
            Optional<Product> p = catalog.find(id);
            List<String> v = p.isEmpty() ? List.of("SUGGESTION_HALLUCINATED:" + id)
                    : productViolations(tc, p.get(), "SUGGESTION_");
            if (selected.containsKey(id)) {
                v = new ArrayList<>(v);
                v.add("SUGGESTION_IN_ITEMS:" + id);
            }
            if (!v.isEmpty()) {
                suggestionsOk = false;
                violations.addAll(v);
            }
        }
        if (tc.expectSuggestions() && suggestionIds.isEmpty()) {
            suggestionsOk = false;
        }

        // 3) 견적·합계·예산
        String quoteId = s.path("quote_id").isNull() ? null : s.path("quote_id").asString(null);
        QuoteStore quotes = run.quotes();
        boolean quoteMatches = quotes.find(quoteId).map(q -> q.quantities().equals(selected)).orElse(selected.isEmpty());
        Integer submittedTotal = s.path("total_price").isNumber() ? s.path("total_price").asInt() : null;
        boolean totalCorrect = submittedTotal != null && submittedTotal == actualTotal;
        Boolean withinBudget = s.path("within_budget").isBoolean() ? s.path("within_budget").asBoolean() : null;
        boolean budgetOk = tc.budget() == null
                ? withinBudget == null || withinBudget
                : withinBudget != null && withinBudget == (actualTotal <= tc.budget());

        // 4) 주문 불가 / 미판매 안내
        boolean excludedOk = containsAll(s.path("excluded_requests"), tc.expectExcluded());
        boolean unavailableOk = containsAll(s.path("unavailable_ingredients"), tc.expectUnavailable());

        double score = 35 * recall
                + 15 * (hits == 0 ? 0 : qtyAcc) * recall
                + (violations.isEmpty() ? 25 : 0)
                + (quoteMatches && totalCorrect ? 10 : 0)
                + (budgetOk ? 5 : 0)
                + (excludedOk && unavailableOk ? 5 : 0)
                + (suggestionsOk ? 5 : 0);
        boolean pass = score >= 99.999;

        return new CaseEvaluation(true, pass, Math.round(score * 10) / 10.0, recall, qtyAcc, violations, extra,
                selected, suggestionIds, quoteMatches, submittedTotal, actualTotal, totalCorrect, withinBudget,
                budgetOk, excludedOk, unavailableOk, suggestionsOk);
    }

    private static List<String> productViolations(TestCase tc, Product p, String prefix) {
        List<String> v = new ArrayList<>();
        if (p.soldOut()) {
            v.add(prefix + "SOLD_OUT:" + p.id());
        }
        if (!p.agentOrderable()) {
            v.add(prefix + "RESTRICTED:" + p.id());
        }
        for (String allergen : tc.allergens()) {
            if (p.allergens().contains(allergen)) {
                v.add(prefix + "ALLERGEN:" + p.id() + "(" + allergen + ")");
            }
        }
        return v;
    }

    private static boolean containsAll(JsonNode array, List<String> keywords) {
        StringBuilder joined = new StringBuilder();
        for (JsonNode n : array) {
            joined.append(n.asString("")).append(' ');
        }
        String all = joined.toString();
        return keywords.stream().allMatch(all::contains);
    }
}
