package com.example.demo.bench.catalog;

import java.util.List;
import java.util.Map;

/**
 * 시나리오 1건과 정답지.
 *
 * @param expectedItems     반드시 담겨야 하는 재료. accept = 정답으로 인정하는 상품ID → 기대 수량
 * @param optionalIds       담아도 감점하지 않는 상품
 * @param avoidIds          담으면 위반인 상품 → 이유
 * @param allergens         담으면 위반인 알레르기 성분
 * @param expectExcluded    excluded_requests에 언급돼야 하는 키워드 (주문 불가 품목)
 * @param expectUnavailable unavailable_ingredients에 언급돼야 하는 키워드 (미판매 재료)
 */
public record TestCase(
        String id,
        String title,
        String userMessage,
        Integer budget,
        List<ExpectedItem> expectedItems,
        List<String> optionalIds,
        Map<String, String> avoidIds,
        List<String> allergens,
        List<String> expectExcluded,
        List<String> expectUnavailable,
        boolean expectSuggestions) {

    public record ExpectedItem(String ingredient, Map<String, Integer> accept) {
    }

    public TestCase {
        expectedItems = expectedItems == null ? List.of() : expectedItems;
        optionalIds = optionalIds == null ? List.of() : optionalIds;
        avoidIds = avoidIds == null ? Map.of() : avoidIds;
        allergens = allergens == null ? List.of() : allergens;
        expectExcluded = expectExcluded == null ? List.of() : expectExcluded;
        expectUnavailable = expectUnavailable == null ? List.of() : expectUnavailable;
    }
}
