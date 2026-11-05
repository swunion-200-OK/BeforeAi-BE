# 구현 계획서 — 3.8 웹 코드 에디터

> 대상: Postman 컬렉션 `BeforeAI API` 3.8 그룹 (3개 엔드포인트)
> 작업 브랜치: `feature/section-3.8` (`dev`에서 분기, `origin/dev` 최신 병합 완료)
> 기준 문서: `.docs/api.md` 3.8, `.docs/BeforeAI.postman_collection.v2.json`, 기능 ID `CORE-05`

---

## 0. 요약 — 대부분 재사용, 신규는 "웹쉘 파일 읽기 포트"뿐

`dev` 병합 결과, 3.8이 필요로 하는 도메인 뼈대가 **이미 대부분 존재**한다. 조사 결과 다음은 그대로 재사용한다:

| 3.8이 필요로 하는 것 | 병합 후 실제 상태 |
|---|---|
| `RequestSource.EDITOR` | **이미 존재** (`entity/enums/RequestSource` = PROMPT, EDITOR, ROLLBACK) |
| `RequestStatus.ANALYZING`, `REVIEWING` | **이미 존재** |
| `Request` 엔티티 (`source`, `status`, `currentPlanId`) | **이미 존재**, 빌더/도메인 메서드 포함 |
| `Plan.baseCommit` (기준 커밋 저장) | **이미 존재** (`plans.base_commit varchar(64) NOT NULL`) |
| 코드 수정 작업 표현 (`Task` + `TaskContent.diff`) | **이미 존재** (`TaskType.CODE_MINOR/CODE_FEATURE`, `target`=파일 경로, `TaskContent.diff`) |
| `ErrorCode.EDIT_CONFLICT` (409) | **이미 존재** (`"에디터를 연 뒤 저장소가 바뀌었습니다"`) |
| 202 Accepted 반환 / `@InternalNetworkOnly` / `@AuthenticationPrincipal AuthUser` | **이미 존재** (`RequestController` 패턴) |
| 공통 오류 응답 (`ApiException` → `GlobalExceptionHandler` → `{error.code,...}`) | **이미 존재** |
| 프로젝트 조회 (`ProjectService.getProject`, `Project.repository`) | **이미 존재** |
| 테스트 하네스 (Testcontainers, MockMvc, `@MockitoBean WebshellClient`, `ApiTestSupport`) | **이미 존재** |

> 결과적으로 3.8 전용으로 **새로 만들 것**은 다음만 남는다:
> - **웹쉘 파일 읽기 포트**: `WebshellClient`에 저장소 디렉터리 목록 / 파일 내용 / 현재 기준 커밋 조회 메서드 추가 (현재 `kill()`만 구현됨).
> - **컨트롤러/서비스/DTO**: 3.8의 3개 엔드포인트.
> - 마이그레이션: **원칙적으로 불필요** (요구 상태가 기존 `requests`/`plans`/`tasks`로 모두 커버됨). 추가 상태를 영속화할 경우에만 `V5`.

---

## 1. 엔드포인트 목록과 매핑

| # | Method & Path | 권한 | 망 | 성공 | 기능ID | 비고 |
|---|---------------|------|----|------|--------|------|
| 1 | `GET /projects/{projectId}/files` | 전체 | 내부 | 200 | CORE-05 | `?path=&ref=` 디렉터리 목록 + 기준 커밋 |
| 2 | `GET /projects/{projectId}/files/content` | 전체 | 내부 | 200 | CORE-05 | `?path=&ref=` 파일 내용 (읽기 전용) |
| 3 | `POST /projects/{projectId}/editor/changes` | 전체 | 내부 | 202 | CORE-05 | 에디터 변경 → `source=EDITOR` 요청 생성, `409 EDIT_CONFLICT` |

> 세 엔드포인트 모두 **권한=전체(로그인 사용자), 망=내부**. 따라서 전부 `@InternalNetworkOnly`를 부착하되, 역할 제약은 두지 않는다(프로젝트 조회 가능 여부만 적용).

### 1.1 계약 확정 여부
- #1, #2는 컬렉션에 **요청/응답 스키마·성공 코드가 명세에 확정되지 않은** "잠정 계약"이다. 예시 필드명을 채택하되 DTO만 교체 가능하도록 컨트롤러↔서비스↔웹쉘 포트를 분리한다.
- #3은 **명세에 요청/응답 구조가 정의**되어 있다(`baseCommit`/`description`/`files[].path`/`files[].patch` → `202 {requestId, source, status}`, `409 EDIT_CONFLICT`).

