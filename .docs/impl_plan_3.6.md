# 구현 계획서 — 3.6 승인

> 대상: Postman 컬렉션 `BeforeAI API` **3.6 승인** 그룹 (4개 엔드포인트)
> 작업 브랜치: `feature/section-3.6` (`dev`에서 분기, `origin/dev` 병합 완료)
> 기준 문서: `.docs/BeforeAI.postman_collection.v2.json` 3.6, `AGENT.md`, 기능 명세서 CORE-02·CORE-04·CORE-05
> 선행 구현: 3.1(인증·내 정보·승인 키), 3.2(관리자), 3.5(작업 요청·Plan·실행)이 모두 `dev`에 병합되어 들어와 있음

---

## 0. 전제와 범위

### 0.1 이미 존재하는 것 (재사용)
3.5 작업 요청·Plan·실행이 병합되면서 승인 구현에 필요한 토대가 거의 다 들어와 있다. **새로 만들지 말고 재사용**한다.

| 구분 | 이미 있음 | 위치 |
|---|---|---|
| 엔티티 | `Approval`(승인 기록), `Request`, `Plan`, `Task`, `Execution`, `SigningKey` | `entity/` |
| 값 객체 | `ApprovalEnvelope(alg, encapsulatedKey, ciphertext)`, `RequiredApprover(rule, count, candidates)`, `TaskContent` | `entity/value/` |
| enum | `RequestStatus`, `TaskStatus`(PENDING/APPROVED/REJECTED/BLOCKED/RUNNING/…), `TaskType`, `Risk`, `ApproverRule`(ANYONE/SAME_DEPT_OTHER/PROJECT_OWNER/DEPT_MANAGER/ADMIN), `Network`(INTERNAL/EXTERNAL), `ExecutionStatus`, `SigningKeyStatus`(PENDING/REGISTERED/REVOKED) | `entity/enums/` |
| 서비스 | `RequestService`(조회·권한 검사), `PlanHashService`(해시 계산·hashTarget), `RequestExecutionService`(킬·로그), `RequestStreamService`(SSE 발행) | `service/` |
| 리포지토리 | `RequestRepository`, `PlanRepository`(`findWithTasksById`), `ApprovalRepository`, `SigningKeyRepository`, `UserRepository`, `ExecutionRepository` | `repository/` |
| 웹쉘 포트 | `WebshellClient`(`kill()`만 구현, mTLS·SSL 번들·502 처리 방식 확립) | `webshell/` |
| 보안 | `AuthUser(id, role, passwordChangeRequired)`, `NetworkResolver.resolve()`, `@InternalNetworkOnly`, `GlobalExceptionHandler` | `security/`, `exception/` |
| 오류 코드 | `PLAN_HASH_MISMATCH`, `NOT_APPROVER`, `KEY_NOT_REGISTERED`, `INTERNAL_NETWORK_ONLY`, `INVALID_STATE`, `NOT_FOUND`, `VALIDATION_ERROR`, `WEBSHELL_UNAVAILABLE`, `FORBIDDEN_ROLE` | `exception/ErrorCode` |
| 공통 DTO | `CursorPageResponse<T>(items, nextCursor)`, `ErrorResponse` | `dto/common/` |
| 요청 DTO | `TaskResponse`, `PlanResponse`, `RequesterResponse`, `ApprovalResponse`(상세의 승인 현황용) | `dto/request/` |

→ **3.6 전용으로 새로 만들 것**은 다음만 남는다:
> - `ApprovalController` (4개 엔드포인트) + `ApprovalService`
> - `dto/approval/*` 응답/요청 record
> - `WebshellClient.submitExecution()` (POST /v1/executions) — 승인 성공 시 웹쉘 실행 요청
> - 리포지토리 신규 쿼리: `ApprovalRepository`에 승인 집계/조회, `RequestRepository`에 "내가 승인 후보인 PENDING 작업" 조회(또는 Task 기준 신규 조회 메서드)
> - (신규 테이블·마이그레이션 **없음** — `approvals`/`executions` 테이블은 3.5 때 이미 생성됨)

