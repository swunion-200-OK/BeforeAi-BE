# 구현 계획: AWS Bedrock AI 분석기 연동

> 대상: 프롬프트 작업 요청의 AI 분석 → Plan(작업 목록) 자동 생성 + 위험도 분류.
> 기준 코드: `RequestService.create`, `RequestPlanService.retryAnalysis/patchPlan`, `EditorService`(위험도), `Request`/`Plan`/`Task` 엔티티, `RequestStreamService`(SSE).
> 현재 상태: 상태 머신·엔티티·API 골격은 완성. **LLM 호출 레이어만 비어 있음**(코드 주석 "AI 분석기(Bedrock) 연동 전").

---

## 1. 목표와 범위

### 하는 일
1. `POST /requests` 로 요청 생성 시 **비동기로 Bedrock 분석**을 시작한다.
2. Bedrock이 프롬프트 + 프로젝트 컨텍스트를 받아 **Plan(Task 목록)을 구조화(JSON)로 생성**한다.
3. 각 Task의 **위험도(Risk)를 분류**한다(에디터 변경 포함).
4. 분석 성공 → `REVIEWING`(+Plan 연결), 실패 → `ANALYSIS_FAILED`. 추가 정보 필요 → `clarification` 질문 저장.
5. 진행 상태 변화를 **SSE로 publish**한다(`RequestStreamService`).

### 안 하는 일 (이번 범위 밖)
- 실제 코드 실행(웹쉘), 승인/서명, 문서 롤백 — 기존 플로우 그대로.
- 금지 규칙/승인 기준표 자체 관리(이미 구현) — 분석기는 **읽어서 입력으로만** 씀.
- 스트리밍 토큰 출력(초기엔 단건 응답). 추후 확장.

---

## 2. 상태 흐름 (기존 enum 그대로 사용)

```
POST /requests
  └─ Request 저장(status=ANALYZING, analysisAttempts=1)   [동기, 기존]
     └─ 비동기 분석 시작                                     [신규]
         ├─ 성공: Plan+Task 생성 → status=REVIEWING, currentPlanId 연결
         ├─ 질문 필요: clarification 저장 → status=REVIEWING (요청자가 answer)
         └─ 실패: status=ANALYSIS_FAILED

POST /requests/{id}/analysis/retry   (ANALYSIS_FAILED, attempts<3)   [기존 → 분석 재호출 추가]
PATCH /requests/{id}/plan (answer/UPDATE)  → status=ANALYZING → 재분석  [기존 → 분석 재호출 추가]
```

- `RequestStatus`에 값 추가 없음. `ANALYZING`/`ANALYSIS_FAILED`/`REVIEWING`/`clarification`/`analysisAttempts(최대 3)` 모두 이미 존재.
- 재분석 입력(UPDATE instruction, answer)은 현재 "저장·전달 안 함" 주석 → 이번에 분석기 입력으로 전달.

---

## 3. 의존성 (build.gradle)

AWS SDK v2 Bedrock Runtime 사용(공식, 비동기 지원).

```gradle
dependencies {
    // AWS Bedrock Runtime (BOM으로 버전 일괄 관리)
    implementation platform('software.amazon.awssdk:bom:2.28.29')
    implementation 'software.amazon.awssdk:bedrockruntime'
    // JSON 파싱은 이미 있는 Jackson(spring-boot-starter) 사용
}
```

- 버전은 작성 시점 최신 안정 버전으로 확정(BOM 사용 권장).
- 인증: EC2 **IAM Role**(인스턴스 프로파일) 권장. 로컬은 `AWS_PROFILE`/기본 체인.
- **모델: Claude Sonnet 5.5** (2026-09-28 출시, Claude 5.5 패밀리의 최신 Sonnet. 코딩·에이전트 최적).
  - 참고: Sonnet 4.5 는 2026-11-30 retire 예정이라 쓰지 않는다.
  - 컨텍스트 1M 토큰, 최대 출력 128K. API 기준 ID는 `claude-sonnet-5-5`.
  - 버전 suffix: Anthropic이 Sonnet 4.6부터 `-v1` suffix를 제거 → 5.5도 suffix 없음.
