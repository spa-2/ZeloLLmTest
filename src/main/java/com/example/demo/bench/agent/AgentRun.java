package com.example.demo.bench.agent;

import java.util.List;
import java.util.Map;

import com.example.demo.bench.llm.TokenUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * 모델 1개 × 케이스 1개 × 반복 1회 실행 결과.
 *
 * @param submitted     submit_answer 인자 (제출하지 않았으면 null)
 * @param toolCalls     도구 이름 → 호출 횟수
 * @param failedTools   에러를 돌려준 도구 호출 수 (재고 부족 견적 등)
 */
public record AgentRun(
        String model,
        String caseId,
        int repeat,
        JsonNode submitted,
        String finalText,
        int turns,
        Map<String, Integer> toolCalls,
        int failedTools,
        TokenUsage usage,
        double costUsd,
        long latencyMs,
        String error,
        QuoteStore quotes,
        ArrayNode transcript,
        List<ApiCallLog> apiCalls) {

    /** api_calls.jsonl 한 줄 = API 호출 1회 */
    public record ApiCallLog(
            String timestamp,
            String model,
            String caseId,
            int repeat,
            int turn,
            String responseId,
            String status,
            String incompleteReason,
            long latencyMs,
            int attempts,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long reasoningTokens,
            long totalTokens,
            double costUsd,
            List<String> functionCalls) {
    }
}
