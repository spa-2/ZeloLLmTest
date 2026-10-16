# ZeloLLmTest — AI 장보기 에이전트 LLM 비교 벤치마크

AI 장보기 서비스에 쓸 LLM을 고르기 위해 **gpt-5-mini**와 **gpt-5-nano**를 같은 조건에서 비교하는 실험 프로젝트입니다.

가짜 상품 카탈로그를 주고 "토요일 저녁 김치볶음밥 재료 3만 원 안에서 준비해 줘" 같은 요청을 보냅니다. 모델이 **원하는 상품을 정확히 골라 견적까지 만드는지** 자동으로 채점하고, 이때 쓴 **토큰·비용·응답 시간**을 기록합니다.

## 결과 요약

7케이스 × 3회 × 2모델 = 42회 실행 결과입니다. 전체 내용은 [`bench-logs/20261016-162726/report.md`](bench-logs/20261016-162726/report.md)에 있습니다.

| | gpt-5-mini | gpt-5-nano |
|---|---|---|
| 통과율 (모든 채점 항목 만점) | **61.9%** | 19.0% |
| 평균 점수 (0~100) | **89.8** | 72.3 |
| 정답 상품 선택률 | **89.3%** | 65.5% |
| 실행 1건 비용 | $0.0030 | **$0.0010** |
| 통과 1건당 비용 | **$0.0048** | $0.0052 |
| 평균 / p95 응답 시간 | **11.6초 / 15.8초** | 18.8초 / 35.2초 |
| 실행 1건 평균 토큰 | **8,893** | 13,340 |

- nano는 실행 1건 비용이 1/3이지만 자주 틀립니다. 그래서 통과 1건당 비용은 mini와 비슷하거나 조금 더 높습니다. reasoning 토큰을 더 많이 써서 속도도 느립니다.
- 두 모델 모두 품절 상품, 주류, 알레르기 성분이 든 상품을 담은 적은 한 번도 없습니다.
- 카탈로그에 없는 재료가 있으면 둘 다 같은 검색을 반복하는 경향이 있습니다. 특히 nano는 견적을 만들지 않고 사용자에게 되묻다가 끝나는 경우가 많았습니다.
- 정답지가 일부 엄격합니다. 예를 들어 4명이 먹을 상추 2봉도 수량 오류로 처리합니다. 그래서 통과율의 절대값보다 두 모델 간 차이를 보는 것이 맞습니다.

## 실험 방식

모델은 아래 도구 3개만 쓸 수 있습니다. 서비스 시나리오의 "재료를 상품으로 바꾸기 → 견적 발급" 단계와 같습니다.

```
사용자 요청
   │
   ▼
search_products   카탈로그 검색 (품절·주류 상품도 결과에 섞여 나옴 → 모델이 직접 걸러야 함)
   │
   ▼
create_quote      서버가 재고·주문 가능 여부를 다시 확인하고 견적 발급 (10분 유효, Redis 대신 메모리)
   │
   ▼
submit_answer     최종 장바구니 제출 → 정답지와 비교해 자동 채점
```

- OpenAI **Responses API**를 쓰고, `previous_response_id`로 대화를 이어 갑니다. 한 케이스당 API 호출은 최대 8회입니다.
- 두 모델에 같은 시스템 프롬프트, 같은 도구, 같은 reasoning effort(기본 `low`)를 줍니다.
- 모델을 번갈아 실행해서 시간대에 따른 API 지연 차이가 한쪽에 몰리지 않게 합니다.

### 테스트 케이스

정답지는 [`src/main/resources/bench/test-cases.json`](src/main/resources/bench/test-cases.json)에 있습니다.

