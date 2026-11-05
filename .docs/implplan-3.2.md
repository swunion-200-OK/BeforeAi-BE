# 구현 계획서 — 3.2 관리자: 계정·조직·정책·키

> 대상: Postman 컬렉션 `BeforeAI API` 3.2 그룹 (16개 엔드포인트)
> 작업 브랜치: `feature/admin-section-3.2` (`dev`에서 분기, 이후 `origin/dev` 병합)
> 기준 문서: `.docs/api.md` 3.2, `.docs/BeforeAI.postman_collection.v2.json`

---

## ⚠️ 기획 변경: Slack 제외 (2026-11 steering)

Slack 연동은 기획 단계에서 삭제되었다. 따라서 **아래 두 엔드포인트는 구현하지 않는다**:
- ~~#14 `PUT /admin/integrations/slack`~~ (제외)
- ~~#15 `GET /integrations/slack/oauth/callback`~~ (제외)

이에 따라:
- `SlackIntegration` 엔티티/리포지토리/컨트롤러/서비스 **만들지 않음**.
- `users.slack_user_id` 컬럼 및 `User.slackUserId` 필드 **추가하지 않음** (계정 생성/변경 DTO에서도 slackUserId 제외).
- 신규 마이그레이션(V4) **불필요** — 3.2 구현에 새 테이블/컬럼 변경 없음.
- **3.2 구현 범위는 16개 → 14개 엔드포인트**로 축소.

---

이 브랜치에 `origin/dev`를 병합한 결과, 3.1(인증·내 정보·서명 키)과 전체 도메인 스캐폴딩이 이미 들어왔다. **아래의 많은 신규 작업은 이미 존재하므로 "재사용"으로 전환**한다.

| 계획서가 신규로 가정했던 것 | 병합 후 실제 상태 |
|---|---|
| enum 패키지 `…/enums` | **`…/entity/enums` 로 이동됨** (경로 전부 교체) |
| `Role`, `PolicyPreset`, `SigningKeyStatus` | **이미 존재** (`entity/enums`) |
| `User`, `Department`, `SigningKey` 엔티티 | **이미 존재** (`entity`) |
| `ApprovalMatrixRule`, `RequiredApprover` 값 객체 | **이미 존재** (`entity/value`) |
| `UserRepository`, `SigningKeyRepository` | **이미 존재** (`repository`) |
| 공통 오류 핸들러/코드 | **이미 존재**: `exception/GlobalExceptionHandler`, `ErrorCode`, `ApiException` |
| 공통 페이지/오류 DTO | **이미 존재**: `dto/common/CursorPageResponse`, `dto/common/ErrorResponse` |
| 인가/망 가드 설계(TODO로 미루려던 것) | **이미 구현됨**: `SecurityConfig`(JWT), `JwtProvider`, `JwtAuthenticationFilter`, `NetworkResolver`, `@InternalNetworkOnly` + `InternalNetworkInterceptor`, `AuthUser` |
| 마이그레이션 `V2`/`V3` (내가 쓰려던 번호) | **점유됨**: `V2__create_user_auth_tables.sql`, `V3__create_domain_tables.sql`. 신규 테이블은 **V4부터** |

### 병합으로 바뀐 핵심 전제
1. **enum/엔티티/리포지토리 import 경로는 `site.siyoung.beforeaibe.entity.enums` / `.entity` / `.entity.value` 를 따른다.**
2. **인증·인가는 TODO가 아니라 실제 동작한다.** ADMIN 전용은 메서드 시큐리티 또는 `SecurityConfig` 매처로 보호하고, 내부망 전용은 기존 `@InternalNetworkOnly`를 **그대로 붙인다**(새 NetworkContext 스텁 불필요).
3. **공통 오류 응답은 기존 `GlobalExceptionHandler` + `ErrorCode` 를 재사용**한다. 새 `@RestControllerAdvice`를 만들지 않는다.
4. **페이지네이션 래퍼는 기존 `CursorPageResponse` 를 재사용**한다(새 `PageResponse` 만들지 않음).
5. 이미 `U​ser`에는 `passwordChangeRequired`, `otpSecret/otpEnabled`, `changePassword()` 등이 있으니 **계정 생성/초기화 로직은 기존 도메인 메서드를 활용**한다.