### 1.2 예시 스키마 (컬렉션 기준)

```jsonc
// #1 GET /projects/{projectId}/files  → 200
{ "baseCommit": "9b2f4e1", "path": "src/auth",
  "items": [ { "name": "session.ts", "path": "src/auth/session.ts", "type": "FILE" } ] }

// #2 GET /projects/{projectId}/files/content  → 200
{ "path": "src/auth/session.ts", "ref": "main", "baseCommit": "9b2f4e1",
  "content": "export const loginTimeoutMs = 30000;\n" }

// #3 POST /projects/{projectId}/editor/changes  (요청)
{ "baseCommit": "9b2f4e1", "description": "로그인 타임아웃 긴급 수정",
  "files": [ { "path": "src/auth/session.ts",
               "patch": "--- a/...\n+++ b/...\n@@ -1 +1 @@\n-...\n+...\n" } ] }
// → 202
{ "requestId": "7ee7...", "source": "EDITOR", "status": "ANALYZING" }
// → 409 EDIT_CONFLICT
{ "error": { "code": "EDIT_CONFLICT", "message": "기준 커밋이 변경되었습니다. 최신 파일을 다시 조회해 주세요", "details": {} } }
```

---

## 2. 도메인 모델 — 신규 엔티티 없음

3.8은 **새 엔티티/enum/컬럼을 만들지 않는다.** 재사용 매핑:

