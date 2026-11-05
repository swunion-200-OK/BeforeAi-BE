# 웹쉘 실행 이벤트 콜백 계약

> 대상 API: `POST /api/v1/internal/executions/{executionId}/events` (웹쉘 → 웹 서비스)
> 기준 코드: `InternalExecutionController`, `ExecutionEventService`, `RequestProgressService`(STEP 노드), `SecurityConfig`
> 명세(`.docs/BeforeAI.postman_collection.v2.json` 5장 콜백)에는 이벤트 필드·종류·응답 코드가 정해져 있지 않아 **웹 서비스에서 정한 잠정 계약**입니다. 웹쉘 구현 측과 합의한 뒤 확정합니다.

---

## 1. 연결과 인증

- 웹쉘 서버의 클라이언트 인증서로 mTLS 연결합니다. 웹 서비스는 `SERVER_SSL_ENABLED=true`, `SERVER_SSL_BUNDLE`(서버 인증서 + 웹쉘 CA 신뢰 저장소), `server.ssl.client-auth=want`로 TLS를 직접 종료합니다.
- 인증서 체인은 서버 TLS가 검증하고, 애플리케이션은 인증서 주체의 **CN이 `WEBSHELL_CALLBACK_CLIENT_CN`과 같은지**만 봅니다. 값이 비어 있으면 모든 콜백이 거절됩니다.
- 출발지 IP가 내부망(`INTERNAL_CIDRS`)이어야 합니다.
- 사용자 JWT는 쓰지 않습니다.

## 2. 요청 본문

