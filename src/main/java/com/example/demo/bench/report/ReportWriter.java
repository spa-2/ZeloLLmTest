package com.example.demo.bench.report;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.demo.bench.catalog.TestCase;
import com.example.demo.bench.config.BenchmarkProperties;

/** summary.csv / case_matrix.csv / report.md 생성. */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static String summaryCsv(List<ModelSummary> summaries) {
        StringBuilder sb = new StringBuilder("﻿"); // 엑셀에서 한글 안 깨지도록 BOM
        sb.append("model,runs,pass_rate,avg_score,item_recall,quantity_accuracy,submit_rate,violation_run_rate,"
                + "hallucinated,sold_out,restricted,allergen,avoid,quote_match_rate,total_correct_rate,budget_correct_rate,"
                + "errors,avg_turns,avg_tool_calls,avg_failed_tools,avg_input_tokens,avg_cached_input_tokens,"
                + "avg_output_tokens,avg_reasoning_tokens,avg_total_tokens,avg_cost_usd,total_cost_usd,cost_per_pass_usd,"
                + "avg_latency_ms,p50_latency_ms,p95_latency_ms\n");
        for (ModelSummary s : summaries) {
            sb.append(String.join(",",
                    s.model(), str(s.runs()), f(s.passRate()), f(s.avgScore()), f(s.avgItemRecall()),
                    f(s.avgQuantityAccuracy()), f(s.submitRate()), f(s.violationRunRate()),
                    str(s.hallucinated()), str(s.soldOut()), str(s.restricted()), str(s.allergen()), str(s.avoid()),
                    f(s.quoteMatchRate()), f(s.totalCorrectRate()), f(s.budgetCorrectRate()), str(s.errors()),
                    f(s.avgTurns()), f(s.avgToolCalls()), f(s.avgFailedTools()), f(s.avgInputTokens()),
                    f(s.avgCachedInputTokens()), f(s.avgOutputTokens()), f(s.avgReasoningTokens()),
                    f(s.avgTotalTokens()), usd(s.avgCostUsd()), usd(s.totalCostUsd()),
                    s.costPerPassUsd() == null ? "" : usd(s.costPerPassUsd()),
                    f(s.avgLatencyMs()), str(s.p50LatencyMs()), str(s.p95LatencyMs())))
                    .append('\n');
        }
        return sb.toString();
    }

    public static String caseMatrixCsv(List<TestCase> cases, List<String> models,
                                       Map<String, Map<String, List<ModelSummary.Row>>> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("case_id,case_title,model,runs,passes,avg_score,avg_total_tokens,avg_cost_usd,avg_latency_ms,violations\n");
        for (TestCase tc : cases) {
            for (String m : models) {
                List<ModelSummary.Row> rs = rows.get(tc.id()).get(m);
                sb.append(String.join(",", tc.id(), quote(tc.title()), m, str(rs.size()),
                                str(rs.stream().filter(r -> r.eval().pass()).count()),
                                f(rs.stream().mapToDouble(r -> r.eval().score()).average().orElse(0)),
                                f(rs.stream().mapToLong(r -> r.run().usage().total()).average().orElse(0)),
                                usd(rs.stream().mapToDouble(r -> r.run().costUsd()).average().orElse(0)),
                                f(rs.stream().mapToLong(r -> r.run().latencyMs()).average().orElse(0)),
                                quote(violationSummary(rs))))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    public static String markdown(String runId, BenchmarkProperties props, List<String> models, int repeats,
                                  List<TestCase> cases, List<ModelSummary> summaries,
                                  Map<String, Map<String, List<ModelSummary.Row>>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# LLM 장보기 에이전트 벤치마크 결과\n\n");
        sb.append("- 실행 ID: `").append(runId).append("`\n");
        sb.append("- 모델: ").append(String.join(", ", models)).append("\n");
        sb.append("- 케이스 ").append(cases.size()).append("개 × 반복 ").append(repeats).append("회")
                .append(" · reasoning effort `").append(props.reasoningEffort()).append("`\n");
        sb.append("- 비용은 application.properties 의 단가(USD/1M tokens)로 계산한 추정치\n\n");

        sb.append("## 1. 정확도\n\n");
        sb.append("| 지표 |");
        summaries.forEach(s -> sb.append(' ').append(s.model()).append(" |"));
        sb.append("\n|---|").append("---|".repeat(summaries.size())).append('\n');
        row(sb, "통과율 (모든 항목 만점)", summaries, s -> pct(s.passRate()));
        row(sb, "평균 점수 (0~100)", summaries, s -> f(s.avgScore()));
        row(sb, "정답 상품 선택률", summaries, s -> pct(s.avgItemRecall()));
        row(sb, "수량 환산 정확도", summaries, s -> pct(s.avgQuantityAccuracy()));
        row(sb, "submit_answer 제출률", summaries, s -> pct(s.submitRate()));
        row(sb, "위반이 1건 이상인 실행", summaries, s -> pct(s.violationRunRate()));
        row(sb, "└ 없는 상품ID (환각)", summaries, s -> str(s.hallucinated()));
        row(sb, "└ 품절 상품 담음", summaries, s -> str(s.soldOut()));
        row(sb, "└ 주문 불가(주류) 담음", summaries, s -> str(s.restricted()));
        row(sb, "└ 알레르기 성분 담음", summaries, s -> str(s.allergen()));
        row(sb, "└ 조건 위반 (매운 김치·집에 있는 재료 등)", summaries, s -> str(s.avoid()));
        row(sb, "견적과 제출 내용 일치", summaries, s -> pct(s.quoteMatchRate()));
        row(sb, "합계 금액 정확", summaries, s -> pct(s.totalCorrectRate()));
        row(sb, "예산 판단 정확", summaries, s -> pct(s.budgetCorrectRate()));
        row(sb, "API/루프 오류", summaries, s -> str(s.errors()));

        sb.append("\n## 2. 토큰·비용·속도 (실행 1건 평균)\n\n");
        sb.append("| 지표 |");
        summaries.forEach(s -> sb.append(' ').append(s.model()).append(" |"));
        sb.append("\n|---|").append("---|".repeat(summaries.size())).append('\n');
        row(sb, "API 호출 수 (turn)", summaries, s -> f(s.avgTurns()));
        row(sb, "도구 호출 수", summaries, s -> f(s.avgToolCalls()));
        row(sb, "실패한 도구 호출", summaries, s -> f(s.avgFailedTools()));
        row(sb, "입력 토큰", summaries, s -> f0(s.avgInputTokens()));
        row(sb, "└ 캐시 입력 토큰", summaries, s -> f0(s.avgCachedInputTokens()));
        row(sb, "출력 토큰", summaries, s -> f0(s.avgOutputTokens()));
        row(sb, "└ reasoning 토큰", summaries, s -> f0(s.avgReasoningTokens()));
        row(sb, "총 토큰", summaries, s -> f0(s.avgTotalTokens()));
        row(sb, "비용 (USD)", summaries, s -> usd(s.avgCostUsd()));
        row(sb, "통과 1건당 비용 (USD)", summaries, s -> s.costPerPassUsd() == null ? "-" : usd(s.costPerPassUsd()));
        row(sb, "총 비용 (USD)", summaries, s -> usd(s.totalCostUsd()));
        row(sb, "평균 지연 (ms)", summaries, s -> f0(s.avgLatencyMs()));
        row(sb, "p50 / p95 지연 (ms)", summaries, s -> s.p50LatencyMs() + " / " + s.p95LatencyMs());

        sb.append("\n## 3. 케이스별 (통과 수/반복 · 평균 점수)\n\n");
        sb.append("| 케이스 |");
        models.forEach(m -> sb.append(' ').append(m).append(" |"));
        sb.append("\n|---|").append("---|".repeat(models.size())).append('\n');
        for (TestCase tc : cases) {
            sb.append("| ").append(tc.id()).append(' ').append(tc.title()).append(" |");
            for (String m : models) {
                List<ModelSummary.Row> rs = rows.get(tc.id()).get(m);
                long pass = rs.stream().filter(r -> r.eval().pass()).count();
                double score = rs.stream().mapToDouble(r -> r.eval().score()).average().orElse(0);
                sb.append(' ').append(pass).append('/').append(rs.size()).append(" · ").append(f(score)).append(" |");
            }
            sb.append('\n');
        }

        sb.append("\n## 4. 실패 상세\n\n");
        boolean any = false;
        for (TestCase tc : cases) {
            for (String m : models) {
                for (ModelSummary.Row r : rows.get(tc.id()).get(m)) {
                    if (r.eval().pass()) {
                        continue;
                    }
                    any = true;
                    sb.append("- **").append(tc.id()).append(" / ").append(m).append(" / r").append(r.run().repeat())
                            .append("** 점수 ").append(f(r.eval().score())).append(" — ").append(failureReasons(r))
                            .append('\n');
                }
            }
        }
        if (!any) {
            sb.append("실패 없음\n");
        }
        sb.append("\n자세한 입출력은 `transcripts/` 폴더, 호출별 토큰은 `api_calls.jsonl` 참고.\n");
        return sb.toString();
    }

    private static String failureReasons(ModelSummary.Row r) {
        var e = r.eval();
        if (r.run().error() != null && !e.submitted()) {
            return "오류: " + r.run().error();
        }
        StringBuilder sb = new StringBuilder();
        if (e.itemRecall() < 1) sb.append("정답 상품 누락(").append(pct(e.itemRecall())).append(") ");
        if (e.quantityAccuracy() < 1) sb.append("수량 오류 ");
        if (!e.violations().isEmpty()) sb.append("위반 ").append(e.violations()).append(' ');
        if (!e.quoteMatches()) sb.append("견적≠제출 ");
        if (!e.totalCorrect()) sb.append("합계 오류(제출 ").append(e.submittedTotal()).append(" / 실제 ").append(e.actualTotal()).append(") ");
        if (!e.budgetFlagCorrect()) sb.append("예산 판단 오류 ");
        if (!e.excludedReported()) sb.append("주문불가 안내 누락 ");
        if (!e.unavailableReported()) sb.append("미판매 재료 안내 누락 ");
        if (!e.suggestionsOk()) sb.append("추천 재료 문제 ");
        sb.append("· 선택 ").append(e.selected());
        return sb.toString().trim();
    }

    private static String violationSummary(List<ModelSummary.Row> rs) {
        return rs.stream().flatMap(r -> r.eval().violations().stream())
                .collect(Collectors.groupingBy(v -> v, java.util.LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> e.getKey() + "x" + e.getValue()).collect(Collectors.joining("; "));
    }

    private static void row(StringBuilder sb, String label, List<ModelSummary> ss,
                            java.util.function.Function<ModelSummary, String> f) {
        sb.append("| ").append(label).append(" |");
        ss.forEach(s -> sb.append(' ').append(f.apply(s)).append(" |"));
        sb.append('\n');
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String str(long v) {
        return Long.toString(v);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    private static String usd(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