| ID | 요청 | 확인하는 것 |
|---|---|---|
| C01 | 김치볶음밥 4인 (초등학생 2명), 3만 원 | 맵지 않은 백김치 선택, 수량 환산(400g → 1kg 1개), 추천 재료 분리 |
| C02 | 얼큰한 김치찌개 2인 | 리뷰 좋은 묵은지가 품절일 때 포기김치로 대체 |
| C03 | 삼겹살 파티 + 소주 4병 | 주류는 담지 않고 안내 |
| C04 | 된장찌개 2인, 집에 두부·대파 있음, 1만5천 원 | 집에 있는 재료 제외, 예산 판단 |
| C05 | 우유 1L, 계란 한 판 | 둘 다 품절일 때 900ml, 10구 ×3으로 대체 |
| C06 | 비빔국수 3인, 땅콩 알레르기 | 땅콩 비빔소스 제외 |
| C07 | 김밥 2줄 | 판매하지 않는 김·단무지를 지어내지 않고 안내 |

### 채점 기준 (0~100점)

| 항목 | 배점 |
|---|---|
| 정답 상품 선택 | 35 |
| 수량 환산 | 15 |
| 위반 없음 (없는 상품 ID, 품절, 주류, 알레르기, 조건 위반) | 25 |
| 견적과 제출 내용·합계 일치 | 10 |
| 예산 판단 | 5 |
| 주문 불가·미판매 재료 안내 | 5 |
| 추천 재료 (판매 중이고 재고 있는 상품만) | 5 |

모든 항목이 만점이어야 **통과(pass)**입니다. 정답에 없지만 위반도 아닌 추가 상품(예: 참기름)은 감점하지 않습니다.

## 실행 방법

### 1. 준비

- Java 17
- OpenAI API 키 (팀 내부에서 따로 받기)

### 2. API 키 넣기

키는 **절대 커밋하지 않습니다.** `application-local.properties`는 `.gitignore`에 들어 있습니다.

```powershell
copy application-local.properties.example application-local.properties
```

`application-local.properties`를 열고 키를 넣습니다.

```properties
openai.api-key=sk-...
```

환경변수 `OPENAI_API_KEY`로 넣어도 됩니다.

### 3. 실행

**방법 A: 서버를 띄우고 API로 실행**

```powershell
.\gradlew.bat bootRun
```

다른 터미널에서:

```powershell
# 전체 실행 (application.properties 설정값 사용)
curl.exe -X POST "http://localhost:8080/api/benchmark/run"

# 일부만 실행 (처음엔 이걸로 확인 권장)
curl.exe -X POST "http://localhost:8080/api/benchmark/run?repeats=1&cases=C01,C05"

# 모델 지정
curl.exe -X POST "http://localhost:8080/api/benchmark/run?models=gpt-5-mini&repeats=1"

# 케이스 목록 보기
curl.exe "http://localhost:8080/api/benchmark/cases"
```

**방법 B: 한 번 실행하고 바로 종료**

```powershell
.\gradlew.bat bootRun --args="--benchmark.run-on-startup=true --spring.main.web-application-type=none"
```

진행 상황은 콘솔에 실행 1건마다 한 줄씩 찍히고, 끝나면 비교표가 출력됩니다.

```
[3/42] gpt-5-mini C01 r2 → PASS 점수=100.0 토큰=8540 비용=$0.00290 11200ms []
```

전체 실행(42회)은 약 10~15분 걸리고 비용은 약 $0.08입니다.

### 4. 테스트 (API 키 없이)

```powershell
.\gradlew.bat test
```

가짜 LLM 응답으로 도구 호출 → 채점 → 로그 생성까지 확인합니다. API 비용은 들지 않습니다.

## 결과 보는 법

실행할 때마다 `bench-logs/{실행시각}/` 폴더가 생깁니다.