> 결과적으로 3.2 전용으로 **새로 만들 것**은 대체로 다음만 남는다:
> - 엔티티: `AdminPolicy`(단일 정책 행), `SlackIntegration`(단일 행). *`PolicyVersion` 엔티티가 이미 병합되어 있으므로 정책 버저닝을 그걸로 대체할지 먼저 확인* (아래 2.4 주석).
> - enum: (대부분 존재) — 부족분만.
> - 마이그레이션: `V4__create_admin_policy_slack.sql` (+ 필요 시 시드).
> - 서비스/컨트롤러/DTO: 3.2 16개 엔드포인트 (단, `Department`/`User`/`SigningKey`는 기존 엔티티 재사용).

---

## 0. 전제와 범위

### 0.1 계약 미확정 주의
Postman 컬렉션의 거의 모든 3.2 API는 **요청/응답 스키마·성공 코드·래퍼·인증 전달 방식이 명세에 확정되지 않았다**. 본 계획은 컬렉션의 예시 스키마를 "잠정 계약(provisional contract)"으로 채택하고, 추후 `.docs/api.md`가 확정되면 DTO만 교체할 수 있도록 **컨트롤러 ↔ 서비스 ↔ 엔티티를 분리**하는 것을 전제로 한다.

### 0.2 이번 브랜치의 범위
- 포함: 3.2의 16개 엔드포인트에 대한 엔티티 / 마이그레이션 / 리포지토리 / 서비스 / 컨트롤러 / DTO / 예외 처리.
- **웹쉘 연동은 인터페이스(port)만 정의하고 스텁 구현**으로 대체한다. (실제 mTLS·HPKE·정책 배포는 5장 범위이며 별도 브랜치)
- 인증/인가는 현재 `SecurityConfig`가 `permitAll` 상태이므로, 본 브랜치에서는 **역할 기반 인가를 메서드 레벨 가드로 설계만 하고 실제 토큰 검증은 TODO로 남긴다**. (3.1 인증 브랜치 의존)
- 테스트: 리포지토리 슬라이스 테스트 + 서비스 단위 테스트 + 컨트롤러 웹 슬라이스 테스트.

### 0.3 제외
- 실제 Ed25519/HPKE 암호 연산, 웹쉘 정책 실제 배포, Slack OAuth 실제 토큰 교환, kill-all의 실제 실행 중단 로직(5장 웹쉘 범위).

---

## 1. 엔드포인트 목록과 매핑

| # | Method & Path | 권한 | 망 | 기능ID | 비고 |
|---|---------------|------|----|--------|------|
| 1 | `POST /admin/users` | ADMIN | 내부 | ADM-01 | 계정 생성 + 초기 비밀번호 발급 |
| 2 | `GET /admin/users` | ADMIN | 내부 | ADM-01 | `?departmentId=&role=` 필터 |
| 3 | `PATCH /admin/users/{userId}` | ADMIN | 내부 | ADM-02 | 역할·부서·slack·active 부분 변경 |
| 4 | `POST /admin/users/{userId}/reset-password` | ADMIN | 내부 | ADM-01 | 초기 비밀번호 재발급 |
| 5 | `GET /departments` | 전체 | 전체 | - | 부서 목록 |
| 6 | `POST /admin/departments` | ADMIN | 내부 | ADM-02 | 부서 생성 |
| 7 | `GET /admin/policy` | ADMIN | 내부 | ADM-04 | preset + approvalMatrix + policyHash |
| 8 | `PUT /admin/policy/preset` | ADMIN | 내부 | ADM-04 | SMALL/MEDIUM/LARGE + 웹쉘 배포 |
| 9 | `GET /approval-matrix` | 전체 | 전체 | CORE-02 | 승인 기준표 조회 |
| 10 | `PUT /admin/approval-matrix` | ADMIN | 내부 | ADM-03 | 기준표 수정 + 웹쉘 배포 |
| 11 | `GET /admin/signing-keys` | ADMIN | 내부 | ADM-09 | 공개키 목록(대기/등록) |
| 12 | `POST /admin/signing-keys/{keyId}/register` | ADMIN | 내부 | ADM-09 | 공개키 웹쉘 정책 등록 |
| 13 | `DELETE /admin/signing-keys/{keyId}` | ADMIN | 내부 | ADM-09 | 공개키 폐기 |
| 14 | `PUT /admin/integrations/slack` | ADMIN | 내부 | DEP-04 | Slack 워크스페이스/채널 설정 |
| 15 | `GET /integrations/slack/oauth/callback` | - | 전체 | DEP-04 | Slack 설치 OAuth 콜백 |
| 16 | `POST /admin/kill-all` | ADMIN | 전체 | CORE-11 | 전사 실행 일괄 중단 |

