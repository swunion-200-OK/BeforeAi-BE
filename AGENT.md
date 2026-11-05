# AGENT.md

AI 코딩 에이전트(Claude Code, Codex, Cursor 등)가 이 저장소에서 작업할 때 따르는 공통 가이드입니다.

## 프로젝트 개요

BeforeAI-BE는 **AI가 만든 작업 계획(Plan)을 사람이 검토·승인한 뒤에만 실행되도록 하는 웹 서비스의 백엔드**입니다.

- 사용자가 프롬프트(또는 웹 에디터 변경, 롤백)로 **Request**를 만들면 AI 분석을 거쳐 **Plan**과 여러 **Task**가 생성됩니다.
- Task마다 유형(`TaskType`)·위험도(`Risk`)·필요 승인자(`RequiredApprover`)가 정해지고, 승인자는 기기 키로 서명한 **Approval**을 제출합니다.
- 실제 실행은 내부망(Tailscale)의 **웹쉘**이 담당합니다. 별도 실행기는 없습니다. 웹 서비스는 승인 봉투(`ApprovalEnvelope`)를 열어 보지 않고 전달만 하며, 웹쉘은 mTLS 콜백(`/api/v1/internal/executions/{executionId}/events`)으로 진행 상황을 알려 줍니다.
- Plan 해시는 웹 서비스가 제출(submit) 때 RFC 8785(JCS) + SHA-256으로 만들어 보여 주는 표시용 값이고, 승인자 기기와 웹쉘이 각자 다시 계산합니다.
- 접속 환경(내부망/외부망)은 서버가 출발지 IP로 판별하고, 외부망에서는 허용된 기능만 열립니다(외부망 로그인은 OTP 필수).

API 명세는 `.docs/BeforeAI.postman_collection.v2.json`(웹 서비스 79개, 웹쉘 8개, 콜백 1개 엔드포인트)에 있고, 원본 기획은 Notion의 「해커톤 기획 문서」 아래 「API 명세서」·「기능 명세서」입니다. 컬렉션에 "예시 / 계약 미정"으로 표시된 API는 필드명·응답 래퍼가 확정되지 않았으므로 확정 계약으로 간주하지 마세요. 단, 3.1(인증·내 정보·승인 키)은 컬렉션 예시를 계약으로 채택해 구현했습니다(리프레시 토큰만 본문 대신 HttpOnly 쿠키).

**Slack 관련 기능(알림 설정 API `/me/notification-settings`, `/admin/integrations/slack`, OAuth 콜백, 사용자 Slack ID)은 범위에서 제외했습니다.** 명세에 있더라도 구현하지 않습니다.

웹 서비스 API의 기본 경로는 `/api/v1`이며, 컨트롤러 `@RequestMapping`에 접두어로 붙입니다.

## 기술 스택

- Java 25 (Gradle toolchain), Spring Boot 4.1.x
- Spring Web MVC, Data JPA(Hibernate), Security, Validation, WebSocket, Actuator
- PostgreSQL + Flyway 마이그레이션
- JWT: jjwt 0.13 / 비밀번호: BCrypt(Spring Security) / OTP: `dev.samstevens.totp`(TOTP, RFC 6238)
- API 문서: springdoc-openapi 3.x
- Lombok
- JSON: Jackson 3(`tools.jackson.*`). 단, 어노테이션은 `com.fasterxml.jackson.annotation.*`
- 테스트: JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers(`postgres:17-alpine`)

## 자주 쓰는 명령

```bash
./gradlew build                 # 컴파일 + 테스트
./gradlew test                  # 전체 테스트 (Docker 필요: Testcontainers)
./gradlew test --tests 'site.siyoung.beforeaibe.repository.PlanRepositoryTest'
./gradlew bootRun               # 로컬 실행 (아래 환경변수 필요)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun   # 로컬 개발 프로필: 루프백=내부망, 쿠키 Secure 끔, 시드 데이터 생성
```

