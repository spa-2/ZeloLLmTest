package com.example.demo.bench.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.bench.BenchmarkService;
import com.example.demo.bench.catalog.TestCase;
import com.example.demo.bench.catalog.TestCaseRepository;

@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private final BenchmarkService service;
    private final TestCaseRepository cases;

    public BenchmarkController(BenchmarkService service, TestCaseRepository cases) {
        this.service = service;
        this.cases = cases;
    }

    /**
     * 예: POST /api/benchmark/run?models=gpt-5-mini,gpt-5-nano&amp;repeats=3&amp;cases=C01,C05
     * 파라미터를 생략하면 application.properties 값을 사용한다. 끝날 때까지 응답을 기다린다.
     */
    @PostMapping("/run")
    public BenchmarkService.Result run(@RequestParam(required = false) List<String> models,
                                       @RequestParam(required = false) Integer repeats,
                                       @RequestParam(required = false) List<String> cases) {
        return service.run(models, repeats, cases);
    }

    @GetMapping("/cases")
    public List<TestCase> cases() {
        return cases.select(List.of());
    }
}