- **리전: 서버(EC2)에 이미 설정됨.** 애플리케이션은 리전을 명시하지 않고 기본 체인(인스턴스 리전)을 따르거나, 환경변수로만 받는다.
- **호출 방식 주의 — inference profile 필수**: 최신 Claude 모델은 리전 직접 model-id가 아니라
  **cross-Region inference profile ID**로 호출한다. 리전 지역에 따라 prefix가 다르다:
  - APAC(서울 포함): `apac.anthropic.claude-sonnet-5-5` 계열
  - US: `us.anthropic.claude-sonnet-5-5` 계열
  - Global: `global.anthropic.claude-sonnet-5-5` 계열
  **정확한 profile ID 는 배포 리전의 Bedrock 콘솔 "Inference profiles"에서 복사해 확정**한다
  (prefix·접미사 규칙이 버전마다 달라질 수 있으므로 콘솔 값이 최종 기준).
  IAM 권한은 inference profile ARN + 그 프로필이 라우팅하는 리전들의 foundation model까지 필요.

---

## 4. 설정 (application.properties + .env)

```properties
# Bedrock. 비면 분석기 비활성(요청은 ANALYZING에서 수동 편집만) — 기존 동작 보존
bedrock.enabled=${BEDROCK_ENABLED:false}
# 리전은 서버(EC2 인스턴스/IAM/환경)에 설정됨. 비우면 SDK 기본 리전 체인을 따름.
bedrock.region=${BEDROCK_REGION:}
# Sonnet 5.5 는 cross-Region inference profile ID 로 호출 (리전 콘솔에서 복사해 확정)
bedrock.model-id=${BEDROCK_MODEL_ID:apac.anthropic.claude-sonnet-5-5}
bedrock.max-tokens=${BEDROCK_MAX_TOKENS:4096}
bedrock.temperature=${BEDROCK_TEMPERATURE:0.2}
bedrock.timeout-seconds=${BEDROCK_TIMEOUT_SECONDS:60}
```

`.env`(EC2) 추가 키: `BEDROCK_ENABLED=true`, `BEDROCK_MODEL_ID`(콘솔 값).
`BEDROCK_REGION`은 서버에 리전이 이미 잡혀 있으면 생략 가능(SDK 기본 체인 사용).
IAM 자격증명은 `.env`에 넣지 말고 **인스턴스 IAM Role**로 주입(키 노출 방지).

**플래그 설계 핵심**: `bedrock.enabled=false`면 현재와 100% 동일하게 동작(연동 전 상태 보존). CI/CD·기존 테스트 영향 없음.

---

## 5. 컴포넌트 설계 (신규 파일)

```
ai/
├─ BedrockConfig.java            # BedrockRuntimeAsyncClient 빈 (enabled일 때만)
├─ AiAnalyzer.java               # 인터페이스: analyze(AnalysisInput) -> AnalysisResult
├─ BedrockAiAnalyzer.java        # 구현: 프롬프트 빌드 → invokeModel → 파싱
├─ NoopAiAnalyzer.java           # enabled=false일 때 빈 구현(아무 것도 안 함)
├─ prompt/
│   ├─ PromptBuilder.java        # system + user 프롬프트 조립
│   └─ templates/analysis.st     # 프롬프트 템플릿(리소스)
├─ dto/
│   ├─ AnalysisInput.java        # prompt, projectContext, forbiddenRules, approvalMatrix, priorPlan, answer
│   ├─ AnalysisResult.java       # tasks[], clarification?, failedReason?
│   └─ AiTask.java               # type, title, target, content, risk, relatedDepartments
└─ RequestAnalysisService.java   # 비동기 오케스트레이션 + 상태 전이 + SSE
```

### 5-1. `AiAnalyzer` (경계 인터페이스)
- 입력/출력을 도메인 DTO로 고정해, Bedrock을 다른 LLM으로 바꿔도 이 경계만 유지.
- `enabled` 여부로 `BedrockAiAnalyzer`/`NoopAiAnalyzer` 중 하나를 빈 등록(`@ConditionalOnProperty`).