> 참고: 3.2 안에는 ADMIN 전용이 아닌 공개/범용 조회(`GET /departments`, `GET /approval-matrix`, Slack OAuth 콜백)가 섞여 있다. URL prefix가 `/admin`이 아닌 것은 권한이 "전체"임에 유의.

---

## 2. 도메인 모델 (신규 엔티티)

기존 엔티티(`Request`, `Plan`, `Task`, `Approval`)와 동일한 패턴을 따른다:
`@Entity` + `@GeneratedValue(strategy = UUID)` + `@NoArgsConstructor(PROTECTED)` + `@Builder` + 감사 시각이 필요하면 `BaseTimeEntity` 상속.

### 2.1 `User` (`users`) — **이미 존재, 재사용**
병합된 `entity/User`를 그대로 쓴다. 보유 필드: `employeeNumber(unique)`, `passwordHash`, `name`, `role(Role)`, `departmentId(nullable)`, `active`, `passwordChangeRequired`, `otpSecret/otpEnabled`. 도메인 메서드 `changePassword()`, `issueOtpSecret()`, `enableOtp()` 존재. → 3.2 계정 생성/초기화는 이 엔티티와 `UserRepository.findByEmployeeNumber()`를 사용.
*(3.2에 필요하나 현재 없는 것: 사번 중복 체크용 `existsByEmployeeNumber`, slackUserId 필드. 아래 7. 참조)*

### 2.2 `Department` (`departments`) — **이미 존재, 재사용**
병합된 `entity/Department`(id, name) 그대로 사용. 이름 중복 체크 메서드(`existsByName`)와 `DepartmentRepository`는 아직 없으므로 신규 추가 필요.

### 2.3 `SigningKey` (`signing_keys`) — **이미 존재, 재사용**
병합된 `entity/SigningKey`(user FK, algorithm, publicKey(unique), deviceName, status(SigningKeyStatus)) 사용. `SigningKeyRepository`도 존재. 3.2의 register/revoke는 `status` 전이 + 웹쉘 반영만 추가하면 된다. *(현재 엔티티에 status 전이 도메인 메서드가 없으면 추가)*

### 2.4 정책/승인 매트릭스 — **`PolicyVersion` 엔티티로 대체 (신규 `AdminPolicy` 만들지 않음)**
병합된 `entity/PolicyVersion`이 3.2의 정책 요구를 이미 커버한다:
`version(unique)`, `preset(PolicyPreset)`, `approvalMatrix(jsonb List<ApprovalMatrixRule>)`, `policyHash`, `adminKeyId`+`adminSignature`(=adminProof), `webshellApplied`, `createdBy(User)`, `markWebshellApplied()`.
- **현재 정책 = 가장 큰 `version`** 모델. 프리셋/매트릭스 변경 시 **새 행 INSERT(version++)**.
- 따라서 `GET /admin/policy`·`GET /approval-matrix`는 최신 `PolicyVersion` 조회, `PUT /admin/policy/preset`·`PUT /admin/approval-matrix`는 새 버전 생성 + 웹쉘 배포.
- **신규 필요**: `PolicyVersionRepository`(`findTopByOrderByVersionDesc()` 등). (엔티티·마이그레이션은 재사용)

### 2.5 `SlackIntegration` (`slack_integration`) — **신규 (유일하게 새로 만드는 엔티티)**
| 필드 | 타입 | 비고 |
|------|------|------|
| id | UUID | PK |
| workspaceId | String | nullable until connected |
| defaultChannelId | String | nullable |
| connected | boolean | default false |
| accessToken | String(text) | OAuth 콜백 저장(암호화는 TODO) |
| createdAt/updatedAt | Instant | `BaseTimeEntity` |
단일 행으로 운용. `SlackIntegrationRepository`도 신규.

### 2.6 승인 매트릭스 값 객체 — **이미 존재, 재사용**
`entity/value/ApprovalMatrixRule(taskType, risk, requiredApprovers)` 와 `entity/value/RequiredApprover(rule, count, candidates)` 모두 존재. jsonb 매핑은 `PolicyVersion.approvalMatrix`가 이미 `@JdbcTypeCode(SqlTypes.JSON)`로 사용 중. 그대로 쓴다.

---

## 3. enum — 대부분 병합됨

패키지는 **`site.siyoung.beforeaibe.entity.enums`** (이동됨).

