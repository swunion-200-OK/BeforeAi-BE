# 구현 계획서 — 3.4 금지 작업

> 작업 브랜치: `3.4` (`origin/dev`의 `bbbebd5`에서 분기)
> 기준: `AGENT.md`, `.docs/BeforeAI.postman_collection.v2.json`의 3.4 및 웹쉘 정책 API
> 작성일: 2026-11-05
> 구현 기준 dev: `7da2030` (추가 병합). 아래는 최초 계획이며 실제 채택 계약은 [forbidden-workflow.md](forbidden-workflow.md)에 정리했다.

## 구현 반영

- API 7개, 권한·소속·망 검사, 상태 가드 및 잠금 조회 구현.
- V5에 전체 정책 payload, 고정 잠금 행, 커서 조회 인덱스 추가.
- 프리셋·승인 기준표와 금지 규칙의 정책 생성 경로 통합. 커밋 후 배포와 최신 미적용 정책 재전송 구현.
- 해제 요청의 adminProof는 선택값으로 채택. 없으면 다음 서명된 정책 변경에 포함하며 금지를 유지.
- API 통합·실제 커밋/동시성·정책 HTTP 요청 테스트 추가. 실제 웹쉘 mTLS·서명 호환성은 별도 검증 대상.
- 검증: `SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew test --no-daemon` 전체 212개 테스트 통과(실패·스킵 없음).

## 1. 목적과 범위

부서장이 금지 작업을 제안하고 관리자가 확정·반려하거나 직접 규칙을 추가한다. 확정된 규칙은 서비스 정책과 관리자 서명 정책 묶음에 반영한다. 해제 요청 뒤에도 금지는 유지하며 실제 해제는 웹쉘 서버 콘솔에서만 수행한다.

- 포함: 3.4의 API 7개, 역할·소속·망 검사, 상태 전이, 조회, 정책 버전 및 배포 연계, 관련 테스트.
- 제외: Slack, 웹쉘 콘솔 구현, 실제 해제를 수행하는 신규 API, 작업 대상 문자열을 해석하는 실행 엔진.
- 컬렉션의 필드·응답 래퍼·성공 코드는 **예시 / 계약 미정**이다. 아래 DTO와 상태 코드는 구현을 위한 잠정안이다.
- 컬렉션에서 참조하는 `.docs/api.md`는 현재 저장소에 없다. 확인하지 않은 원문을 확정 근거로 삼지 않는다.

## 2. 엔드포인트

경로에는 공통으로 `/api/v1`을 붙인다. 권한의 “전체”는 인증된 모든 역할을 뜻한다.

| Method | 경로 | 권한 | 망 | 성공 응답 잠정안 |
|---|---|---|---|---|
| GET | `/departments/{deptId}/forbidden-rules` | 전체 | 전체 | 200, `items`, `nextCursor` |
| POST | `/departments/{deptId}/forbidden-proposals` | 소속 DEPT_MANAGER | 내부 | 201, 제안 정보 |
| GET | `/admin/forbidden-proposals` | ADMIN | 내부 | 200, `items`, `nextCursor` |
| POST | `/admin/forbidden-proposals/{proposalId}/confirm` | ADMIN | 내부 | 200, `proposalId`, `status`, `rule`, `policyVersion` |
| POST | `/admin/forbidden-proposals/{proposalId}/reject` | ADMIN | 내부 | 200, `proposalId`, `status` |
| POST | `/admin/forbidden-rules` | ADMIN | 내부 | 201, 규칙 정보 |
| POST | `/admin/forbidden-rules/{ruleId}/release` | ADMIN | 내부 | 200, `ruleId`, `status` |

제안 목록은 선택 필터 `status=OPEN|CONFIRMED|REJECTED`를 지원한다. 목록의 `cursor`, `limit`은 기존 API 관례에 맞춰 추가하는 잠정안으로, 기본 20·최대 100 및 `(createdAt DESC, id DESC)` 정렬을 사용한다.

## 3. 현재 코드와 재사용 범위

