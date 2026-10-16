package com.example.demo.bench.report;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

import com.example.demo.bench.agent.AgentRun;
import com.example.demo.bench.eval.CaseEvaluation;

/** 모델별 집계. */
public record ModelSummary(
        String model,
        int runs,
        double passRate,
        double avgScore,
        double avgItemRecall,
        double avgQuantityAccuracy,
        double submitRate,
        double violationRunRate,
        long hallucinated,
        long soldOut,
        long restricted,
        long allergen,
        long avoid,
        double quoteMatchRate,
        double totalCorrectRate,
        double budgetCorrectRate,
        int errors,
        double avgTurns,
        double avgToolCalls,
        double avgFailedTools,
        double avgInputTokens,
        double avgCachedInputTokens,
        double avgOutputTokens,
        double avgReasoningTokens,
        double avgTotalTokens,
        double avgCostUsd,
        double totalCostUsd,
        Double costPerPassUsd,
        double avgLatencyMs,
        long p50LatencyMs,
        long p95LatencyMs) {

    public record Row(AgentRun run, CaseEvaluation eval) {
    }

    public static ModelSummary of(String model, List<Row> rows) {
        int n = rows.size();
        long passes = rows.stream().filter(r -> r.eval().pass()).count();
        double totalCost = rows.stream().mapToDouble(r -> r.run().costUsd()).sum();
        List<Long> latencies = rows.stream().map(r -> r.run().latencyMs()).sorted(Comparator.naturalOrder()).toList();
        return new ModelSummary(
                model, n,
                ratio(passes, n),
                avg(rows, r -> r.eval().score()),
                avg(rows, r -> r.eval().itemRecall()),
                avg(rows, r -> r.eval().quantityAccuracy()),
                ratio(rows.stream().filter(r -> r.eval().submitted()).count(), n),
                ratio(rows.stream().filter(r -> r.eval().submitted() && !r.eval().violations().isEmpty()).count(), n),
                countViolations(rows, "HALLUCINATED"),
                countViolations(rows, "SOLD_OUT"),
                countViolations(rows, "RESTRICTED"),
                countViolations(rows, "ALLERGEN"),
                countViolations(rows, "AVOID"),
                ratio(rows.stream().filter(r -> r.eval().quoteMatches()).count(), n),
                ratio(rows.stream().filter(r -> r.eval().totalCorrect()).count(), n),
                ratio(rows.stream().filter(r -> r.eval().budgetFlagCorrect()).count(), n),
                (int) rows.stream().filter(r -> r.run().error() != null).count(),
                avg(rows, r -> r.run().turns()),
                avg(rows, r -> r.run().toolCalls().values().stream().mapToInt(Integer::intValue).sum()),
                avg(rows, r -> r.run().failedTools()),
                avg(rows, r -> r.run().usage().input()),
                avg(rows, r -> r.run().usage().cachedInput()),
                avg(rows, r -> r.run().usage().output()),
                avg(rows, r -> r.run().usage().reasoning()),
                avg(rows, r -> r.run().usage().total()),
                n == 0 ? 0 : totalCost / n,
                totalCost,
                passes == 0 ? null : totalCost / passes,
                avg(rows, r -> r.run().latencyMs()),
                percentile(latencies, 50),
                percentile(latencies, 95));
    }

    private static long countViolations(List<Row> rows, String kind) {
        return rows.stream().flatMap(r -> r.eval().violations().stream())
                .filter(v -> v.startsWith(kind) || v.startsWith("SUGGESTION_" + kind))
                .count();
    }

    private static double avg(List<Row> rows, ToDoubleFunction<Row> f) {
        return rows.stream().mapToDouble(f).average().orElse(0);
    }

    private static double ratio(long a, int n) {
        return n == 0 ? 0 : (double) a / n;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
