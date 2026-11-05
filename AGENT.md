# AGENT.md

AI 코딩 에이전트(Claude Code, Codex, Cursor 등)가 이 저장소에서 작업할 때 따르는 공통 가이드입니다.

## 프로젝트 개요

BeforeAI-BE는 **AI가 만든 작업 계획(Plan)을 사람이 검토·승인한 뒤에만 실행되도록 하는 웹 서비스의 백엔드**입니다.

- 사용자가 프롬프트(또는 웹 에디터 변경, 롤백)로 **Request**를 만들면 AI 분석을 거쳐 **Plan**과 여러 **Task**가 생성됩니다.
- Task마다 유형(`TaskType`)·위험도(`Risk`)·필요 승인자(`RequiredApprover`)가 정해지고, 승인자는 기기 키로 서명한 **Approval**을 제출합니다.
- 실제 실행은 내부망의 별도 **웹쉘(실행기)** 이 담당합니다. 웹 서비스는 승인 봉투(`ApprovalEnvelope`)를 열어 보지 않고 전달만 하며, 웹쉘은 mTLS 콜백(`/internal/executions/{id}/events`)으로 진행 상황을 알려 줍니다.

API 명세는 `.docs/BeforeAI.postman_collection.v2.json`(웹 서비스 79개, 웹쉘 8개, 콜백 1개 엔드포인트)에 있습니다. 컬렉션에 "예시 / 계약 미정"으로 표시된 API는 필드명·응답 래퍼가 확정되지 않았으므로 확정 계약으로 간주하지 마세요.

## 기술 스택

- Java 25 (Gradle toolchain), Spring Boot 4.1.x
- Spring Web MVC, Data JPA(Hibernate), Security, Validation, WebSocket, Actuator
- PostgreSQL + Flyway 마이그레이션
- JWT: jjwt 0.13
- API 문서: springdoc-openapi 3.x
- Lombok
- 테스트: JUnit 5, AssertJ, Testcontainers(`postgres:17-alpine`)

## 자주 쓰는 명령

```bash
./gradlew build                 # 컴파일 + 테스트
./gradlew test                  # 전체 테스트 (Docker 필요: Testcontainers)
./gradlew test --tests 'site.siyoung.beforeaibe.repository.PlanRepositoryTest'
./gradlew bootRun               # 로컬 실행 (DB_URL, DB_USERNAME, DB_PASSWORD 환경변수 필요)
```

## 디렉터리 구조

```
src/main/java/site/siyoung/beforeaibe/
├── config/        # JpaAuditingConfig, SecurityConfig
├── entity/        # JPA 엔티티 (Request, Plan, Task, Approval, BaseTimeEntity)
│   └── value/     # 임베디드 값/JSON 레코드 (ApprovalEnvelope, TaskContent, RequiredApprover)
├── enums/         # 도메인 enum (RequestStatus, TaskType, Risk 등)
└── repository/    # Spring Data JPA 리포지토리
src/main/resources/
├── application.properties
└── db/migration/  # Flyway 스크립트 (V{n}__{설명}.sql)
.docs/             # API 명세(Postman Collection)
```

아직 controller/service/dto 계층은 없습니다. 추가할 때는 같은 기준 패키지(`site.siyoung.beforeaibe`) 아래 `controller/`, `service/`, `dto/` 등 계층별 패키지로 둡니다.

## 코드 컨벤션

### 엔티티
- 클래스에 `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`를 붙이고 **세터는 만들지 않습니다.**
- 생성은 `private` 생성자 + `@Builder`로만 합니다. 상태 변경이 필요하면 의도가 드러나는 메서드(예: `approve()`)를 추가합니다.
- PK는 `UUID` + `@GeneratedValue(strategy = GenerationType.UUID)`.
- 연관관계는 `@ManyToOne(fetch = FetchType.LAZY, optional = false)` 기본. 컬렉션은 외부에 수정 불가 리스트로 노출하고 `Plan.addTask()`처럼 편의 메서드로만 변경합니다.
- enum은 `@Enumerated(EnumType.STRING)` + 마이그레이션의 `varchar` 길이와 일치하는 `length`.
- JSON 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`. 값 타입은 `record`로 만들고 `@JsonInclude(NON_NULL)`로 null 필드를 저장하지 않습니다.
- 생성/수정 시각은 `Instant`(UTC). 수정 가능한 엔티티는 `BaseTimeEntity`를 상속하고, 불변 기록(예: `Approval`)은 `createdAt`만 둡니다.
- SQL 예약어와 겹치는 필드는 컬럼명만 바꿉니다 (예: `order` → `task_order`).

### 데이터베이스 / 마이그레이션
- `spring.jpa.hibernate.ddl-auto=validate`입니다. **스키마 변경은 반드시 새 Flyway 파일**(`V2__...sql`, `V3__...sql`)로 하고, 엔티티 매핑과 일치시킵니다.
- 이미 적용된 마이그레이션 파일은 절대 수정하지 않습니다.
- 외래키 컬럼에는 조회 패턴에 맞는 인덱스를 함께 추가합니다.

### 보안
- 현재 `SecurityConfig`는 개발 초기용으로 **모든 요청을 permitAll** 합니다. JWT 인증을 구현할 때 범위를 좁히고 인증 필터를 추가해야 합니다.
- 승인 봉투(`ApprovalEnvelope`)는 웹 서비스에서 복호화·해석하지 않습니다. `Approval`의 `planHash`·`taskIds`·`keyId`는 화면 표시·사전 검사용이며 실행 판단 근거로 쓰지 않습니다.
- `.env`와 비밀값(DB 접속 정보, 키, 토큰)은 절대 커밋하거나 코드·로그·문서에 적지 않습니다.

### 주석·네이밍
- 주석은 한국어로, "무엇"보다 "왜"를 짧게 씁니다.
- 테스트 메서드명은 한국어 문장을 `_`로 이어 씁니다 (예: `같은_Plan_안에서_order가_겹치면_저장되지_않는다`).

## 테스트

- 리포지토리 테스트는 `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})` 조합을 씁니다. H2 같은 인메모리 DB로 대체하지 않습니다(jsonb 등 PostgreSQL 기능 검증 필요).
- 테스트 픽스처는 `RepositoryTestSupport`처럼 패키지 내부 헬퍼에 모읍니다.
- 저장 후 `em.clear()`로 영속성 컨텍스트를 비운 뒤 다시 조회해 실제 DB 매핑을 검증합니다.
- 기능을 추가·수정하면 관련 테스트를 함께 작성하고 `./gradlew test`가 통과하는지 확인합니다.

## Git

- 작업 브랜치는 `dev`, 기본(배포) 브랜치는 `master`입니다.
- 커밋 메시지는 짧은 접두어 + 한국어/영어 요약 형식을 따릅니다 (예: `add: Api 콜렉션 작성`, `fix: ...`, `refactor: ...`).
- 사용자가 요청하지 않으면 커밋·푸시하지 않습니다.