| 구성 | 확인한 상태 | 작업 |
|---|---|---|
| `ForbiddenProposal` | 부서, 유형, 대상, 사유, 상태, 반려 사유 및 `confirm()`, `reject()` 존재 | OPEN 상태 가드 보강 |
| `ForbiddenRule` | 부서, 유형, 대상, 사유, 상태, 해제 사유 및 `requestRelease()` 존재 | CONFIRMED 상태 가드 보강 |
| 상태 enum | 제안 OPEN/CONFIRMED/REJECTED, 규칙 CONFIRMED/RELEASE_PENDING | 그대로 사용 |
| V3 마이그레이션 | 두 테이블과 부서 FK, 제안 status·부서 인덱스 존재 | 기존 파일 수정 금지 |
| 금지 작업 전용 계층 | Repository/Service/Controller/DTO 없음 | 신규 추가 |
| `DepartmentRepository`, `UserRepository` | 존재 | 대상 부서 및 실제 사용자 소속 확인 |
| `PolicyService`, `PolicyVersionRepository` | 프리셋·승인 기준표 변경 시 버전 생성 | 금지 규칙 변경도 같은 버전 체계 사용 |
| `WebshellPolicyClient` | 현재 applyPolicy 인자는 버전·승인 기준표·해시뿐 | 금지 규칙과 adminProof 전달을 위한 확장 필요 |
| `StubWebshellPolicyClient` | 항상 배포 성공을 반환하는 스텁 | 실제 웹쉘 반영 완료로 간주하지 않음 |
| `PolicyHasher` | 프리셋·승인 기준표만 포함, JCS 근사 구현 | 금지 규칙 포함 및 정규화 계약 정리 필요 |
| `AdminProofVerifier` | 형식 검사 지점, 실제 서명 검증 아님 | 기존 지점 재사용, 최종 검증은 웹쉘 책임 |
| 보안·공통 응답 | JWT, `/admin/**` ADMIN 검사, 내부망 애너테이션, 공통 오류·커서 존재 | 재사용 |

## 4. DTO와 계층 설계

신규 DTO는 `dto/forbidden`의 검증 어노테이션이 붙은 record로 둔다. 응답 변환은 `from()`으로 하고 엔티티를 반환하지 않는다.

| DTO 잠정 이름 | 필드 |
|---|---|
| `ForbiddenProposalCreateRequest` | `taskType`, `target`, `reason` |
| `ForbiddenRuleCreateRequest` | `departmentId`, `taskType`, `target`, `reason`, `adminProof` |
| `ForbiddenProposalConfirmRequest` | 기존 `dto.admin.AdminProof` 재사용 |
| `ForbiddenProposalRejectRequest` | `reason` |
| `ForbiddenRuleReleaseRequest` | `reason` |
| `ForbiddenProposalResponse` | `id`, `departmentId`, `status`, `taskType`, `target`, `reason` |
| `ForbiddenRuleResponse` | `id`, `departmentId`, `taskType`, `target`, `reason`, `status` |
| `ForbiddenProposalConfirmResponse` | `proposalId`, `status`, `rule`, `policyVersion` |
| 상태 변경 응답 | 반려: `proposalId`, `status` / 해제 요청: `ruleId`, `status` |

- UUID·유형·adminProof는 필요한 곳에 `@NotNull`, 대상·사유는 `@NotBlank`를 적용한다. 대상은 DB와 동일하게 최대 1024자로 제한한다.
- `DepartmentForbiddenController`: 부서별 조회와 제안 생성. 제안 생성 메서드에만 `@InternalNetworkOnly`를 적용한다.
- `AdminForbiddenController`: 관리자 목록·확정·반려·직접 추가·해제 요청. 각 핸들러에 내부망 제약을 적용한다.
- `ForbiddenProposalService`: 소속 검사, 제안 생성·조회·확정·반려 및 규칙 생성 연계.
- `ForbiddenRuleService`: 규칙 조회·직접 추가·해제 요청. 정책 변경은 공통 정책 서비스로 위임한다.
- `ForbiddenProposalRepository`, `ForbiddenRuleRepository`: 필터·커서 조회 및 상태 변경 대상 잠금 조회. 서비스에서 트랜잭션을 관리한다.

## 5. 권한과 상태 규칙

