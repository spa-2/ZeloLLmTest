package com.example.demo.bench.llm;

import com.example.demo.bench.config.BenchmarkProperties.Pricing;

import tools.jackson.databind.JsonNode;

/** Responses API usage 필드. reasoning 토큰은 output 토큰에 포함되어 과금된다. */
public record TokenUsage(long input, long cachedInput, long output, long reasoning) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    public static TokenUsage from(JsonNode response) {
        JsonNode u = response.path("usage");
        return new TokenUsage(
                u.path("input_tokens").asLong(0),
                u.path("input_tokens_details").path("cached_tokens").asLong(0),
                u.path("output_tokens").asLong(0),
                u.path("output_tokens_details").path("reasoning_tokens").asLong(0));
    }

    public TokenUsage plus(TokenUsage o) {
        return new TokenUsage(input + o.input, cachedInput + o.cachedInput, output + o.output, reasoning + o.reasoning);
    }

    public long total() {
        return input + output;
    }

    /** 단가는 USD / 1M tokens */
    public double costUsd(Pricing p) {
        return ((input - cachedInput) * p.input() + cachedInput * p.cachedInput() + output * p.output()) / 1_000_000.0;
    }
}