### 5-2. `RequestAnalysisService` (오케스트레이션)
- `@Async` 메서드 `analyze(UUID requestId)`.
- 흐름: 요청 로드 → 컨텍스트 수집(프로젝트/금지규칙/승인기준표) → `AiAnalyzer.analyze` →
  - 성공: Plan+Task 저장, Task별 Risk 반영, 금지규칙과 매칭되는 Task는 `Risk.FORBIDDEN`,
    승인 기준표로 `requiredApprovers` 채움(기존 `PresetApprovalMatrix`/정책 재사용) → `REVIEWING`
  - 질문: `request.askClarification(...)` → `REVIEWING`
  - 실패/타임아웃/파싱오류: `ANALYSIS_FAILED`
- 각 분기에서 `RequestStreamService.publishTreeReplaced`/`publishNodeUpdated` 호출.
- 트랜잭션: 분석 자체는 트랜잭션 밖(긴 외부 호출), **결과 반영만 짧은 @Transactional**로.

### 5-3. 비동기 인프라
- `@EnableAsync` + 전용 `ThreadPoolTaskExecutor`(이름 `ai-analyzer`, 코어/최대/큐 설정).
- 호출부(`RequestService.create`, `retryAnalysis`, `patchPlan`)는 **커밋 후** 분석을 띄움
  (`TransactionSynchronization afterCommit` 또는 이벤트). 저장 전 분석 방지.

---

## 6. 프롬프트 엔지니어링 설계

### 6-1. System 프롬프트 (요지)
- 역할: "사내 작업 요청을 **실행 가능한 Task 목록**으로 분해하는 분석기".
- 제약:
  - 출력은 **지정 JSON 스키마만**(설명/마크다운 금지). JSON 외 토큰 금지.
  - Task.type은 enum(`TaskType`)만. content는 type에 맞는 필드 하나만(diff/command/query/body).
  - 애매하면 Task를 추측하지 말고 `clarification`(질문)으로 돌려줄 것.
  - 입력으로 받은 **금지 규칙에 해당하면 그 Task를 생성하지 말거나 위험 사유를 명시**.
- few-shot 예시 1~2개(간단한 프롬프트 → JSON) 포함.

### 6-2. User 프롬프트 (주입 변수)
- 요청 프롬프트(`request.prompt`)
- 프로젝트 컨텍스트(이름/저장소/기준 커밋/기획서 요약)
- 부서 금지 규칙 목록(`forbidden_rules`에서 조회)
- 승인 기준표(작업유형·위험도별) — 참고용
- 재분석 시: 이전 Plan 요약 + 요청자 `answer`/`UPDATE instruction`

### 6-3. 출력 스키마 (엄격 JSON)
```json
{
  "clarification": null,
  "tasks": [
    {
      "type": "CODE|DEPLOY|DB|DOCUMENT|...(TaskType)",
      "title": "요약",
      "target": "대상(파일/서비스/테이블/문서)",
      "risk": "LOW|MEDIUM|HIGH (FORBIDDEN은 서버가 금지규칙으로 판정)",
      "content": { "diff": "...", "command": null, "query": null, "body": null },
      "relatedDepartments": []
    }
  ]
}
```
- 파싱 실패/스키마 위반 시 1회 재프롬프트("JSON만, 스키마 위반") 후에도 실패면 `ANALYSIS_FAILED`.
- Claude는 Messages API(`anthropic_version`, `messages`, `max_tokens`) 형식으로 호출.
  `system`에 스키마·규칙, `messages`에 user 컨텍스트.
- **구조화 출력 강제(권장)**: Bedrock의 **Structured Outputs**(사전 JSON 스키마로 엄격 강제)를
  Converse/Tool Use API로 사용한다. Sonnet 5.5에서 지원되며, 이걸 쓰면 위 "재프롬프트" 폴백
  필요성이 크게 줄고 파싱 실패율이 낮아진다. 미사용 환경에서는 tool use(JSON schema)로 대체.

### 6-4. 위험도 분류
- 1차: LLM이 Task별 `risk` 제안.
- 2차(서버 확정): 금지 규칙 매칭 → `FORBIDDEN` 강제. 승인 기준표와 교차검증.
- `EditorService`의 하드코딩 `UNCLASSIFIED_RISK=MEDIUM`도 분석기 호출로 대체(옵션).