### 0.2 계약 상태
컬렉션의 3.6 응답 예시는 `GET /approvals/pending`·`POST /rejections`가 "스키마·상태코드 예시 / 계약 미정", `approval-context`·`POST /approvals`는 "명세 기반"이다. 예시 스키마를 잠정 계약으로 채택하고, 컨트롤러↔서비스↔엔티티를 분리해 추후 DTO만 교체 가능하게 둔다.

### 0.3 이번 브랜치의 제외
- 실제 HPKE 복호화·Ed25519 서명 검증은 **웹쉘(5장)** 몫이다. 웹 서비스는 `ApprovalEnvelope`를 **열어 보지 않고 보관·전달만** 한다(AGENT.md 보안 규칙). `planHash`·`taskIds`·`keyId`는 화면 표시·사전 검사용이며 실행 판단 근거로 쓰지 않는다.
- 웹쉘 실제 실행 로직(작업 수행, 콜백 이벤트 처리)은 3.5/5장 범위. 본 브랜치는 `POST /v1/executions` **호출과 응답 매핑**까지만.

---

## 1. 엔드포인트 목록과 매핑

| # | Method & Path | 권한 | 망 | 기능ID | 성공 코드 |
|---|---------------|------|----|--------|----------|
| 1 | `GET /approvals/pending` | 로그인(승인 후보) | 전체 | CORE-04 | 200 |
| 2 | `GET /requests/{requestId}/approval-context` | 요청 조회권자 | 전체 | CORE-04 | 200 |
| 3 | `POST /requests/{requestId}/approvals` | 승인권자 | **내부** | CORE-05 | 200 |
| 4 | `POST /requests/{requestId}/rejections` | 승인권자 | **내부** | CORE-05 | 200 |

- #3, #4는 망 열이 "내부" → 컨트롤러 메서드에 `@InternalNetworkOnly`.
- #2(`approval-context`)는 **전체 망에서 조회 가능**하되, 응답 안에서 외부망이면 배포(내부망 전용) 작업을 `blockedTaskIds`로 돌려준다(차단 사유 `INTERNAL_NETWORK_ONLY`). 즉 조회 자체는 막지 않고 **승인 가능/차단 작업을 구분**해 내려 준다.
- #1(`pending`)은 `/approvals/pending`으로 `/requests` prefix 밖이다 → **별도 컨트롤러** 또는 같은 컨트롤러에 `@RequestMapping` 없이 절대경로. 3.5의 `RequestController`가 `/api/v1/requests`에 매핑돼 있으므로, 3.6은 **신규 `ApprovalController(@RequestMapping("/api/v1"))`** 로 두어 네 경로를 모두 담는다.

---

## 2. 엔드포인트별 동작 설계

공통 base 경로: `/api/v1`. 로그인 사용자는 `@AuthenticationPrincipal AuthUser authUser`로, 접속 환경은 `NetworkResolver.resolve(HttpServletRequest)`로 판별해 서비스에 `Network`를 인자로 넘긴다(기존 `Approval.network` 채우기에 필요).

### #1 `GET /approvals/pending` — 내 승인 대기 작업 목록
- **대상**: 로그인 사용자가 **승인 후보**인, 상태가 `PENDING`인 Task들. 각 Task를 요청/Plan/요청자 정보와 함께 평탄화해 돌려준다(응답 단위는 요청이 아니라 **Task**).
- **후보 판정**: Task.requiredApprovers 중 `rule == ANYONE` 이거나 `candidates`에 사용자 id가 들어 있음. (3.5 `RequestPlanService.selfApprovableTaskIds`, `RequestService.isCandidate`와 동일 규칙 — 공통 판정 메서드로 추출 검토)
- **조회 범위 제한**: 요청 상태가 승인을 받을 수 있는 단계(`PENDING_APPROVAL`, `REAPPROVAL_REQUIRED`)인 요청의 **현재 Plan** 작업만. 지난 Plan·취소·실행 중 요청은 제외.
- **응답**(컬렉션 예시):
  ```json
  { "items": [ {
      "requestId", "planId",
      "requester": { "id", "name", "departmentId" },
      "requestedAt",
      "task": { "id", "order", "type", "risk", "title", "target",
                "content", "relatedDepartments", "requiredApprovers", "status" }
  } ], "nextCursor": null }
  ```
  - `task`는 기존 `TaskResponse`를 그대로 재사용(필드 동일). `requester`는 `RequesterResponse` 재사용. `requestedAt`은 요청 `createdAt`.
  - 페이지네이션은 공통 커서 규칙(`?cursor=&limit=`, 기본 20·최대 100). 1차는 `nextCursor=null`로 단순 반환 후, Task 커서(요청 createdAt, requestId, task order/id) 정렬로 확장.