- **파일 목록/내용(#1, #2)**: DB에 영속화하지 않는다. 웹쉘의 읽기 전용 API 응답을 그대로 DTO로 변환해 반환한다. 저장소에 쓰지 않는다.
- **에디터 변경(#3)**: 기존 요청 흐름에 그대로 올린다.
  - `Request`: `source = RequestSource.EDITOR`, `status = ANALYZING`, `analysisAttempts = 1`, `prompt`에는 요청 `description`을 넣는다(요청 모델에 코드 에디터 전용 필드가 없으므로 변경 목적을 prompt로 사용).
  - `Plan`: `repository = project.repository`, `baseCommit = 요청 baseCommit`, `kind = NORMAL`. **에디터에서 받은 baseCommit을 Plan.baseCommit에 그대로 저장**한다 → 이후 서명/웹쉘 검증 및 재조회 충돌 판단의 기준.
  - `Task`: **코드 수정 작업 1개**. `type`은 코드 편집(기본 `CODE_MINOR`, 위험도 분류 결과에 따라 `CODE_FEATURE` 가능), `target = files[0].path`(여러 파일이면 대표/다건 처리는 2.1 참조), `content = TaskContent(diff = files[].patch)`, `order = 0`, `risk` = 아래 3.의 분류 결과.

### 2.1 다중 파일 처리 결정
컬렉션 예시는 `files`가 배열이다(여러 파일 patch 가능). 명세는 "코드 수정 작업 1개짜리 Plan"이라 명시한다. → **파일이 여럿이어도 Task는 1개**로 만들고, `TaskContent.diff`에 모든 파일의 unified diff를 합쳐 담는다. `target`은 대표 경로(단일이면 그 경로, 다건이면 첫 경로 + 개수 표기) 또는 다건 표기를 사용한다. *(계약 미확정 → `target` 표기 규칙은 open question 10.1)*

---

## 3. 위험도 분류(ANALYZING → REVIEWING) — AI 미연동, 스텁으로 처리

**현재 코드베이스에 AI 위험도 분류(Bedrock)가 없다.** `RequestService.create`는 상태를 `ANALYZING`으로만 두고 `REVIEWING`으로 전이시키는 로직이 전혀 없다(프롬프트 경로와 동일). 3.8도 동일 원칙을 따르되, 컬렉션이 "AI가 위험도를 분류한 후 REVIEWING"이라고 설명하므로 다음 중 하나를 택한다:

- **채택안(A)**: 프롬프트 경로와 **동일하게 `ANALYZING`에서 멈춘다.** 응답은 예시대로 `status: ANALYZING`. 실제 위험도 분류·REVIEWING 전이는 AI 연동(별도 범위)에서 처리하는 TODO로 남긴다. → 컬렉션 202 예시(`"status": "ANALYZING"`)와 정확히 일치하므로 **A를 기본 채택**.
- 대안(B): 동기 스텁 분류기(`EditorRiskClassifier` no-op)가 즉시 `Risk`를 부여하고 `REVIEWING`으로 전이. 컬렉션 설명과는 맞지만 예시 응답(ANALYZING)과 어긋난다.

> 결정: **A**. Plan/Task는 생성하되 Task `risk`는 분류 전 기본값(예: `MEDIUM`) 또는 미지정 규칙을 따르고, REVIEWING 전이는 AI 연동 hook에 위임(TODO). Task를 분석 전에 만들지, REVIEWING에서 만들지는 3.1 참조.

### 3.1 Plan/Task 생성 시점 결정
프롬프트 경로는 **분석 전에는 Plan이 없다**(`currentPlanId == null`). 하지만 에디터 경로는 사용자가 **이미 완성된 diff**를 제출하므로 분석할 "프롬프트"가 아니라 확정된 변경이다. → **에디터 경로는 생성 시점에 Plan+Task를 즉시 만들고 `currentPlanId`를 채운다.** 상태는 A안대로 `ANALYZING`으로 두되(위험도 재확인/검토 대기 의미), Plan은 이미 존재한다. *(open question 10.2: 에디터는 ANALYZING을 건너뛰고 바로 REVIEWING이 맞는지 명세 확정 필요)*

---

## 4. 웹쉘 파일 읽기 포트 (유일한 신규 인프라)

`webshell/WebshellClient`는 현재 `kill()`만 구현돼 있고 **파일/커밋 조회 메서드가 없다.** 기존 패턴(`RestClient`, mTLS SSL 번들, `WEBSHELL_UNAVAILABLE` 502 fallback)을 그대로 따라 아래를 추가한다:

| 신규 메서드 | 웹쉘 호출(가정) | 반환 |
|---|---|---|
| `listFiles(repository, path, ref)` | `GET /v1/repos/{...}/files?path=&ref=` | `FileListResult(baseCommit, path, items[])` |
| `readFile(repository, path, ref)` | `GET /v1/repos/{...}/files/content?path=&ref=` | `FileContentResult(path, ref, baseCommit, content)` |
| `currentCommit(repository, ref)` | 위 응답의 `baseCommit` 재사용 또는 `GET .../head` | `String` |

- `restClient == null`이면 기존대로 `ApiException(ErrorCode.WEBSHELL_UNAVAILABLE)`.
- 웹쉘 실제 경로/스키마는 미확정이므로 **포트(메서드 시그니처)만 확정**하고, 실제 URL은 `kill()`과 같은 `restClient.get()...retrieve().body(...)` 형태로 두되 계약 확정 시 교체. 테스트에서는 `@MockitoBean WebshellClient`로 스텁.

### 4.1 EDIT_CONFLICT 판정
`POST /editor/changes` 처리 시:
1. 요청 `baseCommit`을 받는다.
2. `webshellClient.currentCommit(project.repository, ref)` (또는 `listFiles`/`readFile` 응답의 `baseCommit`)로 **현재 저장소 커밋**을 얻는다.
3. 두 값이 다르면 `throw new ApiException(ErrorCode.EDIT_CONFLICT)` → 409.
4. 같으면 Plan.baseCommit에 저장하고 요청 생성 진행.

> 웹쉘이 없거나(설정 미비) 커밋 비교 불가 시의 동작은 open question 10.3. 우선 `WEBSHELL_UNAVAILABLE`(502)로 둔다.

---

## 5. 레이어별 작업 항목

### 5.1 패키지 구조 (신규)
```
site.siyoung.beforeaibe
├─ controller
│   └─ EditorController                 (#1, #2, #3)  —  루트 /api/v1/projects/{projectId}
├─ service
│   └─ EditorService                    (파일 조회 위임 + 에디터 변경→요청 생성)
├─ dto/editor   (record)
│   ├─ FileListResponse(baseCommit, path, List<FileEntry> items)
│   ├─ FileEntry(name, path, FileType type)         // FileType = FILE | DIR (enum, 예시 기준)
│   ├─ FileContentResponse(path, ref, baseCommit, content)
│   ├─ EditorChangeRequest(@NotBlank baseCommit, String description, @NotEmpty List<FileChange> files)
│   ├─ FileChange(@NotBlank path, @NotBlank patch)
│   └─ EditorChangeResponse(UUID requestId, RequestSource source, RequestStatus status)  // from(Request)
└─ webshell
    └─ WebshellClient  (+ listFiles/readFile/currentCommit + 결과 record)
```

> `EditorChangeResponse`는 기존 `RequestStatusResponse` 패턴(static factory)을 따른다. `source`를 노출해야 하므로 별도 record로 둔다.

### 5.2 서비스 책임 (`EditorService`)
| 메서드 | 핵심 로직 |
|--------|-----------|
| `listFiles(userId, projectId, path, ref)` | 프로젝트 조회(`ProjectService.getProject`, 없으면 404) → 조회 권한 확인(로그인 사용자면 OK, 필요 시 프로젝트 가시성 규칙) → `webshellClient.listFiles(repo, path, ref)` → `FileListResponse` |
| `readFile(userId, projectId, path, ref)` | 동일 선행 → `webshellClient.readFile(...)` → `FileContentResponse` |
| `createChange(userId, projectId, req)` | ① 프로젝트 조회 ② `currentCommit` vs `req.baseCommit` 비교, 다르면 `EDIT_CONFLICT`(409) ③ `Request`(source=EDITOR, ANALYZING) 저장 ④ `Plan`(repo, baseCommit=req.baseCommit) + `Task`(CODE_*, target, TaskContent.diff) 생성, `request.startReanalysis(plan.id)`로 currentPlanId 연결(또는 전용 메서드) ⑤ `EditorChangeResponse.from(request)` (202) |

> `Request`에 "생성과 동시에 currentPlanId 설정"할 적절한 도메인 메서드가 없다면(`startReanalysis`는 상태를 ANALYZING으로 두므로 재사용 가능) 그대로 쓰거나, 의미가 어긋나면 `attachPlan(planId)` 같은 전용 메서드를 `Request`에 추가한다.

### 5.3 컨트롤러 (`EditorController`, 루트 `/api/v1/projects/{projectId}`)
```java
@InternalNetworkOnly @GetMapping("/files")                 // 200
public FileListResponse files(@AuthenticationPrincipal AuthUser u, @PathVariable UUID projectId,
                              @RequestParam(required=false) String path,
                              @RequestParam(required=false) String ref) { ... }

@InternalNetworkOnly @GetMapping("/files/content")          // 200
public FileContentResponse content(..., @RequestParam String path,
                                    @RequestParam(required=false) String ref) { ... }

@InternalNetworkOnly @PostMapping("/editor/changes")        // 202
public ResponseEntity<EditorChangeResponse> changes(@AuthenticationPrincipal AuthUser u,
        @PathVariable UUID projectId, @Valid @RequestBody EditorChangeRequest req) {
    return ResponseEntity.accepted().body(editorService.createChange(u.id(), projectId, req));
}
```
- 세 엔드포인트 모두 `@InternalNetworkOnly` (망=내부).
- `#2`의 `path`는 필수(`@RequestParam String path`), `ref`는 선택.

### 5.4 상태 코드 규약
- #1, #2 조회: `200 OK`
- #3 생성: `202 Accepted` (요청은 비동기 검토 대상으로 큐잉되는 의미)
- 기준 커밋 불일치: `409 EDIT_CONFLICT`
- 프로젝트 없음: `404 NOT_FOUND`
- 본문 검증 실패(빈 files/baseCommit 등): `400 VALIDATION_ERROR`
- 외부망 접근: `403 INTERNAL_NETWORK_ONLY`
- 웹쉘 불가: `502 WEBSHELL_UNAVAILABLE`

---

## 6. 인가 / 망 가드 — 기존 구현 재사용

- 세 엔드포인트 모두 **`@InternalNetworkOnly`** 부착(기존 `security/InternalNetworkOnly` + 인터셉터, 외부망 → 403 `INTERNAL_NETWORK_ONLY`).
- 로그인 사용자 주입: `@AuthenticationPrincipal AuthUser` (record `(id, role, passwordChangeRequired)`).
- 역할 제약 없음(권한=전체). 단, 프로젝트 가시성 규칙이 필요하면 `RequestService.checkViewable`에 준하는 최소 규칙 적용(open question 10.4).

---

## 7. DB 마이그레이션 — 불필요 (조건부 V5)

- 요구 상태(`source=EDITOR` 요청, 단일 코드 Task, `Plan.baseCommit`)는 **`V1`의 `requests`/`plans`/`tasks`로 모두 커버**된다. 최신 마이그레이션은 **V4** (`V4__add_plan_timestamps.sql`).
- `spring.jpa.hibernate.ddl-auto=update`이지만 **수기 Flyway 마이그레이션을 소스 오브 트루스로 유지**하므로, 에디터 전용 영속 컬럼을 추가하는 경우에만 **`V5__...`**를 만든다. 기본 설계상 신규 컬럼 없음 → **V5 불필요**.

---

## 8. 테스트 계획

- **컨트롤러 API 테스트** (`@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class) @Transactional`, `RequestApiTest` 템플릿):
  - #1 파일 목록: `@MockitoBean WebshellClient`가 `FileListResult` 반환 → 200 + `$.baseCommit`, `$.items[0].name`.
  - #2 파일 내용: 모킹 → 200 + `$.content`.
  - #3 성공: 내부망 + 로그인, 커밋 일치 → 202 + `$.source=EDITOR`, `$.status=ANALYZING`, `$.requestId` 존재. DB에 Request(source=EDITOR)+Plan(baseCommit)+Task(1개, diff) 생성 확인.
  - #3 충돌: 모킹 `currentCommit` != 요청 baseCommit → 409 + `$.error.code=EDIT_CONFLICT`.
  - 외부망(`external()`) 호출 → 403 + `$.error.code=INTERNAL_NETWORK_ONLY`.
  - 검증 실패(빈 `files`/`baseCommit`) → 400 + `$.error.code=VALIDATION_ERROR`.
  - 없는 projectId → 404.
  - 웹쉘 미설정(모킹이 `WEBSHELL_UNAVAILABLE` 던짐) → 502.
- **서비스 단위 테스트** (Mockito): `createChange`의 커밋 비교 분기, Request/Plan/Task 조립 검증.
- 전체: `./gradlew test` + `./gradlew build`.

---

## 9. 작업 순서 (커밋 단위)

1. **DTO** (`dto/editor`): `FileListResponse`/`FileEntry`/`FileType`/`FileContentResponse`/`EditorChangeRequest`/`FileChange`/`EditorChangeResponse` → `compileJava`.
2. **WebshellClient 확장**: `listFiles`/`readFile`/`currentCommit` + 결과 record (기존 `kill` 패턴 준수).
3. **EditorService**: 파일 조회 위임 + `createChange`(커밋 비교·EDIT_CONFLICT·Request/Plan/Task 조립). 필요 시 `Request.attachPlan(planId)` 도메인 메서드 추가.
4. **EditorController**: 3개 엔드포인트 + `@InternalNetworkOnly` + 202.
5. **테스트**: 컨트롤러 API + 서비스 단위.
6. `./gradlew test` + `./gradlew build` 최종 검증.
7. 필요 시 `.docs/api.md` 잠정 계약 반영 / open questions 확정.

---

## 10. 미해결 / 결정 필요 사항 (Open Questions)

1. **다중 파일 Task의 `target` 표기** — 단일 경로 vs 다건 표기 규칙. 우선 단일이면 경로, 다건이면 첫 경로. (2.1)
2. **에디터 경로의 상태 전이** — 생성 즉시 `ANALYZING`(예시 응답 일치, A안) vs 바로 `REVIEWING`. 컬렉션 설명과 예시 응답이 상충 → 명세 확정 필요. (3, 3.1)
3. **웹쉘 미설정/불가 시 충돌 판정** — 현재 `WEBSHELL_UNAVAILABLE`(502)로 둠. 테스트 환경에선 모킹. (4.1)
4. **파일 조회(#1,#2)의 가시성 규칙** — 로그인만으로 모든 프로젝트 조회 허용 vs 프로젝트 담당 부서 제한. 권한=전체이나 프로젝트 소유 범위 제한 여부 미정. (6)
5. **웹쉘 파일 API 실제 경로/스키마** — 미확정. 포트 시그니처만 고정, URL은 계약 확정 시 교체. (4)
6. **`ref` 미지정 시 기본 브랜치** — 웹쉘이 기본 브랜치(HEAD)를 결정하는지, 서버가 `main`을 기본값으로 보내는지 미정.
7. **AI 위험도 분류 연동** — 별도 범위. 현재는 분류 없이 ANALYZING 유지(TODO hook).
