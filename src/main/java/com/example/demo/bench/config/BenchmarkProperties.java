package com.example.demo.bench.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * benchmark.* 설정.
 *
 * @param models          비교할 모델 목록
 * @param repeats         케이스당 반복 횟수 (LLM 출력 편차를 보기 위해 3회 이상 권장)
 * @param cases           실행할 케이스 ID (비어 있으면 전체)
 * @param reasoningEffort 두 모델에 동일하게 줄 reasoning effort (minimal/low/medium/high)
 * @param maxTurns        한 케이스에서 허용하는 최대 API 호출 수
 * @param maxOutputTokens 호출당 max_output_tokens
 * @param logDir          로그 출력 폴더
 * @param runOnStartup    true면 애플리케이션 시작 시 바로 벤치마크 실행
 * @param pricing         모델별 단가 (USD / 1M tokens)
 */
@ConfigurationProperties("benchmark")
public record BenchmarkProperties(
        List<String> models,
        @DefaultValue("3") int repeats,
        @DefaultValue List<String> cases,
        @DefaultValue("low") String reasoningEffort,
        @DefaultValue("8") int maxTurns,
        @DefaultValue("8000") int maxOutputTokens,
        @DefaultValue("bench-logs") String logDir,
        @DefaultValue("false") boolean runOnStartup,
        @DefaultValue Map<String, Pricing> pricing) {

    public record Pricing(double input, double cachedInput, double output) {
    }

    public Pricing pricingFor(String model) {
        return pricing.getOrDefault(model, new Pricing(0, 0, 0));
    }
}