- **구현 메모**: candidates 매칭은 3.5 `RequestRepository.findIdsByCurrentPlanCandidate`(jsonb `@>` 네이티브 쿼리)와 같은 패턴. 다만 여기선 "ANYONE 포함" + "status=PENDING" + "요청 상태 필터"가 더 붙으므로, **후보 요청 id 집합을 좁게 조회 → 현재 Plan 로드 → 애플리케이션에서 Task 필터**가 가장 단순하고 안전하다(jsonb 쿼리 복잡도 회피). 성능이 문제되면 쿼리로 내린다.

### #2 `GET /requests/{requestId}/approval-context` — 승인 전 원문·해시 대상 조회
- **권한**: `RequestService.getViewableRequest(requestId, userId)` 재사용(조회권 검사 포함).
- **동작**: 현재 Plan을 로드해, 승인자가 서명 전에 확인할 **원문(plan)** + **해시 대상(hashTarget)** + **표시용 planHash** + **승인 가능/차단 작업 구분**을 돌려준다.
- **hashTarget / planHash**: `PlanHashService.hashTarget(request, plan)`·`hash(...)` 재사용. hashTarget은 `{requestId, planId, projectId, repository, baseCommit, tasks[{order,id,type,risk,target,content}]}` 형태로 이미 이 서비스가 만든다. **승인자 기기·웹쉘과 같은 규칙**이어야 하므로 반드시 이 서비스를 통해 만든다.
- **승인 가능 판정**(`approvableTaskIds` / `blockedTaskIds` / `blockedReason` / `network`):
  - 기본 후보: 로그인 사용자가 승인 후보이고 상태가 `PENDING`인 Task.
  - **외부망 차단**: 접속 환경이 `EXTERNAL`이면, **내부망 전용으로 간주되는 작업**(아래 2.5 "작업별 망 요구")은 `blockedTaskIds`로 옮기고 `blockedReason = "INTERNAL_NETWORK_ONLY"`로 둔다. 내부망이면 `blockedReason = null`, `blockedTaskIds = []`.
- **응답**(컬렉션 "명세 기반" 예시):
  ```json
  { "requestId", "plan": { PlanResponse },
    "hashTarget": { requestId, planId, projectId, repository, baseCommit, tasks[...] },
    "planHash", "approvableTaskIds": [...], "blockedTaskIds": [...],
    "blockedReason": null|"INTERNAL_NETWORK_ONLY", "network": "INTERNAL"|"EXTERNAL" }
  ```
  - `plan`은 `PlanResponse.from(plan)` 재사용.
  - `hashTarget`은 `PlanHashService.hashTarget()`이 돌려주는 `Map<String,Object>`를 그대로 직렬화(키 이름이 이미 계약과 일치). DTO 필드 타입은 `Map<String,Object>` 또는 전용 record.

### #3 `POST /requests/{requestId}/approvals` — 암호화한 승인 서명 제출
- **망**: `@InternalNetworkOnly` (외부망 → 403 INTERNAL_NETWORK_ONLY).
- **요청 본문**:
  ```json
  { "planId", "planHash", "taskIds": [...], "keyId",
    "envelope": { "alg", "encapsulatedKey", "ciphertext" } }
  ```
  검증: `@NotNull planId/keyId`, `@NotEmpty taskIds`, `@NotBlank planHash`, `envelope` 세 필드 `@NotBlank`.