| enum | 값 | 상태 |
|------|----|------|
| `Role` | ADMIN, DEPT_MANAGER, EMPLOYEE | **존재** |
| `PolicyPreset` | SMALL, MEDIUM, LARGE | **존재** |
| `SigningKeyStatus` | PENDING, REGISTERED, REVOKED | **존재** (실제 값 확인 후 사용) |
| `TaskType`, `Risk`, `ApproverRule`, `Network` | | **존재** (재사용) |

→ 3.2용으로 **새로 만들 enum 없음** (존재값만 확인 후 사용).

---

## 4. DB 마이그레이션

Flyway. **`V2`, `V3`는 병합으로 점유됨** (`V2__create_user_auth_tables.sql`, `V3__create_domain_tables.sql`). `users`, `departments`, `signing_keys`, `policy_versions` 테이블은 **V3에 이미 생성**되어 있다. 따라서 신규는 **V4부터**:

- **`V4__create_slack_integration.sql`**
  - `slack_integration` 테이블 (단일 행 운용) + 초기 시드(connected=false) 선택.

> 그 외 3.2 테이블은 신규 마이그레이션 불필요(이미 V2/V3에 존재). `ddl-auto=validate`이므로 새 엔티티(`SlackIntegration`)만 스키마와 1:1로 맞추면 된다. jsonb/`timestamptz` 매핑은 기존 패턴(`@JdbcTypeCode(SqlTypes.JSON)`, `BaseTimeEntity`) 준수.
> **주의**: 3.2가 요구하는 `users.slack_user_id` 컬럼이 기존 스키마에 없다면 필요할 때만 `V4`/`V5`에서 `ALTER TABLE`로 덧붙인다. 먼저 V2/V3 SQL을 읽고 결정(아래 7.).

---

## 5. 레이어별 작업 항목

### 5.1 패키지 구조 (신규)
```
site.siyoung.beforeaibe
├─ controller/admin
│   ├─ AdminUserController          (#1~4)
│   ├─ AdminDepartmentController    (#6)
│   ├─ DepartmentController         (#5, 공개)
│   ├─ AdminPolicyController        (#7, #8)
│   ├─ ApprovalMatrixController     (#9 공개, #10 admin)
│   ├─ AdminSigningKeyController    (#11~13)
│   ├─ AdminSlackController         (#14)
│   ├─ SlackOauthController         (#15, 공개)
│   └─ AdminKillController          (#16)
├─ service
│   ├─ UserAdminService
│   ├─ DepartmentService
│   ├─ PolicyService
│   ├─ SigningKeyAdminService
│   ├─ SlackIntegrationService
│   └─ KillSwitchService
├─ dto/admin   (요청/응답 record)
├─ repository  (기존 위치에 추가)
├─ webshell    (port 인터페이스 + 스텁)
│   └─ WebshellPolicyClient (registerKey/revokeKey/applyPolicy → PolicyApplyResult)
└─ support     (초기 비밀번호 생성기, 정책 해시 계산기 placeholder)
```

### 5.2 리포지토리
- `UserRepository extends JpaRepository<User, UUID>`
  - `Optional<User> findByEmployeeNumber(String)`
  - `List<User> findByDepartmentIdAndRole(UUID, Role)` + 필터 조합 → **Specification 또는 쿼리 메서드 분기** (departmentId/role 둘 다 optional이므로 Specification 권장)
  - `boolean existsByEmployeeNumber(String)`
- `DepartmentRepository`
  - `boolean existsByName(String)`
- `SigningKeyRepository`
  - `List<SigningKey> findByStatus(SigningKeyStatus)` (필요 시)
- `AdminPolicyRepository` / `SlackIntegrationRepository` — 단일 행 접근 헬퍼(`findFirstBy...` 또는 고정 ID로 findById)