| 환경변수 | 필수 | 설명 |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | ✅ | PostgreSQL 접속 정보 |
| `JWT_SECRET` | ✅ | JWT 서명 키 (32바이트 이상) |
| `INTERNAL_CIDRS` | | 내부망으로 볼 IP 대역(쉼표 구분). 기본 `100.64.0.0/10` |
| `CORS_ALLOWED_ORIGINS` | | 브라우저 호출을 허용할 프론트 출처(쉼표 구분, `http://localhost:*` 같은 패턴 가능). 비어 있으면 같은 출처만. local 프로필 기본값은 localhost 전체 포트 |
| `AUTH_REFRESH_COOKIE_SECURE` | | 리프레시 쿠키 Secure 여부. 기본 `true` |
| `WEBSHELL_ENCRYPTION_PUBLIC_KEY`, `WEBSHELL_SIGNING_PUBLIC_KEY` | | 웹쉘 공개키(Base64). 비어 있으면 `/webshell/public-key`가 502 |
| `WEBSHELL_BASE_URL` | | 웹쉘 주소(내부망). 비어 있으면 웹쉘 호출(`/requests/{id}/kill`)이 502 |
| `WEBSHELL_SSL_BUNDLE` | | 웹쉘 mTLS 클라이언트 인증서를 등록한 Spring Boot SSL 번들 이름(`spring.ssl.bundle.pem.<이름>.*`로 등록) |
| `SERVER_SSL_ENABLED`, `SERVER_SSL_BUNDLE` | | 웹 서비스가 직접 TLS를 종료할 때 켬. 번들에 서버 인증서와 웹쉘 CA 신뢰 저장소를 넣음(`client-auth=want`). 웹쉘 콜백 mTLS에 필요 |
| `WEBSHELL_CALLBACK_CLIENT_CN` | | 웹쉘 콜백(`/api/v1/internal/**`)을 허용할 클라이언트 인증서 CN. 비어 있으면 콜백은 모두 401 |
| `SEED_PASSWORD` | local 프로필 | 시드 계정(ADMIN001, MGR001, EMP001, EMP002, EMP101) 비밀번호 |

## 디렉터리 구조

```
src/main/java/site/siyoung/beforeaibe/
├── config/        # SecurityConfig, WebMvcConfig, JpaAuditingConfig, LocalSeedDataRunner(local 프로필)
├── controller/    # REST 컨트롤러 (/api/v1/...)
├── dto/           # 요청·응답 record (기능별 하위 패키지: auth, me, webshell, common)
├── entity/        # JPA 엔티티와 BaseTimeEntity
│   ├── enums/     # 도메인 enum (RequestStatus, TaskType, Risk, Role 등)
│   └── value/     # 임베디드 값/JSON 레코드 (ApprovalEnvelope, TaskContent, RequiredApprover 등)
├── exception/     # ErrorCode, ApiException, GlobalExceptionHandler
├── repository/    # Spring Data JPA 리포지토리
├── security/      # JWT, 인증 필터, 접속 환경 판별(NetworkResolver), @InternalNetworkOnly
└── service/       # 비즈니스 로직·트랜잭션 경계
src/main/resources/
├── application.properties
├── application-local.properties   # 로컬 개발 프로필
└── db/migration/  # Flyway 스크립트 (V{n}__{설명}.sql)
.docs/             # API 명세(Postman Collection)
```

명세의 도메인 엔티티는 모두 만들어져 있지만, 리포지토리·서비스·컨트롤러는 구현한 API(현재 3.1)에 필요한 것만 있습니다. 새 API를 구현할 때 필요한 리포지토리부터 추가합니다.

### 계층 책임
- **Controller**: 경로 매핑, `@Valid` 검증, `@AuthenticationPrincipal AuthUser`, 쿠키·HTTP 상태 코드, `@InternalNetworkOnly` 선언. 접속 환경은 `NetworkResolver`로 판별해 서비스에 인자로 넘깁니다. 엔티티를 직접 반환하지 않습니다.
- **Service**: 트랜잭션 경계와 비즈니스 규칙, 오류는 `ApiException`으로 던집니다. 서블릿 객체(`HttpServletRequest`, 쿠키)를 다루지 않습니다.
- **Repository**: Spring Data 조회 메서드만 둡니다.
- **DTO**: `record` + 검증 어노테이션, 엔티티 변환은 정적 팩토리(`from()`/`of()`)로 합니다.

## 코드 컨벤션

