# dev 머지 컨플릭트 · bootJar 빌드 복구 보고서

작성일: 2026-11-05
브랜치: `feature/section-3.6` (머지 커밋 `b31b3e2` — "Merge branch 'dev' into feature/section-3.6")

## 요약

`dev` 병합 과정에서 `WebshellClient.java`가 잘못 합쳐져 **컴파일이 깨지고 `bootJar`가 실패**했다.
누락된 코드를 복원해 **컴파일·`bootJar` 빌드는 성공**시켰다.
빌드 복구 뒤 드러난 **`ApprovalApiTest`의 403 실패 6건도 수정**했다. 승인 권한 검사와 요청 열람 권한을 분리하고, 승인 저장·웹쉘 실행의 실제 트랜잭션 경계를 바로잡았다.
최종 검증은 **전체 테스트 220개 통과, `bootJar` 성공**이며, DB 스키마 검증(`ddl-auto=validate`)도 통과했다.

## 1. 증상

```
> Task :compileJava FAILED
.../dto/editor/FileContentResponse.java:8: error: cannot find symbol  class FileContentResult
.../dto/editor/FileListResponse.java:10: error: cannot find symbol  class FileListResult
```

이후 승인 쪽도 연쇄로 드러남:

```
.../service/ApprovalTransactionService.java: cannot find symbol  class ApprovalPayload
.../service/ApprovalTransactionService.java: cannot find symbol  class ExecutionResult
.../service/ApprovalTransactionService.java: cannot find symbol  class ExecutionRequest
```

## 2. 원인

머지 커밋 `b31b3e2`에서 `WebshellClient.java`가 한쪽 버전으로 덮이면서,
**양쪽 브랜치가 각각 추가했던 중첩 타입·메서드가 통째로 빠졌다.**

- 3.8(editor, 커밋 `b042df4`)이 추가한 것: `listFiles`, `readFile`, `currentCommit`, `fileUri` + 레코드 `FileListResult`, `FileItem`, `FileContentResult`
- 3.6(승인, 커밋 `7b386c9`)이 추가한 것: `submitExecution` + 레코드 `ExecutionRequest`, `ApprovalPayload`(중첩 `ApprovalEnvelope`), `ExecutionResult`

머지 결과 파일에는 3.6의 `kill`만 남고 나머지가 모두 사라져 있었다.

## 3. 조치 (완료)

`WebshellClient.java`에 양쪽 누락분을 모두 복원하고 import(`List`, `Map`, `URI`, `UriBuilder`)를 정리했다.
복원 기준은 각 브랜치의 머지 직전 커밋 원본(`b042df4`, `7b386c9`)이다.

검증:

```
./gradlew bootJar   →  BUILD SUCCESSFUL
```

## 4. 승인 테스트 실패 원인과 수정 (완료)

### 재현 결과와 기존 보고서 정정

수정 전 승인 API·요청 API·웹쉘 클라이언트 관련 테스트 60개 중 6개가 실패했다. 모두 `ApprovalApiTest`의 `FORBIDDEN_ROLE` 응답이었다.

| 테스트 | 기존 실제 응답 | 수정 후 기대 응답 |
|---|---|---|
| `해시가_다르면_409_PLAN_HASH_MISMATCH` | 403 FORBIDDEN_ROLE | 409 PLAN_HASH_MISMATCH |
| `웹쉘_연결_실패면_서명은_보관되고_실행은_요청되지_않는다` | 403 FORBIDDEN_ROLE | 200, executionRequested=false |
| `승인권자가_아니면_403_NOT_APPROVER` | 403 FORBIDDEN_ROLE | 403 NOT_APPROVER |
| `전량_승인되면_웹쉘에_실행을_요청하고_RUNNING이_된다` | 403 FORBIDDEN_ROLE | 200, RUNNING |
| `등록되지_않은_키면_403_KEY_NOT_REGISTERED` | 403 FORBIDDEN_ROLE | 403 KEY_NOT_REGISTERED |
| `승인_대기_상태가_아니면_409_INVALID_STATE` | 403 FORBIDDEN_ROLE | 409 INVALID_STATE |

기존 보고서가 여섯 번째 실패로 적었던 `승인_대기_상태가_아니면_거부할_수_없다`는 실제로 통과했다. 실패한 것은 REVIEWING 상태의 ANYONE 작업에 대한 **승인** 테스트였다. 명시된 후보 UUID는 DB 재조회 후에도 유지됨을 검증했다.