### 5.3 서비스 책임
| 서비스 | 메서드 | 핵심 로직 |
|--------|--------|-----------|
| UserAdminService | createUser | 사번 중복 검사(409 CONFLICT), 부서 존재 검사, 초기 비밀번호 생성→BCrypt 해시→`mustChangePassword=true`, 평문 비밀번호는 응답에만 1회 노출 |
| | listUsers | departmentId/role 필터, 페이지네이션(items/nextCursor) |
| | updateUser | PATCH 의미론(전달된 필드만 변경), active=false 시 세션 무효화는 TODO |
| | resetPassword | 새 초기 비밀번호 발급, `mustChangePassword=true` 재설정 |
| DepartmentService | list / create | 이름 중복 정책(우선 409) |
| PolicyService | getPolicy | preset+version+approvalMatrix+policyHash 반환 |
| | applyPreset | preset별 기본 매트릭스 적용→version++→policyHash 재계산→`WebshellPolicyClient.applyPolicy()`→webshellApplied 반영 |
| | getApprovalMatrix / updateApprovalMatrix | 매트릭스 검증(taskType×risk 유효 조합, rule enum)→version++→웹쉘 배포 |
| SigningKeyAdminService | list / register / revoke | register: PENDING→REGISTERED + 웹쉘 등록, revoke: →REVOKED + 웹쉘 반영(204) |
| SlackIntegrationService | upsertSettings / handleOauthCallback | state 검증(TODO), connected 토글 |
| KillSwitchService | killAll | 실행 중 요청 조회→웹쉘 kill-all 호출(스텁)→중단된 executionIds 반환 |

> `adminProof`(관리자 서명)는 본 브랜치에서 **형식 미정이므로 유효성 통과시키되 검증 지점(hook)만 남긴다** — `AdminProofVerifier` 인터페이스 + no-op 스텁. 실제 검증은 키/서명 계약 확정 후.

### 5.4 DTO (record, `dto/admin`)
컬렉션 예시 스키마를 그대로 채택. 예:
- `CreateUserRequest(employeeNumber, name, departmentId, role, slackUserId)` → `@NotBlank`/`@NotNull` 검증
- `CreateUserResponse(UserView user, String initialPassword)`
- `UserView(id, employeeNumber, name, role, departmentId, slackUserId, active)`
- `UpdateUserRequest(Role role, UUID departmentId, String slackUserId, Boolean active)` — 모두 nullable(PATCH)
- `PageResponse<T>(List<T> items, String nextCursor)` — 공통 래퍼
- `PolicyView(preset, version, List<ApprovalMatrixRule> approvalMatrix, policyHash)`
- `ApplyPresetRequest(PolicyPreset preset, AdminProof adminProof)` / `ApplyPresetResponse(preset, policyVersion, webshellApplied)`
- `AdminProof(UUID keyId, String signature)`
- `ApprovalMatrixResponse(List<ApprovalMatrixRule> rules)` / `UpdateApprovalMatrixRequest(rules, adminProof)` / `UpdateApprovalMatrixResponse(policyVersion, webshellApplied, rules)`
- `SigningKeyView(id, algorithm, publicKey, status, createdAt, userId)`
- `RegisterKeyRequest(AdminProof adminProof)` / `RegisterKeyResponse(keyId, status, policyVersion)`
- `SlackSettingsRequest(workspaceId, defaultChannelId)` / `SlackSettingsResponse(workspaceId, defaultChannelId, connected)`
- `KillAllRequest(String reason)` / `KillAllResponse(String status, List<UUID> executionIds)`

