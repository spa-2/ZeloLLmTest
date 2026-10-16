package com.example.demo.bench.eval;

import java.util.List;
import java.util.Map;

/**
 * 실행 1건 채점 결과.
 *
 * @param itemRecall       정답 재료 중 맞는 상품을 고른 비율
 * @param quantityAccuracy 맞는 상품을 고른 재료 중 수량까지 맞은 비율
 * @param violations       위반 목록 (HALLUCINATED / SOLD_OUT / RESTRICTED / AVOID / ALLERGEN / SUGGESTION_*)
 * @param extraIds         정답도 선택 허용 목록도 아닌 추가 상품 (감점 없음, 참고용)
 * @param actualTotal      카탈로그 가격으로 다시 계산한 합계
 * @param score            0~100 가중 점수 (Evaluator 주석 참고)
 */
public record CaseEvaluation(
        boolean submitted,
        boolean pass,
        double score,
        double itemRecall,
        double quantityAccuracy,
        List<String> violations,
        List<String> extraIds,
        Map<String, Integer> selected,
        List<String> suggestionIds,
        boolean quoteMatches,
        Integer submittedTotal,
        int actualTotal,
        boolean totalCorrect,
        Boolean withinBudget,
        boolean budgetFlagCorrect,
        boolean excludedReported,
        boolean unavailableReported,
        boolean suggestionsOk) {
}