### 권한 검사

`saveApproval`·`reject`가 `getViewableRequestForUpdate`를 호출하면서 일반 요청 열람 권한을 먼저 검사했다. 열람 규칙은 명시된 후보만 인정하지만, 승인 규칙은 `ANYONE`도 허용하여 정상 승인자가 작업별 검증에 도달하지 못했다.

- `ApprovalTransactionService`에서 요청 행을 직접 잠금 조회하고, `resolveApprovableTargets`로 모든 대상 작업의 소속·상태·승인권을 검사한다.
- 일반 요청 상세·목록·SSE 등의 열람 권한은 기존 규칙을 유지한다. `ANYONE`을 일반 열람 권한으로 확대하지 않았다.
- 비승인자는 승인·거부 모두 `NOT_APPROVER`로 차단한다. 여러 작업 중 하나라도 권한이 없으면 승인 기록·작업·요청 상태가 바뀌지 않는다.
- 앞선 수정 시도에서 남은 `RequestService`의 미사용 `ApprovalPolicy` 주입 필드를 제거했다.

### 트랜잭션 경계

`ApprovalService`의 클래스 수준 `@Transactional(readOnly = true)`가 승인·거부까지 감싸고 있었다. 이 때문에 `saveApproval`의 쓰기 트랜잭션이 읽기 전용 외부 트랜잭션에 참여하고, 웹쉘 실행 단계 전에 승인이 커밋되지 않았다.

- 읽기 전용 트랜잭션은 `pending`·`approvalContext` 조회 메서드에만 적용한다.
- 승인 퍼사드는 트랜잭션 없이 `saveApproval`을 먼저 커밋한 다음 별도 실행 트랜잭션을 호출한다.
- 실행 직전 다시 얻은 행 잠금 안에서 승인한 Plan ID·요청 상태·실행 대상 전체 승인 여부를 재검사한다. 사이에 취소되거나 Plan이 바뀌면 웹쉘 호출 전에 `INVALID_STATE`로 차단한다.
- 동일 Plan이 이미 RUNNING이면 실행을 중복 요청하지 않는다.

## 5. 회귀 테스트

- `ApprovalApiTest`: 테스트 클래스의 외부 트랜잭션을 제거했다. 준비 데이터를 실제 커밋한 뒤 API를 호출하고 새로운 조회로 저장 결과를 검증한다. 테스트 데이터는 해당 프로젝트·사용자·부서 범위만 정리한다.
- 전량 승인 성공 시 저장된 승인 봉투·작업 상태·실행 ID·Plan 서명과 웹쉘로 보낸 원문·봉투를 검증한다.
- 웹쉘 연결 실패 시 승인 봉투·작업 승인이 커밋되어 남고 실행 기록·웹쉘 서명은 생기지 않는지 검증한다.
- ANYONE 거부 성공, 비승인자 거부 차단, 권한 없는 작업이 섞인 승인·거부 전체 차단 테스트 4개를 추가했다.
- `ApprovalTransactionServiceTest`: 승인 이후 취소·Plan 교체·미승인 작업 차단과 동일 Plan 중복 실행 방지 테스트 4개를 추가했다. 두 번째 잠금에서 변경된 상태를 읽는 상황을 단위 테스트로 재현한다.
- `WebshellClientTest`: 복원된 목록·내용·현재 커밋 조회와 실행 실패의 오류 변환을 로컬 HTTP 서버로 검증하는 테스트 4개를 추가했다.

## 6. 검증 결과

```bash
./gradlew test --tests '*ApprovalApiTest' --tests '*RequestApiTest' --tests '*WebshellClientTest' --no-daemon
# 68개 통과, 실패·오류·건너뜀 0

SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew test bootJar --no-daemon
# BUILD SUCCESSFUL — 34개 테스트 클래스, 220개 통과, 실패·오류·건너뜀 0

git diff --check
# 통과
```

생성 산출물: `build/libs/BeforeAI-BE-0.0.1-SNAPSHOT.jar`.
PostgreSQL Testcontainers를 사용했으며, 웹쉘은 API 테스트의 mock 및 클라이언트 테스트의 로컬 HTTP 서버로 검증했다. 실제 웹쉘·mTLS 연동 검증은 포함하지 않는다.
