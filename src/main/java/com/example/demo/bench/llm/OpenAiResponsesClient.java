package com.example.demo.bench.llm;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.example.demo.bench.config.OpenAiProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** OpenAI Responses API (POST /v1/responses) 호출. 429/5xx/네트워크 오류는 지수 백오프로 재시도. */
@Component
public class OpenAiResponsesClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesClient.class);
    private static final int MAX_ATTEMPTS = 4;

    private final OpenAiProperties props;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public OpenAiResponsesClient(OpenAiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * @param body       요청 본문
     * @param response   응답 JSON
     * @param latencyMs  성공한 시도의 왕복 시간
     * @param attempts   재시도 포함 시도 횟수
     */
    public record Call(ObjectNode body, JsonNode response, long latencyMs, int attempts) {
    }

    public Call create(ObjectNode body) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("openai.api-key 가 비어 있습니다 (application.properties 또는 OPENAI_API_KEY 환경변수).");
        }
        String json = mapper.writeValueAsString(body);
        for (int attempt = 1; ; attempt++) {
            long start = System.nanoTime();
            try {
                String raw = restClient.post()
                        .uri(props.apiUrl())
                        .header("Authorization", "Bearer " + props.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json)
                        .retrieve()
                        .body(String.class);
                long latency = (System.nanoTime() - start) / 1_000_000;
                return new Call(body, mapper.readTree(raw), latency, attempt);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw new OpenAiException(status, e.getResponseBodyAsString());
                }
                log.warn("OpenAI {} 응답, 재시도 {}/{}", status, attempt, MAX_ATTEMPTS - 1);
            } catch (ResourceAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new OpenAiException(-1, e.getMessage());
                }
                log.warn("OpenAI 네트워크 오류 ({}), 재시도 {}/{}", e.getMessage(), attempt, MAX_ATTEMPTS - 1);
            }
            sleep(1000L << attempt);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static class OpenAiException extends RuntimeException {
        private final int status;

        public OpenAiException(int status, String body) {
            super("OpenAI API 오류 " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
