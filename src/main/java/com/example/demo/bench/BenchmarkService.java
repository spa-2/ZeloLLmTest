package com.example.demo.bench;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.demo.bench.agent.AgentRun;
import com.example.demo.bench.agent.AgentRunner;
import com.example.demo.bench.catalog.TestCase;
import com.example.demo.bench.catalog.TestCaseRepository;
import com.example.demo.bench.config.BenchmarkProperties;
import com.example.demo.bench.eval.CaseEvaluation;
import com.example.demo.bench.eval.Evaluator;
import com.example.demo.bench.report.ModelSummary;
import com.example.demo.bench.report.ReportWriter;
import com.example.demo.bench.report.RunLogger;

import tools.jackson.databind.ObjectMapper;

@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    private final AgentRunner runner;
    private final Evaluator evaluator;
    private final TestCaseRepository caseRepository;
    private final BenchmarkProperties props;
    private final ObjectMapper mapper;

    public BenchmarkService(AgentRunner runner, Evaluator evaluator, TestCaseRepository caseRepository,
                            BenchmarkProperties props, ObjectMapper mapper) {
        this.runner = runner;
        this.evaluator = evaluator;
        this.caseRepository = caseRepository;
        this.props = props;
        this.mapper = mapper;
    }

    public record Result(String runId, String logDir, List<ModelSummary> summaries) {
    }

    /** null 인자는 application.properties 값을 사용. */
    public synchronized Result run(List<String> models, Integer repeats, List<String> caseIds) {
        List<String> ms = models == null || models.isEmpty() ? props.models() : models;
        int reps = repeats == null ? props.repeats() : repeats;
        List<TestCase> cases = caseRepository.select(caseIds == null || caseIds.isEmpty() ? props.cases() : caseIds);

        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        RunLogger logger = new RunLogger(Path.of(props.logDir(), runId), mapper);
        log.info("벤치마크 시작 runId={} models={} cases={} repeats={} → {}", runId, ms,
                cases.stream().map(TestCase::id).toList(), reps, logger.dir().toAbsolutePath());

        // caseId → model → rows
        Map<String, Map<String, List<ModelSummary.Row>>> rows = new LinkedHashMap<>();
        Map<String, List<ModelSummary.Row>> byModel = new LinkedHashMap<>();
        ms.forEach(m -> byModel.put(m, new ArrayList<>()));

        int total = cases.size() * reps * ms.size();
        int done = 0;
        for (TestCase tc : cases) {
            Map<String, List<ModelSummary.Row>> perModel = new LinkedHashMap<>();
            ms.forEach(m -> perModel.put(m, new ArrayList<>()));
            rows.put(tc.id(), perModel);
            for (int r = 1; r <= reps; r++) {
                // 모델을 번갈아 실행해 시간대에 따른 API 지연 편차를 양쪽에 고르게 분산
                for (String m : ms) {
                    AgentRun run = runner.run(m, tc, r);
                    CaseEvaluation eval = evaluator.evaluate(tc, run);
                    logger.log(tc, run, eval);
                    ModelSummary.Row row = new ModelSummary.Row(run, eval);
                    perModel.get(m).add(row);
                    byModel.get(m).add(row);
                    done++;
                    log.info("[{}/{}] {} {} r{} → {} 점수={} 토큰={} 비용=${} {}ms {}", done, total, m, tc.id(), r,
                            eval.pass() ? "PASS" : "FAIL", eval.score(), run.usage().total(),
                            String.format("%.5f", run.costUsd()), run.latencyMs(),
                            run.error() != null ? "오류=" + run.error() : eval.violations());
                }
            }
        }

        List<ModelSummary> summaries = ms.stream().map(m -> ModelSummary.of(m, byModel.get(m))).toList();
        logger.write("summary.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summaries));
        logger.write("summary.csv", ReportWriter.summaryCsv(summaries));
        logger.write("case_matrix.csv", ReportWriter.caseMatrixCsv(cases, ms, rows));
        String report = ReportWriter.markdown(runId, props, ms, reps, cases, summaries, rows);
        logger.write("report.md", report);
        log.info("벤치마크 완료 → {}\n{}", logger.dir().resolve("report.md").toAbsolutePath(), report);
        return new Result(runId, logger.dir().toAbsolutePath().toString(), summaries);
    }
}
