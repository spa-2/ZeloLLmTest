package com.example.demo.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.demo.bench.agent.AgentRunner;
import com.example.demo.bench.agent.ShoppingTools;
import com.example.demo.bench.catalog.ProductCatalog;
import com.example.demo.bench.catalog.TestCaseRepository;
import com.example.demo.bench.config.BenchmarkProperties;
import com.example.demo.bench.config.OpenAiProperties;
import com.example.demo.bench.eval.Evaluator;
import com.example.demo.bench.llm.OpenAiResponsesClient;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** API 키 없이 도구 루프 → 채점 → 로그/리포트 생성까지 확인하는 오프라인 테스트. */
class BenchmarkPipelineTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** 미리 정해 둔 응답을 순서대로 돌려주는 가짜 클라이언트 */
    static class ScriptedClient extends OpenAiResponsesClient {
        private final Deque<String> responses;
        private final ObjectMapper mapper;

        ScriptedClient(ObjectMapper mapper, List<String> responses) {
            super(new OpenAiProperties("fake", "http://localhost", 1), mapper);
            this.mapper = mapper;
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public Call create(ObjectNode body) {
            return new Call(body, mapper.readTree(responses.removeFirst()), 100, 1);
        }
    }

    private static String functionCall(String id, String name, String args) {
        String escaped = args.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"id":"%s","status":"completed",
                 "output":[{"type":"reasoning","id":"rs_1","summary":[]},
                           {"type":"function_call","id":"fc_%s","call_id":"call_%s","name":"%s","arguments":"%s"}],
                 "usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":200},
                          "output_tokens":300,"output_tokens_details":{"reasoning_tokens":150},"total_tokens":1300}}
                """.formatted(id, id, id, name, escaped);
    }

    @Test
    void scriptedRunIsScoredAndLogged(@TempDir Path tmp) throws Exception {
        BenchmarkProperties props = new BenchmarkProperties(List.of("fake-model"), 1, List.of("C01"), "low", 8, 8000,
                tmp.toString(), false,
                Map.of("fake-model", new BenchmarkProperties.Pricing(0.25, 0.025, 2.0)));
        ProductCatalog catalog = new ProductCatalog(mapper);
        ShoppingTools tools = new ShoppingTools(catalog, mapper);

        ScriptedClient client = new ScriptedClient(mapper, List.of(
                functionCall("resp_1", "search_products", "{\"category\":\"김치\",\"keyword\":null}"),
                functionCall("resp_2", "create_quote",
                        "{\"items\":[{\"product_id\":\"P102\",\"quantity\":1},{\"product_id\":\"P201\",\"quantity\":1},"
                                + "{\"product_id\":\"P301\",\"quantity\":1},{\"product_id\":\"P401\",\"quantity\":1}]}"),
                functionCall("resp_3", "submit_answer", """
                        {"quote_id":"Q1","menu":"김치볶음밥","servings":4,
                         "items":[{"ingredient":"김치","product_id":"P102","quantity":1},
                                  {"ingredient":"밥","product_id":"P201","quantity":1},
                                  {"ingredient":"대파","product_id":"P301","quantity":1},
                                  {"ingredient":"계란","product_id":"P401","quantity":1}],
                         "suggestions":[{"ingredient":"참치","product_id":"P601"}],
                         "unavailable_ingredients":[],"excluded_requests":[],
                         "total_price":21500,"within_budget":true,"message":"준비했어요"}""")));

        BenchmarkService service = new BenchmarkService(new AgentRunner(client, tools, props, mapper),
                new Evaluator(catalog), new TestCaseRepository(mapper), props, mapper);
        BenchmarkService.Result result = service.run(null, null, null);

        var s = result.summaries().get(0);
        assertEquals(1, s.runs());
        assertEquals(1.0, s.passRate(), "정답 장바구니는 통과해야 함");
        assertEquals(3, s.avgTurns());
        assertEquals(3900, s.avgTotalTokens());
        // (2400 비캐시 * 0.25 + 600 캐시 * 0.025 + 900 출력 * 2.0) / 1M
        assertEquals(0.002415, s.avgCostUsd(), 1e-9);

        Path dir = Path.of(result.logDir());
        assertEquals(3, Files.readAllLines(dir.resolve("api_calls.jsonl")).size());
        assertEquals(1, Files.readAllLines(dir.resolve("case_results.jsonl")).size());
        assertTrue(Files.exists(dir.resolve("transcripts/fake-model__C01__r1.json")));
        assertTrue(Files.readString(dir.resolve("report.md")).contains("100.0%"));
    }

    @Test
    void wrongCartIsPenalized() {
        ProductCatalog catalog = new ProductCatalog(mapper);
        BenchmarkProperties props = new BenchmarkProperties(List.of("m"), 1, List.of(), "low", 8, 8000, "x", false, Map.of());
        ShoppingTools tools = new ShoppingTools(catalog, mapper);
        // 매운 포기김치 + 품절 계란 + 없는 상품ID, 견적 없이 제출
        ScriptedClient client = new ScriptedClient(mapper, List.of(functionCall("resp_1", "submit_answer", """
                {"quote_id":null,"menu":"김치볶음밥","servings":4,
                 "items":[{"ingredient":"김치","product_id":"P101","quantity":1},
                          {"ingredient":"계란","product_id":"P402","quantity":1},
                          {"ingredient":"햄","product_id":"P999","quantity":1}],
                 "suggestions":[{"ingredient":"치즈","product_id":"P605"}],
                 "unavailable_ingredients":[],"excluded_requests":[],
                 "total_price":20000,"within_budget":true,"message":"x"}""")));
        var tc = new TestCaseRepository(mapper).select(List.of("C01")).get(0);
        var run = new AgentRunner(client, tools, props, mapper).run("m", tc, 1);
        var eval = new Evaluator(catalog).evaluate(tc, run);

        assertFalse(eval.pass());
        assertTrue(eval.violations().contains("HALLUCINATED:P999"));
        assertTrue(eval.violations().contains("SOLD_OUT:P402"));
        assertTrue(eval.violations().stream().anyMatch(v -> v.startsWith("AVOID:P101")));
        assertTrue(eval.violations().contains("SUGGESTION_SOLD_OUT:P605"));
        assertFalse(eval.quoteMatches());
    }

    @Test
    void searchToleratesQuantityWords() {
        ProductCatalog catalog = new ProductCatalog(mapper);
        // gpt-5-nano 가 실제로 보낸 검색어들
        assertTrue(catalog.search("쌀/밥", "쌀 1kg").stream().anyMatch(p -> p.id().equals("P203")));
        assertFalse(catalog.search("쌀/밥", "1kg").isEmpty());
        assertEquals("P301", catalog.search("채소", "대파 2대").get(0).id());
        assertEquals("P401", catalog.search("계란", "계란 10구").get(0).id());
        assertTrue(catalog.search(null, "고추장 순한맛 300g").stream().anyMatch(p -> p.id().equals("P902")));
    }

    @Test
    void quoteRejectsSoldOutAndRestricted() {
        ProductCatalog catalog = new ProductCatalog(mapper);
        ShoppingTools tools = new ShoppingTools(catalog, mapper);
        var result = tools.execute("create_quote", mapper.readTree(
                "{\"items\":[{\"product_id\":\"PA01\",\"quantity\":4},{\"product_id\":\"P402\",\"quantity\":1}]}"),
                new com.example.demo.bench.agent.QuoteStore());
        assertTrue(result.error());
        assertTrue(result.output().contains("PA01") && result.output().contains("P402"));
    }
}