### 엔티티
- 클래스에 `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`를 붙이고 **세터는 만들지 않습니다.**
- 생성은 `private` 생성자 + `@Builder`로만 합니다. 상태 변경이 필요하면 의도가 드러나는 메서드(예: `approve()`)를 추가합니다.
- PK는 `UUID` + `@GeneratedValue(strategy = GenerationType.UUID)`.
- 연관관계는 `@ManyToOne(fetch = FetchType.LAZY, optional = false)` 기본(없을 수 있는 대상만 `optional` 생략). 컬렉션은 외부에 수정 불가 리스트로 노출하고 `Plan.addTask()`, `Project.addOwner()`처럼 편의 메서드로만 변경합니다.
- 초기 엔티티 중 일부는 대상 테이블이 생기기 전에 만들어져 엔티티에서 UUID 필드로만 참조합니다. `Request.projectId`·`requesterId`, `User.departmentId`는 DB FK가 있고(V3), `Approval.approverId`·`keyId`는 FK도 없습니다. 이 필드를 연관관계로 바꾸는 것은 별도 작업으로 다룹니다.
- enum은 `entity/enums` 패키지에 둡니다.
- enum은 `@Enumerated(EnumType.STRING)` + 마이그레이션의 `varchar` 길이와 일치하는 `length`.
- JSON 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`. 값 타입은 `record`로 만들고 `@JsonInclude(NON_NULL)`로 null 필드를 저장하지 않습니다.
- 생성/수정 시각은 `Instant`(UTC). 수정 가능한 엔티티는 `BaseTimeEntity`를 상속하고, 불변 기록(예: `Approval`, `AuditLog`, `SpecVersion`)은 `@EntityListeners(AuditingEntityListener.class)` + `createdAt`만 둡니다.
- 버전이 쌓이는 데이터(기획서, 문서, 정책)는 행을 수정하지 않고 새 버전 행을 추가하며, 가장 큰 `version`이 현재 값입니다.
- SQL 예약어와 겹치는 필드는 컬럼명만 바꿉니다 (예: `order` → `task_order`, `sequence` → `log_sequence`).

### 데이터베이스 / 마이그레이션
- `spring.jpa.hibernate.ddl-auto=update`입니다. 그래도 **스키마 변경은 반드시 새 Flyway 파일**(`V2__...sql`, `V3__...sql`)로 하고, 엔티티 매핑과 일치시킵니다. `update`는 매핑과 마이그레이션이 어긋나도 컬럼을 몰래 만들어 주므로, 스키마를 바꿨다면 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew test`로 일치 여부를 확인합니다.
- 이미 적용된 마이그레이션 파일은 절대 수정하지 않습니다.
- 외래키 컬럼에는 조회 패턴에 맞는 인덱스를 함께 추가합니다.

### 보안
- 인증은 `Authorization: Bearer {액세스 토큰}`(15분)입니다. 리프레시 토큰은 HttpOnly 쿠키(`refresh_token`, Path `/api/v1/auth`)로만 주고받고, 재발급할 때마다 바꿉니다(로테이션). DB에는 해시만 저장합니다.
- CORS는 `/api/**`에만 적용하고 credentials(리프레시 쿠키)를 허용하며 `X-Network`를 노출합니다. 쿠키가 `SameSite=Strict`이므로 프론트와 API는 같은 사이트(같은 등록 도메인, 예: `app.example.com`·`api.example.com`)에 있어야 합니다.
- 토큰 없이 열린 경로는 `POST /api/v1/auth/login`·`/otp/verify`·`/refresh`, `/error`, Swagger, `/actuator/**`뿐입니다. 새 공개 경로가 필요하면 `SecurityConfig`에 명시적으로 추가합니다.
- 웹쉘 콜백(`/api/v1/internal/**`)은 JWT 대신 웹쉘 클라이언트 인증서(mTLS)로 인증하는 별도 필터 체인입니다. 인증서 CN이 `WEBSHELL_CALLBACK_CLIENT_CN`과 같아야 하며 내부망 전용입니다. 이벤트 계약은 `.docs/webshell-callback-events.md`.
- 명세의 망 열이 "내부"인 API는 컨트롤러 메서드에 `@InternalNetworkOnly`를 붙입니다(외부망이면 `403 INTERNAL_NETWORK_ONLY`). 접속 환경은 `NetworkResolver`가 소켓 원격 주소로 판별하며, 프록시가 없으므로 `X-Forwarded-For`는 믿지 않습니다. 모든 응답에 `X-Network` 헤더가 붙습니다.
- 외부망 로그인은 OTP 확인 전까지 임시 토큰만 받습니다(일반 API는 `401 OTP_REQUIRED`). 외부망 세션은 마지막 재발급 후 30분이 지나면 만료됩니다. 내부망에서 받은 토큰을 외부망에서 쓰는 경우는 아직 막지 않습니다.
- `passwordChangeRequired` 사용자는 `GET /me`, `PUT /me/password`, `POST /auth/logout` 외에는 `409 INVALID_STATE`입니다.
- 승인 봉투(`ApprovalEnvelope`)는 웹 서비스에서 복호화·해석하지 않습니다. `Approval`의 `planHash`·`taskIds`·`keyId`는 화면 표시·사전 검사용이며 실행 판단 근거로 쓰지 않습니다.
- `.env`와 비밀값(DB 접속 정보, 키, 토큰)은 절대 커밋하거나 코드·로그·문서에 적지 않습니다.