1. 제안 생성은 DB 사용자 역할이 DEPT_MANAGER이고 사용자 `departmentId`가 경로 부서와 같아야 한다. ADMIN의 대리 제안은 허용 범위에 추가하지 않는다.
2. 관리자 API는 기존 SecurityConfig의 `/api/v1/admin/**` 가드를 사용한다. 내부망 판별은 기존 인터셉터를 사용한다.
3. 제안은 OPEN으로 생성하며 확정 전에는 규칙이나 배포 정책에 넣지 않는다.
4. `OPEN → CONFIRMED`: 제안 값으로 CONFIRMED 규칙 하나를 만들고 정책 버전을 생성한다.
5. `OPEN → REJECTED`: 반려 사유를 별도 필드에 저장한다. 제안 원문 사유를 덮어쓰지 않는다.
6. 관리자 직접 추가는 제안을 만들지 않고 CONFIRMED 규칙과 새 정책 버전을 만든다.
7. `CONFIRMED → RELEASE_PENDING`: 해제 사유를 저장한다. 금지 규칙의 효력은 유지한다. 반복 해제·재확정·확정 후 반려는 `409 INVALID_STATE`로 처리하는 잠정안이다.
8. 부서별 규칙 조회는 여전히 유효한 RELEASE_PENDING도 포함하고 상태를 그대로 보여 주는 잠정안이다. “확정 규칙” 필터의 구체적 의미는 구현 계약에 명시한다.
9. 없는 부서·제안·규칙은 `404 NOT_FOUND`, 역할·소속 위반은 `403 FORBIDDEN_ROLE`, 외부망 변경 요청은 `403 INTERNAL_NETWORK_ONLY`, 형식 오류는 `400 VALIDATION_ERROR`를 사용한다.

같은 제안에 대한 확정·반려 경쟁은 대상 행의 비관적 쓰기 잠금과 상태 재확인으로 직렬화한다. 정책 버전 증가도 3.2 변경과 공통으로 직렬화해야 한다. 최신 행만 잠그는 방식은 최초 정책 생성 및 새 행 삽입 경쟁을 완전히 막지 못하므로, 고정 잠금 행 또는 PostgreSQL 트랜잭션 advisory lock 중 기존 구조에 맞는 방식을 구현 시 선택한다.

## 6. 정책·웹쉘 연계

현재 정책 포트에 금지 규칙만 별도로 전달하면 기존 프리셋 변경 시 규칙이 누락될 수 있다. 공통 정책 묶음 생성 경로를 만들어 프리셋·승인 기준표·금지 규칙·서명키를 보존하는 방향으로 확장한다.

- 확정·직접 추가는 최신 프리셋과 승인 기준표를 유지하고 금지 규칙을 포함한 새 버전을 만든다. 기존 정책 버전 행을 수정하지 않는다.
- 전달할 정책 묶음과 adminProof를 같은 포트에서 받도록 확장한다. 웹쉘의 응답 version/hash/applied를 확인한다.
- 해제 요청은 서비스 RELEASE_PENDING, 웹쉘 RELEASE_PENDING_LOCAL의 관계를 지켜 전달한다. 정책에서 규칙을 삭제하여 해제를 우회하지 않는다.
- 해제 요청 예시에는 adminProof가 없지만 정책 배포에는 관리자 서명이 필요하다. 해제 요청의 서명 전달 및 배포 시점을 계약 확인 항목으로 남긴다. 서명 없는 정책 배포를 임의로 추가하지 않는다.
- 실패한 배포는 `webshellApplied=false`로 남기고 동일 버전·동일 payload 재전송이 가능하도록 설계한다. 실제 적용 전에는 배포 성공으로 표시하지 않는다. 연결 실패의 API 응답은 기존 `WEBSHELL_UNAVAILABLE`을 사용하되 저장된 상태와 함께 문서화한다.
- DB 커밋과 외부 HTTP 호출은 원자적이지 않다. 정책 스냅샷을 먼저 영속화한 뒤 커밋 후 배포하고 결과를 별도 트랜잭션으로 기록하는 구조를 검토한다. 실패 시 재전송 가능한 payload·서명을 보존한다.
- `PolicyVersion`에는 금지 규칙 스냅샷이 없다. 과거 버전 재배포에 현재 규칙을 섞지 않도록 정책 payload 저장 방식과 마이그레이션을 함께 설계한다.
- 해시 대상, 배열 순서, RFC 8785 정규화 및 서명 대상은 웹쉘 계약과 일치시킨다. 현재 근사 해시를 정식 JCS로 문서화하지 않는다.