- **처리 순서**(요청 행 잠금 `getRequesterRequestForUpdate`가 아니라 **조회권 + 승인권 별도 검사** — 요청자가 아닌 승인자가 호출하므로):
  1. 요청 조회·잠금: `RequestService.getViewableRequestForUpdate(requestId, userId)` (MANDATORY 트랜잭션, 행 잠금).
  2. **상태 검사**: 요청 상태가 `PENDING_APPROVAL`/`REAPPROVAL_REQUIRED`가 아니면 `INVALID_STATE`(409).
  3. **planId 일치**: `body.planId == request.currentPlanId` 아니면 `INVALID_STATE`(409, "현재 Plan이 아닙니다").
  4. **planHash 일치**: `PlanHashService.hash(request, plan)` 재계산값과 `body.planHash`가 다르면 `PLAN_HASH_MISMATCH`(409). (웹 서비스 표시용 해시로 사전 검사만 — 실제 판단은 웹쉘)
  5. **승인권 검사**: `body.taskIds`의 모든 Task가 (a)현재 Plan 소속이고 (b)PENDING이며 (c)로그인 사용자가 승인 후보가 아니면 `NOT_APPROVER`(403).
  6. **키 등록 검사**: `SigningKeyRepository`로 `keyId`가 **해당 사용자 소유 + status=REGISTERED**인지 확인. 아니면 `KEY_NOT_REGISTERED`(403). (웹쉘 정책 등록 여부를 웹 서비스가 직접 묻는 API는 없으므로 `SigningKey.status == REGISTERED`로 대리 판정 — 3.2 ADM-09에서 register 시 REGISTERED가 됨)
  7. **Approval 저장**: `Approval.builder()`로 `request/plan/approverId/keyId/planHash/taskIds/network/envelope` 저장. envelope은 열지 않고 그대로 보관.
  8. **승인 집계 → 작업 상태 전이**: 각 Task의 `requiredApprovers`(rule·count)를 **충족**하면 Task를 `APPROVED`로. (1차 구현: `candidates`/`ANYONE` 매칭 + 같은 Plan의 승인 레코드 수가 count 이상이면 충족. count는 대개 1.) 충족 못 한 Task는 `PENDING` 유지.
  9. **전량 승인 시 실행 요청**: 현재 Plan의 **금지 작업 제외 전체 Task가 APPROVED**가 되면 웹쉘 `POST /v1/executions`(`WebshellClient.submitExecution(plan 원문, approvals[])`) 호출.
     - 웹쉘 202 응답(`executionId, planHash, status, webshellSignature`) → `Execution` 저장(status=RUNNING), `Plan.executorSignature` 보관, `request` 상태 `RUNNING`으로, `executionRequested = true`.
     - 웹쉘 연결 실패(502 `WEBSHELL_UNAVAILABLE`) → **서명은 이미 보관**했으므로 **트랜잭션을 롤백하지 않고** `executionRequested = false`로 200 반환(컬렉션 "웹쉘 연결 실패·서명 보관 후 재시도" 예시). → **웹쉘 호출을 트랜잭션 커밋 이후/예외 격리**로 처리해야 함(2.6 참조).
- **응답**:
  ```json
  { "requestId", "status": "PENDING_APPROVAL",
    "approvedTaskIds": [...], "remainingTaskIds": [...], "executionRequested": true|false }
  ```
  - `status`는 전이 결과(전량 승인+실행요청 성공이면 `RUNNING`일 수도 있으나, 컬렉션 예시는 `PENDING_APPROVAL` 유지 → **응답의 status는 "이번 제출 처리 후 요청 상태"**. 실행 요청 성공 시 RUNNING, 실패 시 PENDING_APPROVAL. 예시는 실패/부분 케이스 기준이므로 실제 전이값을 그대로 반영).
  - `approvedTaskIds`: 이번 제출로 APPROVED가 된(또는 이미 APPROVED인 포함) 작업. `remainingTaskIds`: 아직 PENDING인 작업.
- **오류**: `PLAN_HASH_MISMATCH`(409), `NOT_APPROVER`(403), `KEY_NOT_REGISTERED`(403), `INTERNAL_NETWORK_ONLY`(403, 애너테이션), `INVALID_STATE`(409), `WEBSHELL_UNAVAILABLE`는 **던지지 않고** executionRequested=false로 흡수.