### 예외 처리
- 서비스는 `throw new ApiException(ErrorCode.X[, "메시지"])`만 던지고, `GlobalExceptionHandler`가 명세 1.2 형식 `{ "error": { code, message, details } }`으로 응답합니다.
- `ErrorCode`는 명세 1.2의 오류 코드 표를 그대로 옮긴 것입니다. 명세에 없는 코드를 새로 만들지 말고 기존 코드에 매핑합니다(예: 로그인 실패·OTP 오류 → `401 UNAUTHENTICATED`, 입력값·형식 오류 → `400 VALIDATION_ERROR`).
- 컨트롤러 밖에서 나는 예외도 같은 형식으로 응답합니다. DB 제약 위반(동시 요청) → `400 VALIDATION_ERROR`, 없는 경로 → `404 NOT_FOUND`, 그 밖의 Spring MVC 4xx(지원하지 않는 메서드 등) → `400 VALIDATION_ERROR`, 예상하지 못한 예외 → 로그를 남기고 `500 INTERNAL_ERROR`. `INTERNAL_ERROR`만 명세 표에 없는 코드입니다.
- 필터 단계 오류는 `JwtAuthenticationFilter`가 request 속성에 남기고 `RestAuthenticationEntryPoint`가 같은 형식으로 응답합니다.

### 주석·네이밍
- 주석은 한국어로, "무엇"보다 "왜"를 짧게 씁니다.
- 테스트 메서드명은 한국어 문장을 `_`로 이어 씁니다 (예: `같은_Plan_안에서_order가_겹치면_저장되지_않는다`).

## 테스트

- 리포지토리 테스트는 `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})` 조합을 씁니다. H2 같은 인메모리 DB로 대체하지 않습니다(jsonb 등 PostgreSQL 기능 검증 필요).
- 리포지토리가 아직 없는 엔티티는 `DomainEntityMappingTest`에서 `TestEntityManager`로 매핑을 검증합니다.
- API 테스트는 `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration.class)` + `@Transactional`로 실제 DB까지 검증합니다. 접속 환경은 `ApiTestSupport.internal()`/`external()`로 원격 주소를 바꿔 지정합니다.
- 외부 의존이 없는 로직(망 판별, TOTP, 시간 기반 만료 등)은 Spring 없이 단위 테스트로 검증합니다.
- 테스트 픽스처는 `RepositoryTestSupport`, `ApiTestSupport`처럼 패키지 내부 헬퍼에 모읍니다. `requests`는 프로젝트·요청자 FK가 있으므로 `RepositoryTestSupport.newRequest(em)`/`persistRequest(em)`로 만듭니다.
- FK·unique 위반을 검증하는 테스트는 한 테스트에 하나만 둡니다. 위반 뒤에는 PostgreSQL 트랜잭션이 중단돼 같은 테스트에서 더 쿼리할 수 없습니다.
- 테스트 전용 설정(더미 `jwt.secret` 등)은 `src/test/resources/config/application.properties`에 둡니다. `src/test/resources/application.properties`에 두면 main 설정 전체를 가리므로 쓰지 않습니다.
- 저장 후 `em.clear()`로 영속성 컨텍스트를 비운 뒤 다시 조회해 실제 DB 매핑을 검증합니다.
- 기능을 추가·수정하면 관련 테스트를 함께 작성하고 `./gradlew test`가 통과하는지 확인합니다.

## Git

- 작업 브랜치는 `dev`, 기본(배포) 브랜치는 `master`입니다.
- 커밋 메시지는 짧은 접두어 + 한국어/영어 요약 형식을 따릅니다 (예: `add: Api 콜렉션 작성`, `fix: ...`, `refactor: ...`).
- 사용자가 요청하지 않으면 커밋·푸시하지 않습니다.

## 3.4 금지 작업·정책 배포

- 구현 계약은 `.docs/forbidden-workflow.md`를 참고합니다. 규칙 조회에는 `RELEASE_PENDING`도 포함하며 해제 요청 후에도 금지는 유효합니다.
- 정책 변경은 `PolicyRevisionService`와 `PolicyChangeLock`을 통해 직렬화합니다. 정책 버전은 전체 `policyPayload`를 보존하고 금지 규칙·등록된 서명키를 포함해 해시를 계산합니다.
- `PolicyDeploymentService`는 버전 저장 후 별도 트랜잭션에서 최신 스냅샷을 배포합니다. 502여도 변경은 이미 저장될 수 있으며 오류 details의 `mutationCommitted`를 확인합니다. 최신 미적용 버전은 기본 30초마다 재전송합니다.
- 새 정책 묶음은 기본적으로 실제 HTTP 클라이언트를 사용합니다. `webshell.policy-stub=true`는 개발·테스트에서만 명시적으로 켭니다. 공개키 등록·폐기도 새 정책 버전으로 같은 흐름을 타며, 등록된 키를 폐기할 때는 `adminProof` 본문이 필요합니다.
- 정책 adminProof는 필드 존재만 검사하며 실제 서명 계약은 웹쉘과 합의해야 합니다. 공개키 등록 API의 Ed25519 검증 규칙을 정책 서명 규칙으로 재사용하지 않습니다.