실제 mTLS 정책 호출은 현재 스텁과 별도 구현이 필요하다. 기존 `WebshellClient`의 SSL 번들 설정 방식을 참고한다. 개발 스텁 검증과 실제 웹쉘 통합 검증의 완료 기준을 구분한다.

## 7. DB 변경

기본 CRUD와 상태·사유 저장은 기존 V3 스키마로 가능하다. 엔티티를 중복 생성하지 않는다.

- 정책 payload 보존 또는 잠금 행이 필요하면 **새 V5 이상** 마이그레이션으로 추가한다. 현재 최신 파일은 `V4__add_plan_timestamps.sql`이므로 착수 시 번호를 다시 확인한다.
- 커서 조회에 필요한 복합 인덱스는 실제 쿼리에 맞춰 `(department_id, status, created_at DESC, id DESC)` 등의 구성을 검토한다. 상태 필터 없는 목록의 정렬 인덱스도 별도로 검토한다.
- 이미 적용된 V1~V4는 수정하지 않는다. 새 컬럼은 엔티티 매핑과 함께 변경한다.
- 현재 가이드는 기본 ddl-auto가 update이므로, 스키마 변경 검증은 반드시 validate 설정으로 수행한다.

## 8. 구현 순서

1. API 잠정 계약, RELEASE_PENDING 조회 포함 여부, 해제 요청 서명 전달 및 배포 실패 응답을 정리한다.
2. Repository와 엔티티 상태 가드, 잠금 및 필터 조회를 구현한다.
3. DTO·컨트롤러·서비스를 추가하고 인증·역할·소속·내부망 검사를 연결한다.
4. 공통 정책 묶음·버전 생성·payload 보존·배포 포트를 확장한다. 3.2 프리셋/승인 기준표 변경도 금지 규칙을 보존하도록 수정한다.
5. 필요한 신규 마이그레이션을 작성하고 테스트를 수행한다.
6. 잠정 계약과 실제 구현 차이를 컬렉션 또는 문서에 반영한다.

## 9. 검증과 완료 조건

- API 통합 테스트는 기존 `ApiTestSupport`, MockMvc, PostgreSQL Testcontainers를 사용한다.
- 7개 API 정상 응답과 필수값·UUID·enum·길이 오류를 검증한다.
- 미인증, 외부망 변경 차단, EMPLOYEE 제안 차단, 타 부서장 제안 차단, 관리자 API 역할 제한, 전체 역할의 외부망 규칙 조회를 검증한다.
- 제안 확정 시 규칙 1개·정책 버전 1개 생성, 반려 시 규칙 미생성, 반려·해제 사유 보존, 불가능한 상태 전이 거절을 검증한다.
- 중복 확정 및 확정/반려 동시 요청이 규칙 중복이나 상태 덮어쓰기를 만들지 않는지 별도 트랜잭션으로 검증한다.
- 목록 상태 필터, 동일 생성 시각의 커서 경계, 빈 목록, 잘못된 커서, RELEASE_PENDING 포함을 검증한다.
- 정책 클라이언트 테스트는 payload·adminProof 전달, 버전/해시 불일치, 연결 실패 및 재전송을 검증한다. 해제 요청 후 금지가 유지되는지 확인한다.
- 기존 3.2 정책 테스트와 3.5 요청 테스트가 계속 통과해야 한다.
- `./gradlew test`를 실행하고, 스키마 변경이 있으면 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew test`도 실행한다. Docker나 실제 웹쉘이 없어 확인하지 못한 범위는 명시한다.

완료 조건은 API 7개의 권한·상태·오류 계약 및 회귀 테스트 통과다. 실제 웹쉘 정책 적용은 mTLS·서명 계약과 실제 응답까지 확인한 경우에만 완료로 표시한다.