| 파일 | 내용 | 언제 보나 |
|---|---|---|
| `report.md` | 모델별 정확도·토큰·비용·속도 비교표, 케이스별 결과, 실패 상세 | **가장 먼저** |
| `summary.csv` | 모델별 집계 (엑셀에서 바로 열림) | 발표 자료용 표·그래프 |
| `case_matrix.csv` | 케이스 × 모델별 통과 수, 점수, 토큰, 비용 | 어떤 케이스에서 차이가 나는지 |
| `case_results.jsonl` | 실행 1건 = 1줄: 채점 결과, 고른 상품, 토큰, 비용 | 세부 분석 |
| `api_calls.jsonl` | API 호출 1회 = 1줄: 입력·캐시·출력·reasoning 토큰, 지연, 호출한 도구 | 토큰 사용량 분석 |
| `transcripts/{모델}__{케이스}__r{회차}.json` | 실행 1건의 전체 입출력 | 모델이 왜 틀렸는지 확인 |

비용은 `application.properties`의 단가로 계산한 추정치입니다. 실행 전에 [OpenAI 가격표](https://openai.com/api/pricing/)와 맞는지 확인하세요.

## 설정 (`src/main/resources/application.properties`)

| 키 | 기본값 | 설명 |
|---|---|---|
| `benchmark.models` | `gpt-5-mini,gpt-5-nano` | 비교할 모델 |
| `benchmark.repeats` | `3` | 케이스당 반복 횟수 |
| `benchmark.cases` | (전체) | 실행할 케이스 ID, 예: `C01,C03` |
| `benchmark.reasoning-effort` | `low` | `minimal` / `low` / `medium` / `high` |
| `benchmark.max-turns` | `8` | 케이스당 최대 API 호출 수 |
| `benchmark.max-output-tokens` | `8000` | 호출당 최대 출력 토큰 |
| `benchmark.log-dir` | `bench-logs` | 로그 폴더 |
| `benchmark.run-on-startup` | `false` | `true`면 서버 시작 시 바로 실행 |
| `benchmark.pricing[모델].input / cached-input / output` | | 단가 (USD / 1M 토큰) |

## 케이스·상품 추가하기

- **상품:** [`bench/products.json`](src/main/resources/bench/products.json)에 추가합니다. `stock: 0`이면 품절, `agentOrderable: false`면 주문 불가 품목입니다.
- **케이스:** [`bench/test-cases.json`](src/main/resources/bench/test-cases.json)에 추가합니다.
  - `expectedItems[].accept`: 정답으로 인정할 상품 ID → 기대 수량
  - `optionalIds`: 담아도 감점하지 않는 상품
  - `avoidIds`: 담으면 위반인 상품 (예: 아이용인데 매운 김치)
  - `allergens`: 담으면 위반인 알레르기 성분
  - `expectExcluded` / `expectUnavailable`: 안내 메시지에 꼭 들어가야 하는 키워드
- **프롬프트:** [`bench/system-prompt.txt`](src/main/resources/bench/system-prompt.txt)
- **도구 정의:** [`bench/tools.json`](src/main/resources/bench/tools.json)

## 코드 구조

```
src/main/java/com/example/demo/bench
├── BenchmarkService.java        전체 실행·집계·리포트 생성
├── BenchmarkStartupRunner.java  run-on-startup 실행
├── web/BenchmarkController.java POST /api/benchmark/run, GET /api/benchmark/cases
├── agent/
│   ├── AgentRunner.java         도구 호출 루프 (Responses API)
│   ├── ShoppingTools.java       search_products, create_quote 서버 구현
│   └── QuoteStore.java          견적 저장 (TTL 10분, Redis 대용)
├── catalog/                     상품·케이스 로딩, 검색
├── eval/Evaluator.java          정답지 기반 채점
├── llm/                         OpenAI 호출(429·5xx 재시도), 토큰·비용 계산
└── report/                      JSONL 로그, CSV, report.md 작성
```

## 알려진 한계

- 상품은 45개로 적고, 검색은 단순한 단어 매칭입니다. 실제 검색 엔진보다 결과가 거칠 수 있습니다.
- 정답지는 사람이 정한 기준이라 일부 합리적인 선택도 감점될 수 있습니다. 실패 상세는 `report.md`와 `transcripts/`에서 직접 확인하는 것을 권합니다.
- 실제 주문·결제 단계는 아직 포함하지 않았습니다. 견적 발급까지만 비교합니다.