### #4 `POST /requests/{requestId}/rejections` — 작업 거부·문서 미적용
- **망**: `@InternalNetworkOnly`.
- **요청 본문**: `{ "planId", "taskIds": [...], "reason" }` — `@NotNull planId`, `@NotEmpty taskIds`, `reason` 선택(길이 제한 검증).
- **처리**:
  1. 요청 조회·잠금 + 상태 검사(`PENDING_APPROVAL`/`REAPPROVAL_REQUIRED` 아니면 `INVALID_STATE`).
  2. planId 일치 검사.
  3. 승인권 검사(거부도 승인권자만 — `NOT_APPROVER`).
  4. 대상 Task를 `REJECTED`로. 거부가 하나라도 생기면 **요청 상태를 `REJECTED`로** 전이(컬렉션 예시 `status: "REJECTED"`). reason은 감사/표시용(저장 위치는 2.4 결정).
  5. SSE 트리 갱신(`RequestStreamService.publishTreeReplaced`).
- **응답**: `{ "requestId", "status": "REJECTED" }`.
- **오류**: `INVALID_STATE`(409).

### 2.5 작업별 망 요구 (외부망 차단 판정) — **신규 규칙**
컬렉션 예시는 `DEPLOY` 작업을 외부망에서 `blockedReason=INTERNAL_NETWORK_ONLY`로 막는다. 현재 코드에는 TaskType/Risk → 망 요구 매핑이 **없다**. 본 브랜치에서 도입한다.
- 1차 규칙(기능 명세서 기준, 보수적으로): **`TaskType.DEPLOY` 이거나 `Risk.HIGH` 이상 작업은 내부망 승인만 허용**. 그 외(CODE_FEATURE 등 저위험)는 외부망 승인 허용.
- 구현: `ApprovalPolicy`(또는 서비스 내 private 메서드) `boolean internalOnly(Task)`. 정확한 규칙은 **Open Question(10.1)** — 기획 확정 전까지 DEPLOY 기준으로 두고 한 곳에 모아 교체 가능하게 둔다.
- `approval-context`(#2)의 blocked 판정과 `POST /approvals`(#3)의 `@InternalNetworkOnly`는 **다른 층위**다: #3은 "내부망 전용 엔드포인트"라 외부망이면 **엔드포인트 자체가 403**이고, #2는 "조회는 허용하되 외부망에서 서명 불가한 작업을 미리 알려 줌"이다.

### 2.6 웹쉘 호출의 트랜잭션 경계 — 중요
`POST /approvals`에서 **Approval 저장(DB)** 은 커밋되어야 하고, **웹쉘 실행 요청 실패는 롤백을 유발하면 안 된다**(서명 보관 후 재시도 시나리오). 3.5의 `kill()`은 반대로 "웹쉘 성공 후에만 상태 변경"이라 같은 패턴을 쓸 수 없다.
- 설계: ① 승인 저장 + 작업 상태 전이까지를 한 트랜잭션으로 커밋 → ② 전량 승인 시 **별도 단계**에서 웹쉘 호출, 성공 시 Execution 저장·상태 RUNNING(두 번째 트랜잭션), 실패(`WEBSHELL_UNAVAILABLE`)는 **잡아서** executionRequested=false로 응답.
- 구현 선택지: (a) 서비스 메서드를 둘로 �나누고 퍼사드에서 순차 호출, (b) `WebshellClient.submitExecution`를 try/catch로 감싸 `ApiException(WEBSHELL_UNAVAILABLE)`를 흡수. 단, @Transactional 자기호출 주의 — 웹쉘 호출 트랜잭션은 커밋된 승인과 분리.

---

## 3. 레이어별 작업 항목

### 3.1 패키지 구조 (신규)
```
site.siyoung.beforeaibe
├─ controller
│   └─ ApprovalController            @RequestMapping("/api/v1")  — #1~#4
├─ service
│   └─ ApprovalService               — 승인/거부/대기목록/컨텍스트 + 웹쉘 실행 트리거
├─ dto/approval
│   ├─ PendingApprovalResponse        (#1 item: requestId, planId, requester, requestedAt, task)
│   ├─ ApprovalContextResponse        (#2)
│   ├─ ApprovalSubmitRequest          (#3 body: planId, planHash, taskIds, keyId, envelope)
│   ├─ EnvelopeRequest                (#3 body.envelope — 또는 기존 ApprovalEnvelope record 재사용 검토)
│   ├─ ApprovalSubmitResponse         (#3: requestId, status, approvedTaskIds, remainingTaskIds, executionRequested)
│   ├─ RejectionRequest               (#4 body: planId, taskIds, reason)
│   └─ RejectionResponse              (#4: requestId, status)
└─ webshell
    └─ WebshellClient.submitExecution(...)  — POST /v1/executions, ExecutionResult 반환
```
> `dto/request`의 `TaskResponse`·`PlanResponse`·`RequesterResponse`는 **그대로 재사용**(import). 3.6 전용 패키지(`dto/approval`)에서 참조만 한다.

### 3.2 리포지토리 추가
- `ApprovalRepository`
  - 기존: `findByPlan_IdOrderByCreatedAtAscIdAsc(UUID planId)` — 승인 집계에 재사용(같은 Plan 승인 수 세기).
  - 신규(선택): 특정 Task에 대한 승인 수가 필요하면 애플리케이션에서 `taskIds.contains(taskId)`로 집계(간단) 또는 jsonb 쿼리.
- `RequestRepository`
  - 기존 `findIdsByCurrentPlanCandidate(userId)` 재사용 가능하나, #1은 "ANYONE 포함 + PENDING + 요청 상태 필터"가 더 필요. 1차는 후보 요청 id로 좁힌 뒤 애플리케이션 필터. 필요 시 상태 필터 쿼리 추가.
- `SigningKeyRepository`
  - 신규: `Optional<SigningKey> findByIdAndUserId(UUID id, UUID userId)` 또는 `boolean existsByIdAndUserIdAndStatus(UUID, UUID, SigningKeyStatus)` — 키 등록 검사(#3 step 6).
- `ExecutionRepository` / `PlanRepository` — 기존 저장·조회 재사용.

### 3.3 서비스: `ApprovalService`
- 의존: `RequestService`(조회·권한·잠금·getCurrentPlan), `PlanHashService`, `SigningKeyRepository`, `ApprovalRepository`, `ExecutionRepository`, `WebshellClient`, `RequestStreamService`, `UserRepository`.
- 공통 후보 판정 로직은 3.5의 `selfApprovableTaskIds`/`isCandidate`와 중복되므로 **공통 유틸로 추출**하거나 `ApproverRule`+candidates 매칭 private 메서드로 둔다.
- 메서드:
  - `CursorPageResponse<PendingApprovalResponse> pending(UUID userId, String cursor, int limit)`
  - `ApprovalContextResponse approvalContext(UUID userId, UUID requestId, Network network)`
  - `ApprovalSubmitResponse approve(UUID userId, UUID requestId, Network network, ApprovalSubmitRequest body)` — @Transactional, 웹쉘 호출 경계는 2.6대로.
  - `RejectionResponse reject(UUID userId, UUID requestId, RejectionRequest body)` — @Transactional.

### 3.4 컨트롤러: `ApprovalController`
```java
@RestController @RequestMapping("/api/v1") @RequiredArgsConstructor
class ApprovalController {
  @GetMapping("/approvals/pending")                         // #1
  @GetMapping("/requests/{requestId}/approval-context")     // #2  (network 주입)
  @InternalNetworkOnly @PostMapping("/requests/{requestId}/approvals")   // #3
  @InternalNetworkOnly @PostMapping("/requests/{requestId}/rejections")  // #4
}
```
- 접속 환경: `HttpServletRequest` 또는 기존 패턴 확인 후 `NetworkResolver.resolve()`로 `Network`를 뽑아 서비스에 전달(#2, #3). 3.5 컨트롤러는 망을 서비스에 넘기지 않았으므로(킬은 네트워크 무관) **여기서 NetworkResolver 주입이 새로 필요**.
- `@AuthenticationPrincipal AuthUser authUser`, `@Valid @RequestBody`, 목록은 `@RequestParam cursor/limit(@Min(1) @Max(100))`.

### 3.5 웹쉘: `WebshellClient.submitExecution`
```java
// POST /v1/executions — 승인 서명 검증 후 Plan 실행 (202)
public ExecutionResult submitExecution(ExecutionRequest body) { ... }  // 기존 kill()과 동일한 restClient/502 패턴
public record ExecutionRequest(PlanPayload plan, List<ApprovalPayload> approvals) {}
public record ExecutionResult(UUID executionId, String planHash, String status, String webshellSignature) {}
```
- `plan`은 `PlanHashService.hashTarget()`과 **동일한 원문**을 보내야 웹쉘 재계산 해시가 맞는다 → hashTarget Map을 그대로 body의 plan으로 전송하거나 같은 필드 record로 구성.
- `approvals`는 `[{keyId, envelope{alg,encapsulatedKey,ciphertext}}]` — 현재 Plan에 저장된 Approval들을 매핑.
- `restClient == null`이거나 `RestClientException` → `ApiException(WEBSHELL_UNAVAILABLE)` (kill과 동일). 호출부(#3)에서 잡아 executionRequested=false.

### 3.6 마이그레이션
- **없음.** `approvals`, `executions` 테이블은 3.5 때 생성됨. 새 컬럼·테이블 추가 없음. (`ddl-auto`가 매핑과 어긋나도 몰래 만들므로, 변경이 없다는 사실을 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew test`로 확인)

---

## 4. 상태 전이 정리

### 요청(Request) 상태
- 승인 제출(#3) 전량 승인 + 웹쉘 수락: `PENDING_APPROVAL`/`REAPPROVAL_REQUIRED` → `RUNNING`.
- 승인 제출 부분/웹쉘 실패: 상태 유지(`PENDING_APPROVAL`).
- 거부(#4): → `REJECTED`.
> `Request` 엔티티에 `RUNNING` 전이 도메인 메서드가 없으면 추가 필요(예: `startRunning()`). 현재 `submit()`/`cancel()`/`kill()`만 있음 — **신규 도메인 메서드 추가 대상**.

### 작업(Task) 상태
- 승인 충족: `PENDING` → `APPROVED`.
- 거부: `PENDING`/`APPROVED` → `REJECTED`.
- (실행 중 전이 RUNNING/SUCCEEDED 등은 3.5/5장 콜백 몫)

---

## 5. 테스트 계획

- **서비스 단위/슬라이스**(`@SpringBootTest` + Testcontainers + `@Transactional`, 3.5 `ApiTestSupport.internal()/external()` 재사용):
  - #1 pending: 후보인 PENDING 작업만 나오고, 후보 아님/다른 Plan/취소 요청은 제외.
  - #2 context: 내부망 → blockedReason null; 외부망 + DEPLOY 작업 → blockedTaskIds에 포함, blockedReason=INTERNAL_NETWORK_ONLY; hashTarget·planHash가 `PlanHashService`와 일치.
  - #3 approve 성공: Approval 저장, 작업 APPROVED, 전량 승인 시 웹쉘 호출(Mock)·Execution 저장·요청 RUNNING·executionRequested=true.
  - #3 웹쉘 실패: `WebshellClient` Mock이 `WEBSHELL_UNAVAILABLE` → 승인은 보관되고 executionRequested=false, 상태 PENDING_APPROVAL(롤백 안 됨).
  - #3 오류: planHash 불일치 409, 비승인자 403 NOT_APPROVER, 미등록 키 403 KEY_NOT_REGISTERED, 외부망 403 INTERNAL_NETWORK_ONLY, 잘못된 상태 409 INVALID_STATE.
  - #4 reject: 작업 REJECTED + 요청 REJECTED, 상태 위반 409.
- **컨트롤러 경로/상태코드/JSON 구조**가 컬렉션 예시와 일치하는지 MockMvc로 검증.
- 전체: `./gradlew test` 통과 + `./gradlew build`.

---

## 6. 작업 순서 (커밋 단위)

> **구현 완료 (feature/section-3.6)**. 계획 대비 조정: 트랜잭션 자기호출 문제를 피하려고 서비스를 셋으로 나눔 —
> `ApprovalService`(조회·퍼사드), `ApprovalTransactionService`(서명 보관·웹쉘 실행·거부의 트랜잭션 경계, 계획서 2.6),
> `ApprovalPolicy`(승인권자·내부망 요구·승인 충족 판정을 한곳에 모은 규칙). 신규 마이그레이션 없음.

1. **선행 확인**(완료): `Approval`/`Request`/`Plan`/`Task`/`Execution`/`SigningKey`, `PlanHashService`, `RequestService`, `WebshellClient`, `ErrorCode`, 3.6 컬렉션 계약.
2. `WebshellClient.submitExecution()` + `ExecutionRequest`/`ExecutionResult`/`ApprovalPayload` record (기존 kill 패턴) — 로컬 HTTP 서버 테스트로 검증 완료.
3. 리포지토리 신규: `SigningKeyRepository.existsByIdAndUserIdAndStatus`, `RequestRepository.findPendingApprovalRequestsForApprover`(jsonb).
4. `dto/approval/*` record 작성(기존 `TaskResponse`/`PlanResponse`/`RequesterResponse` 재사용).
5. `Request`에 `startRunning()`·`reject()`, `Plan`에 `assignExecutorSignature()` 추가.
6. `ApprovalPolicy` + `ApprovalTransactionService` + `ApprovalService` + `ApprovalApiTest`.
7. `ApprovalController` 4개 엔드포인트 + `@InternalNetworkOnly`(#3,#4) + NetworkResolver 주입.
8. `SecurityConfig` 확인 — `anyRequest().authenticated()`가 신규 경로를 덮으므로 공개 경로 추가 불필요.
9. 검증: `./gradlew compileJava compileTestJava` 통과, `WebshellClientTest`·`PlanHashServiceTest` 통과.
   `ApprovalApiTest`는 Testcontainers(Docker) 필요 — 현재 환경에 Docker 데몬이 없어 로컬 미실행, CI에서 실행 필요.

---

## 7. 미해결/결정 필요 사항 (Open Questions)

1. **작업별 내부망 요구 규칙(2.5)**: `approval-context`가 외부망에서 무엇을 차단해야 하는지. 1차는 `TaskType.DEPLOY` 또는 고위험(`Risk.HIGH`)을 내부망 전용으로 가정. 기능 명세서 확정 시 `internalOnly(Task)` 한 곳만 교체.
2. **승인 충족 집계 규칙**: `requiredApprovers`의 여러 rule/count 조합을 어떻게 "충족"으로 볼지(동일 승인자 중복 서명 배제, rule별 count 개별 충족 등). 1차는 count=1·단일 rule 가정 + candidates/ANYONE 매칭으로 단순화.
3. **`KEY_NOT_REGISTERED` 판정**: 웹쉘 정책 등록 여부를 묻는 API가 없어 `SigningKey.status==REGISTERED`로 대리 판정. 웹쉘-정책 동기화가 보장되는지 3.2/5장과 확인.
4. **거부 reason 저장 위치**: 별도 테이블/컬럼이 없다. 1차는 감사 로그(3.9) 또는 미저장(응답에만)로 두고, 필요 시 컬럼 추가(신규 마이그레이션 V5).
5. **전량 승인 판정 시 "금지 작업" 처리**: `Risk.FORBIDDEN` 작업은 해시·실행 대상에서 빠지므로(3.5 PlanHashService) 승인 집계에서도 제외하고 "전량 승인" 판정 분모에서 뺀다.
6. **웹쉘 실행 요청 트랜잭션 경계(2.6)**: 승인 보관 커밋 후 웹쉘 호출/실패 흡수를 자기호출 없이 깔끔히 분리하는 구조 확정(퍼사드 vs 이벤트).
7. **#1 pending 페이지네이션 커서 키**: Task 단위 정렬 커서 설계(요청 createdAt + requestId + task order). 1차 nextCursor=null 후 확장.
8. **응답 `status` 의미(#3)**: 컬렉션 예시는 `PENDING_APPROVAL` 고정이나, 실제로는 전이 결과를 반영한다(실행 성공 시 RUNNING). 프론트 계약 확정 시 조정.