```json
{
  "eventId": "8a1c…",
  "type": "TASK_SUCCEEDED",
  "executionId": "0e83d7ef-…",
  "requestId": "3cc84fb8-…",
  "taskId": "a7c3b121-…",
  "at": "2026-11-04T03:10:00Z",
  "data": { "label": "diff 적용" },
  "webshellSignature": "base64…"
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `eventId` | ✅ | 웹쉘이 만든 이벤트 고유 ID(UUID). 재전송할 때 같은 값을 씁니다. |
| `type` | ✅ | 3장의 이벤트 종류 |
| `executionId` | ✅ | `POST /v1/executions` 응답의 `executionId`. 경로 값과 같아야 합니다. |
| `requestId` | ✅ | 실행 세션의 요청 ID와 같아야 합니다. |
| `taskId` | 종류별 | 작업 이벤트는 필수, `LOG`는 선택, 그 밖에는 생략 |
| `at` | ✅ | 웹쉘이 이벤트를 기록한 시각(UTC) |
| `data` | | 종류별 데이터. 생략하면 `{}` |
| `webshellSignature` | ✅ | 이벤트 서명. 서명 대상·직렬화 규칙이 정해지지 않아 **웹 서비스는 저장만 하고 검증하지 않습니다.** |

## 3. 이벤트 종류와 처리

경보(`VERIFICATION_ALERT`, `POLICY_MISMATCH`)를 뺀 이벤트는 실행 세션이 `RUNNING`일 때만 받습니다.

| type | 필수 data | 처리 |
|---|---|---|
| `TASK_STARTED` | (`label` 선택) | 작업 `PENDING`·`APPROVED` → `RUNNING`. 진행 트리에 STEP 추가 |
| `TASK_SUCCEEDED` | (`label` 선택) | 작업 `APPROVED`·`RUNNING` → `SUCCEEDED`. STEP 갱신(시작 이벤트가 없었으면 추가) |
| `TASK_FAILED` | (`label` 선택) | 작업 `APPROVED`·`RUNNING` → `FAILED`. STEP 갱신(시작 이벤트가 없었으면 추가) |
| `LOG` | `sequence`(정수), `message`(문자열) | 실행 로그 한 줄 저장, 로그 SSE 전송. `sequence`는 실행 세션 안에서 유일해야 합니다. |
| `EXECUTION_FINISHED` | `status`: `COMPLETED`·`PARTIALLY_COMPLETED`·`FAILED` | 실행 세션과 요청을 최종 상태로 끝냄. 시작 전 작업은 `SKIPPED` |
| `VERIFICATION_ALERT` | 자유 | 상태 변경 없음. 이벤트 저장과 서버 경고 로그만 남김 |
| `POLICY_MISMATCH` | 자유 | 상태 변경 없음. 이벤트 저장과 서버 경고 로그만 남김 |

### 3-1. 최종 상태 규칙

- **웹쉘은 `EXECUTION_FINISHED`보다 먼저 모든 시작한 작업의 종료 이벤트(`TASK_SUCCEEDED`/`TASK_FAILED`)를 보내야 합니다.** `RUNNING`인 작업이 남아 있으면 `409`로 거절합니다. 웹 서비스가 작업 결과를 지어내지 않기 위해서입니다.
- 요청 상태: 일반 Plan은 `data.status`를 그대로 씁니다. 문서 롤백 Plan(`kind=ROLLBACK`, 요청 `ROLLING_BACK`)은 `COMPLETED` → `ROLLED_BACK`, 그 밖은 `ROLLBACK_FAILED`입니다.
- 중단(`KILLED`)은 이 이벤트로 보내지 않습니다. 웹 서비스가 `/v1/executions/{id}/kill`·`/v1/kill-all` 응답을 받은 시점에 직접 반영합니다.

### 3-2. 진행 트리 STEP 노드

- 실행 세션마다 작업 하나에 STEP 하나를 둡니다. id는 `step:{웹 서비스 실행 세션 id}:{taskId}`입니다.
- `label`: 작업 이벤트의 `data.label`(처음 받은 값), 없으면 작업 제목
- `status`: 시작만 받았으면 `RUNNING`, 종료 이벤트를 받으면 `SUCCEEDED`/`FAILED`
- `logRange`: 그 작업에 연결된(`taskId`가 있는) `LOG`의 `sequence` 최솟값~최댓값. 실행 중이면 `to`는 `null`, 로그가 없으면 생략
- SSE: 시작 이벤트는 `node.added`(`parentId`=작업 노드), 종료 이벤트는 작업 노드와 STEP의 `node.updated`

## 4. 응답

| 상황 | 응답 |
|---|---|
| 처리 완료 | `204 No Content` |
| 이미 받은 `eventId` (재전송) | `204` — 아무것도 바꾸지 않음 |
| 인증서 없음 / CN 불일치 | `401 UNAUTHENTICATED` |
| 외부망 출발지 | `403 INTERNAL_NETWORK_ONLY` |
| 없는 실행 세션 | `404 NOT_FOUND` |
| 알 수 없는 `type`, 필수 필드·data 누락, `executionId`·`requestId` 불일치, Plan에 없는 `taskId` | `400 VALIDATION_ERROR` |
| 실행 세션이 `RUNNING`이 아님, 작업 상태가 맞지 않음, 종료 이벤트 없는 작업이 남은 채 `EXECUTION_FINISHED` | `409 INVALID_STATE` |

오류 본문은 웹 서비스 공통 형식 `{ "error": { code, message, details } }`입니다. `4xx`는 재전송해도 같은 결과이므로 웹쉘은 재시도하지 않고 기록만 남기는 것을 권장합니다. 연결 실패·`5xx`는 같은 `eventId`로 재전송합니다.

## 5. 관련 웹쉘 호출 (웹 서비스 → 웹쉘)

- `POST /v1/executions`가 `403`이고 본문 `error.code`가 `VERIFICATION_FAILED`일 때만 요청을 `VERIFICATION_FAILED`로 바꿉니다. 코드가 다른 `403`은 연결 실패와 같이 `502 WEBSHELL_UNAVAILABLE`로 처리해 재시도할 수 있게 둡니다.
- `POST /v1/kill-all` 응답의 `executionIds`에 있는 실행 세션은 바로 `KILLED`로 반영합니다(문서 롤백 실행 포함).
