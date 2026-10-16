package com.example.demo.bench.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.example.demo.bench.agent.AgentRun;
import com.example.demo.bench.catalog.TestCase;
import com.example.demo.bench.eval.CaseEvaluation;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실행 중 로그를 바로바로 파일에 쓴다 (중간에 끊겨도 남도록).
 * <pre>
 * bench-logs/{runId}/api_calls.jsonl     API 호출 1회 = 1줄 (토큰·지연·호출한 도구)
 * bench-logs/{runId}/case_results.jsonl  실행 1건 = 1줄 (채점 결과·토큰 합계·비용)
 * bench-logs/{runId}/transcripts/*.json  실행 1건의 전체 입출력 (디버깅용)
 * </pre>
 */
public class RunLogger {

    private final Path dir;
    private final ObjectMapper mapper;

    public RunLogger(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
        try {
            Files.createDirectories(dir.resolve("transcripts"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path dir() {
        return dir;
    }

    public void log(TestCase tc, AgentRun run, CaseEvaluation eval) {
        for (AgentRun.ApiCallLog call : run.apiCalls()) {
            append("api_calls.jsonl", mapper.writeValueAsString(call));
        }

        ObjectNode line = mapper.createObjectNode();
        line.put("model", run.model());
        line.put("case_id", tc.id());
        line.put("case_title", tc.title());
        line.put("repeat", run.repeat());
        line.put("pass", eval.pass());
        line.put("score", eval.score());
        line.put("error", run.error());
        line.put("turns", run.turns());
        line.set("tool_calls", mapper.valueToTree(run.toolCalls()));
        line.put("failed_tool_calls", run.failedTools());
        line.put("input_tokens", run.usage().input());
        line.put("cached_input_tokens", run.usage().cachedInput());
        line.put("output_tokens", run.usage().output());
        line.put("reasoning_tokens", run.usage().reasoning());
        line.put("total_tokens", run.usage().total());
        line.put("cost_usd", run.costUsd());
        line.put("latency_ms", run.latencyMs());
        line.set("evaluation", mapper.valueToTree(eval));
        line.set("submitted", run.submitted());
        line.put("final_text", run.finalText());
        append("case_results.jsonl", mapper.writeValueAsString(line));

        ObjectNode transcript = mapper.createObjectNode();
        transcript.put("model", run.model());
        transcript.put("case_id", tc.id());
        transcript.put("user_message", tc.userMessage());
        transcript.put("repeat", run.repeat());
        transcript.set("turns", run.transcript());
        transcript.set("evaluation", mapper.valueToTree(eval));
        String file = "%s__%s__r%d.json".formatted(run.model(), tc.id(), run.repeat());
        write(dir.resolve("transcripts").resolve(file), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(transcript));
    }

    public void write(String fileName, String content) {
        write(dir.resolve(fileName), content);
    }

    private void append(String fileName, String line) {
        try {
            Files.writeString(dir.resolve(fileName), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