---

## 7. 변경/신규 파일 요약

| 파일 | 변경 |
|------|------|
| `build.gradle` | AWS SDK Bedrock Runtime 의존성 추가 |
| `application.properties` | `bedrock.*` 설정 추가 |
| `deploy/.env.example`, `deploy/README.md` | `BEDROCK_*` 키 + IAM Role 안내 |
| `ai/` 패키지 (위 5장) | 신규: Config, Analyzer(2), PromptBuilder, DTO, AnalysisService |
| `RequestService.create` | 커밋 후 `RequestAnalysisService.analyze` 호출 추가 |
| `RequestPlanService.retryAnalysis` | 상태 변경 후 분석 재호출 |
| `RequestPlanService.patchPlan` | answer/UPDATE instruction을 분석 입력으로 전달 |
| `EditorService`(선택) | 위험도 하드코딩 → 분석기 분류로 대체 |
| `BeforeAiBeApplication` | `@EnableAsync` (또는 별도 Async config) |

---

## 8. 테스트 전략

- **단위**: `BedrockAiAnalyzer` — Bedrock 클라이언트를 모킹(`BedrockRuntimeAsyncClient` mock), 정상 JSON/깨진 JSON/질문/타임아웃 분기.
- **단위**: `PromptBuilder` — 주입 변수가 템플릿에 올바르게 들어가는지(스냅샷).
- **통합**: `RequestAnalysisService` — `AiAnalyzer`를 **스텁**으로 주입(실제 Bedrock 호출 없이) → 상태 전이(REVIEWING/ANALYSIS_FAILED/clarification)와 Plan/Task 저장, SSE publish 검증. 기존 Testcontainers 그대로.
- **회귀**: `bedrock.enabled=false`(기본)에서 기존 `RequestApiTest` 전부 통과(동작 불변) 확인.
- **CI 영향 없음**: 실제 Bedrock 호출은 테스트에서 하지 않음(모킹/스텁). AWS 자격증명 불필요.

---

## 9. 구현 순서 (PR 단위)

1. **경계부터**: `AiAnalyzer` 인터페이스 + `NoopAiAnalyzer` + DTO + `RequestAnalysisService`(스텁 분석) + 비동기 인프라 + 상태 전이/SSE + 통합 테스트.
   → 이 단계까지는 Bedrock 없이도 "분석기 자리"가 완성되고 테스트로 검증됨.
2. **Bedrock 구현**: 의존성 + `BedrockConfig` + `BedrockAiAnalyzer` + 프롬프트 템플릿 + 파싱 + 단위 테스트.
3. **프롬프트 튜닝**: few-shot/스키마 강제(tool use)로 파싱 실패율 낮추기.
4. **위험도·금지규칙 교차검증** 고도화, `EditorService` 연동(선택).
5. **운영**: `.env`/IAM Role/README, 비용·토큰·타임아웃 모니터링.

---

## 10. 리스크 / 결정 필요

- **모델·inference profile**: Claude Sonnet 5.5 사용. 리전은 서버(EC2)에 설정됨.
  단 최신 Claude는 **cross-Region inference profile ID로만 호출** 가능하므로,
  배포 리전 Bedrock 콘솔의 "Inference profiles"에서 정확한 ID를 복사해 `BEDROCK_MODEL_ID`에 넣어야 한다.
  (Sonnet 4.5 는 2026-11-30 retire 예정이라 선택지에서 제외)
- **비용**: 요청당 토큰. max-tokens·프롬프트 길이 상한, 재분석 3회 제한(이미 있음)으로 방어.
- **구조화 출력 신뢰성**: tool use/JSON mode 강제 권장. 실패 시 재시도 1회 → ANALYSIS_FAILED.
- **보안**: 프롬프트/프로젝트 내용이 Bedrock으로 나감 — 사내 정책상 허용 여부 확인(데이터 거버넌스). IAM 최소권한(`bedrock:InvokeModel`만).
- **비동기 정합성**: 분석 중 요청 취소/중복 분석 방지(상태 가드 + 요청 행 잠금은 기존 `findByIdForUpdate` 활용).
