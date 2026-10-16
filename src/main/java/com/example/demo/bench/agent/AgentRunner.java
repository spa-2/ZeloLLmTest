package com.example.demo.bench.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.example.demo.bench.agent.AgentRun.ApiCallLog;
import com.example.demo.bench.catalog.TestCase;
import com.example.demo.bench.config.BenchmarkProperties;
import com.example.demo.bench.llm.OpenAiResponsesClient;
import com.example.demo.bench.llm.TokenUsage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 도구 호출 루프. 모델이 submit_answer를 부를 때까지 (최대 maxTurns번)
 * search_products / create_quote 결과를 previous_response_id로 이어 붙여 다시 호출한다.
 */
@Component
public class AgentRunner {

    private final OpenAiResponsesClient client;
    private final ShoppingTools tools;
    private final BenchmarkProperties props;
    private final ObjectMapper mapper;
    private final String systemPrompt;

    public AgentRunner(OpenAiResponsesClient client, ShoppingTools tools, BenchmarkProperties props, ObjectMapper mapper) {
        this.client = client;
        this.tools = tools;
        this.props = props;
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource("bench/system-prompt.txt").getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public AgentRun run(String model, TestCase tc, int repeat) {
        QuoteStore quotes = new QuoteStore();
        ArrayNode transcript = mapper.createArrayNode();
        List<ApiCallLog> apiCalls = new ArrayList<>();
        Map<String, Integer> toolCalls = new LinkedHashMap<>();
        TokenUsage usage = TokenUsage.ZERO;
        long latency = 0;
        int failedTools = 0;
        JsonNode submitted = null;
        String finalText = null;
        String error = null;
        int turn = 0;

        ArrayNode input = mapper.createArrayNode();
        ObjectNode userMsg = input.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", tc.userMessage());
        String previousId = null;

        try {
            while (submitted == null && turn < props.maxTurns()) {
                turn++;
                OpenAiResponsesClient.Call call = client.create(requestBody(model, input, previousId));
                JsonNode resp = call.response();
                TokenUsage u = TokenUsage.from(resp);
                double cost = u.costUsd(props.pricingFor(model));
                usage = usage.plus(u);
                latency += call.latencyMs();

                ObjectNode t = transcript.addObject();
                t.put("turn", turn);
                t.set("input", input);
                t.set("output", resp.path("output"));
                t.set("usage", resp.path("usage"));
                ArrayNode toolResults = t.putArray("tool_results");

                ArrayNode nextInput = mapper.createArrayNode();
                List<String> calledNames = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (JsonNode item : resp.path("output")) {
                    String type = item.path("type").asString("");
                    if (type.equals("message")) {
                        for (JsonNode c : item.path("content")) {
                            if (c.path("type").asString("").equals("output_text")) {
                                text.append(c.path("text").asString(""));
                            }
                        }
                    }
                    if (!type.equals("function_call")) {
                        continue;
                    }
                    String name = item.path("name").asString("");
                    String callId = item.path("call_id").asString("");
                    calledNames.add(name);
                    toolCalls.merge(name, 1, Integer::sum);

                    ShoppingTools.ToolResult result;
                    JsonNode args = parseArgs(item.path("arguments").asString("{}"));
                    if (args == null) {
                        result = new ShoppingTools.ToolResult("{\"ok\":false,\"error\":\"arguments가 올바른 JSON이 아닙니다\"}", true);
                    } else if (name.equals(ShoppingTools.SUBMIT)) {
                        submitted = args;
                        result = new ShoppingTools.ToolResult("{\"ok\":true}", false);
                    } else {
                        result = tools.execute(name, args, quotes);
                    }
                    if (result.error()) {
                        failedTools++;
                    }
                    ObjectNode out = nextInput.addObject();
                    out.put("type", "function_call_output");
                    out.put("call_id", callId);
                    out.put("output", result.output());
                    ObjectNode tr = toolResults.addObject();
                    tr.put("name", name);
                    tr.put("output", result.output());
                }
                if (!text.isEmpty()) {
                    finalText = text.toString();
                }

                apiCalls.add(new ApiCallLog(Instant.now().toString(), model, tc.id(), repeat, turn,
                        resp.path("id").asString(null), resp.path("status").asString(null),
                        resp.path("incomplete_details").path("reason").asString(null),
                        call.latencyMs(), call.attempts(), u.input(), u.cachedInput(), u.output(), u.reasoning(),
                        u.total(), cost, calledNames));

                if (submitted != null) {
                    break;
                }
                if (calledNames.isEmpty()) {
                    error = "submit_answer 없이 종료 (status=" + resp.path("status").asString("?") + ")";
                    break;
                }
                input = nextInput;
                previousId = resp.path("id").asString();
            }
            if (submitted == null && error == null) {
                error = "maxTurns(" + props.maxTurns() + ") 초과";
            }
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        return new AgentRun(model, tc.id(), repeat, submitted, finalText, turn, toolCalls, failedTools, usage,
                usage.costUsd(props.pricingFor(model)), latency, error, quotes, transcript, apiCalls);
    }

    private ObjectNode requestBody(String model, ArrayNode input, String previousId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("instructions", systemPrompt);
        body.set("input", input);
        body.set("tools", tools.definitions());
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", true);
        body.putObject("reasoning").put("effort", props.reasoningEffort());
        body.put("max_output_tokens", props.maxOutputTokens());
        if (previousId != null) {
            body.put("previous_response_id", previousId);
        }
        return body;
    }

    private JsonNode parseArgs(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (JacksonException e) {
            return null;
        }
    }
}