### 5.5 상태 코드 규약 (잠정)
- 생성: `201 Created` (#1 users, #6 departments)
- 조회/수정/실행: `200 OK`
- 삭제(#13): `204 No Content`
- 사번/부서명 중복: `409 CONFLICT`
- 대상 없음(userId/keyId): `404 NOT_FOUND`
- 검증 실패: `400 BAD_REQUEST`
- 권한/망 위반: `403 FORBIDDEN`
공통 오류 본문은 5.1 형식을 참고한 `{code, message}` 래퍼로 통일 (`@RestControllerAdvice`).

---

## 6. 인가 / 망(Network) 가드 — **기존 구현 재사용**

병합으로 인증·인가가 실제 동작한다. 새로 만들지 말고 기존 것을 붙인다:
- ADMIN 전용 엔드포인트: `SecurityConfig`의 매처 또는 메서드 시큐리티로 보호. 현재 `SecurityConfig` 구성을 먼저 읽고 기존 방식에 맞춰 ADMIN 경로를 추가한다.
- 내부망 전용(`망: 내부`): 기존 **`@InternalNetworkOnly`** 애너테이션 + `InternalNetworkInterceptor`를 핸들러 메서드에 그대로 부착. `NetworkResolver`가 INTERNAL/EXTERNAL 판별을 이미 담당.
- 로그인 사용자 주입: 기존 `AuthUser` 사용.
- 예외 경로: #5, #9(전체 조회), #15(Slack 콜백, 인증 없음), #16 kill-all(ADMIN이나 외부망 허용 → `@InternalNetworkOnly` 미부착).

---

## 7. 병합 후 선행 확인 항목 (구현 착수 전 반드시 읽을 것)

충돌 가능성은 병합으로 해소되었으나, 3.2 착수 전 다음을 **실제로 읽고** 확정한다:
- `V2__create_user_auth_tables.sql`, `V3__create_domain_tables.sql` — `users`/`departments`/`signing_keys`/`policy_versions` 컬럼 확인. 특히 `users`에 `slack_user_id` 컬럼 유무, `signing_keys.status` 전이 규칙.
- `SecurityConfig` — 인증 방식(JWT 필터 체인), ADMIN 경로 보호를 어디에 추가해야 하는지.
- `SigningKeyStatus` 실제 enum 값(PENDING/REGISTERED/REVOKED 가정이 맞는지).
- `SigningKeyService`(3.1, `/me/signing-keys`) 존재 — 3.2의 register/revoke가 이 서비스와 책임이 겹치지 않도록 분리/확장 결정.
- `GlobalExceptionHandler` + `ErrorCode` — 3.2에서 던질 오류 코드(중복/미존재/권한)가 이미 있는지, 없으면 `ErrorCode`에 추가.
- `CursorPageResponse` 형태 — 목록 API(#2, #5, #11)의 응답 래퍼로 재사용.

---

## 8. 테스트 계획

- **리포지토리 슬라이스** (`@DataJpaTest` + Testcontainers, 기존 `RepositoryTestSupport` 재사용):
  - User: 사번 unique, 필터 조회, 부서 FK.
  - SigningKey: status 조회.
  - AdminPolicy/Slack: 단일 행 로드.
- **서비스 단위** (Mockito):
  - createUser 사번 중복 → 409.
  - updateUser 부분 변경 의미론.
  - applyPreset/updateApprovalMatrix → version 증가 + WebshellPolicyClient 호출 검증.
  - registerKey PENDING→REGISTERED, 폐기 →REVOKED.
- **컨트롤러 웹 슬라이스** (`@WebMvcTest`):
  - 각 엔드포인트 경로/메서드/상태코드/JSON 구조(컬렉션 예시와 일치) 검증.
  - validation 실패 400.
- 전체: `./gradlew test` 통과 + `./gradlew build`.

---

## 9. 작업 순서 (커밋 단위)

1. **선행 확인**: 7.의 파일들(V2/V3 SQL, SecurityConfig, SigningKeyService 등) 읽기.
2. **SlackIntegration 엔티티 + V4 마이그레이션** (유일한 신규 테이블) → `./gradlew compileJava` + validate.
3. **리포지토리 신규분**: `DepartmentRepository`, `PolicyVersionRepository`, `SlackIntegrationRepository` + (필요 시) `UserRepository.existsByEmployeeNumber`, `DepartmentRepository.existsByName`.
4. **webshell port 스텁** + `AdminProofVerifier`(no-op 스텁) + 초기 비밀번호 생성기.
5. **서비스** 6종 + 단위 테스트 (기존 `User`/`SigningKey` 도메인 메서드, `PolicyVersion` 버전 증가 모델 활용).
6. **DTO** (`dto/admin`) — 기존 `CursorPageResponse` 재사용, 오류는 기존 `ErrorCode`/`GlobalExceptionHandler`에 위임.
7. **컨트롤러** 9종 + `@InternalNetworkOnly`/권한 매처 부착 + 웹 슬라이스 테스트.
8. `./gradlew test` + `./gradlew build` 최종 검증.
9. 문서 갱신(필요 시 `.docs/api.md` 잠정 계약 반영).

---

## 10. 미해결/결정 필요 사항 (Open Questions)

1. 인증 토큰 전달 방식(Bearer vs 쿠키) — 3.1 확정 대기. 본 브랜치는 가정으로 진행.
2. `adminProof` 서명 대상/정규화/형식 — 미정. 검증 hook만 설계.
3. `approvalMatrix` jsonb 임베드 vs 정규화 테이블 — 초안 jsonb.
4. 페이지네이션 계약(cursor vs offset, `items/nextCursor` 래퍼) — 예시 채택, 확정 시 교체.
5. 부서명/사번 중복 정책 — 우선 unique(409)로 가정.
6. active=false 시 기존 세션/토큰 무효화 — TODO.
7. Slack OAuth `state` 저장·검증 저장소 — TODO.
8. kill-all의 실제 실행 중단은 웹쉘(5장) 의존 — 스텁.
9. `SigningKey` 테이블 소유 브랜치 — 3.1과 사전 합의 필요.
