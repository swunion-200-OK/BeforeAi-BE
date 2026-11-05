# 구현 계획 — 감사 로그 전체 요청 확장 (FE → BE 모든 요청 기록)

작성: 2026-11-05
목표: 지금은 `AUDIT_EXPORTED` 한 종류만 기록됨. 이를 **프론트가 백엔드에 보낸 모든 요청**
(`/api/v1/**`, `/internal/**` 제외)을 자동으로 감사 로그에 남기도록 확장.

---

## 0. 조사로 확정한 현재 구조

요청 파이프라인 (`/api/v1/**` 일반 요청):
1. `NetworkHeaderFilter` (`OncePerRequestFilter`, `@Order(HIGHEST_PRECEDENCE)`) — **가장 바깥** 필터.
   `X-Network` 헤더만 세팅, 캐시 안 함.
2. Spring Security 체인 → `JwtAuthenticationFilter` — JWT 파싱 후 `AuthUser(id, role, ...)` 를
   `SecurityContextHolder` 에 심음. 실패 시 `ERROR_ATTRIBUTE` 에 예외만 저장하고 통과.
3. `InternalNetworkInterceptor` (`HandlerInterceptor`, `preHandle`만) — `@InternalNetworkOnly` 검사.
4. 컨트롤러.

핵심 제약:
- **userId**: 컨트롤러는 `@AuthenticationPrincipal AuthUser authUser` → `authUser.id()`.
  `SecurityContextHolder` 는 요청 끝에 **Security 체인이 비움** → 바깥 필터의 post 구간에선 못 읽음.
- **network/ip**: `NetworkResolver.resolve(req)` / `clientIp(req)` — HttpServletRequest 만 있으면
  어디서든 호출 가능(캐시 없음, 매번 재계산).
- **AuditLog 쓰기**: builder 필수값 = `taskIds`(not null, `List.of()`), `detail`(not null, Map).
  `action` 은 **free String, 최대 50자**. 읽기 API 필터는 `action` **정확히 일치**(prefix/LIKE 아님).
  `AuditExportFileService` 는 `action.endsWith("_FAILED")` 로 성공/실패 판정.
- **역할별 조회 범위**: 읽기 API 는 `department`/`request.requesterId` 로 거름. 이 둘이 null 이면
  **ADMIN 에게만 보임**(부서관리자·사원 조회에 안 걸림).
- **비동기 패턴**: `applicationTaskExecutor`(AiAsyncConfig) 재사용. 기존 export 는 afterCommit →
  executor. 로깅 실패가 본 요청을 깨면 안 됨 → 예외는 삼키고 log 만(generate/markFailed 선례).

---

## 1. 설계 결정 (가장 중요)

### 결정 1 — 포착 지점: **OncePerRequestFilter (바깥 필터)** 채택
- 이유: 목표가 "모든 요청". HandlerInterceptor.afterCompletion 은 **매핑된 핸들러에 도달한 요청만**
  잡아서 **401/403(Security 엔트리포인트 거부)·404(no handler)·CORS preflight 를 놓침.**
  "FE 가 요청한 모든 것"이면 인증 실패도 기록해야 하므로 바깥 필터가 맞음.
- 비용: 바깥 필터 post 구간에선 SecurityContext 가 이미 비워짐 → userId 를 못 읽음.
  → **해결: `JwtAuthenticationFilter` 가 인증 성공 시 `authUser.id()` 를 request attribute 에 심는다**
  (작은 변경 1줄). 바깥 필터는 그 attribute 를 읽음. (SecurityContext 재파싱 대신 가벼움.)

### 결정 2 — action 값 형식: **`METHOD /route-template`** (50자 제한 준수)
- raw URI 는 UUID 때문에 50자 초과·카디널리티 폭발. **route pattern**
  (`HandlerMapping.bestMatchingPattern`, 예: `GET /api/v1/requests/{requestId}`)을 쓴다.
  - 단, 바깥 필터에선 bestMatchingPattern 을 보장 못 받을 수 있음(핸들러 매핑 전에 끝난 요청).
    → attribute 로 전달받거나, 없으면 **정규화된 path**(UUID 를 `{id}` 로 치환) fallback.
- 4xx/5xx 는 **접미사 `_FAILED`** 를 붙여 export 성공/실패 판정과 호환.
  예: `GET /api/v1/requests/{id}` (200), `POST /api/v1/requests_FAILED` (4xx/5xx).
  - 50자 빠듯 → 너무 길면 action 은 압축 토큰, 상세(method/path/status)는 `detail` jsonb 에.

### 결정 3 — 세분 필드(department/project/request)는 **일반 로거에서 채우지 않음**
- 이들은 엔드포인트별 도메인 지식이 필요(경로변수 → DB 조회). 일반 로거는 신뢰성 있게 못 채움.
- 결과: 일반 HTTP 로그는 `request`/`department` null → **ADMIN 조회에만 노출**.
  - 기존 도메인 이벤트(승인/거부/실행 등)의 "부서·요청에 걸리는" 감사 기록은 **별건(P1 호출부 배선)**
    으로 두고, 이번 범위는 "전체 요청의 ADMIN 가시 운영 로그"로 한정. (범위 명확화)
  - 선택 확장: 경로에 `{requestId}`/`{projectId}` 가 있으면 그 값만 `detail` 에 넣어 상관관계 유지.

### 결정 4 — 노이즈 제외
- 제외 대상: `/api/v1/internal/**`(웹쉘 mTLS), `/actuator/**`, `/swagger`, `/v3/api-docs`,
  `OPTIONS`(CORS preflight), SSE 스트림(`/logs/stream`, `/progress/stream` — 오래 열림, 종료 시 1건만),
  (선택) 순수 GET 폴링을 줄이려면 설정으로 토글.
- 설정 플래그로 on/off, 제외 패턴 목록을 둬서 시연 중 과다 로깅 조절.

---

## 2. 작업 항목

- [ ] 2-1. **AuditWriter(쓰기 추상화) 신설** — `record(actorId, action, network, ip, detail, [requestId])`.
      - 내부에서 `User` 레퍼런스 로딩(actorId 있을 때), `taskIds=List.of()`, `detail` 보장.
      - 예외 삼키고 `log.warn`(본 요청 깨지 않음). export 의 인라인 빌더도 이걸로 교체(일관화, 선택).
      - 영속화는 `applicationTaskExecutor` 로 **요청 스레드 밖**에서(권장) 또는 자체 트랜잭션 동기 저장.
- [ ] 2-2. **JwtAuthenticationFilter 변경** — 인증 성공 시 `request.setAttribute(AUTH_USER_ID, authUser.id())`.
      (바깥 필터에서 userId 확보용. 단 한 줄 + 상수.)
- [ ] 2-3. **AuditRequestFilter(OncePerRequestFilter) 신설** — `NetworkHeaderFilter` 바로 안쪽 순서.
      - `filterChain.doFilter` **후** `response.getStatus()` 확정 → 포착.
      - 제외 패턴 체크 → 해당하면 skip.
      - action = route-template(or 정규화 path) + 상태 4xx/5xx면 `_FAILED`.
      - userId = request attribute(2-2), network/ip = `NetworkResolver`.
      - detail = `{method, path, status, durationMs, (requestId/projectId if present)}`.
      - `auditWriter.record(...)` 호출(비동기·예외격리).
- [ ] 2-4. **필터 등록/순서** — `@Component @Order` 로 `NetworkHeaderFilter` 다음, Security 체인 바깥.
      (Security 체인 안에서 돌면 401 거부 요청을 못 봄 → 반드시 바깥.)
      - 주의: Security 가 거부한 요청도 이 필터는 통과하므로 status 로 잡힘. 확인 필요.
- [ ] 2-5. **action 50자 가드** — 생성한 action 이 50자 넘으면 자르고 해시/축약. 테스트로 경계 확인.
- [ ] 2-6. **설정** — `audit.request.enabled`, `audit.request.exclude-patterns`,
      `audit.request.include-get`(폴링 과다 대비) 등 플래그.
- [ ] 2-7. **검증** — 로그인/요청생성/조회/승인/거부/404/401 각각이 `GET /audit-logs`(ADMIN)에
      올바른 action·network·ip·status 로 남는지. SSE·actuator·OPTIONS 제외 확인. 본 요청 성능 영향 점검.

---

## 3. 리스크 / 주의

- **SecurityContext 비움 타이밍**: 바깥 필터 post 구간은 context 비워짐 → 반드시 2-2 attribute 사용.
- **로그 폭증**: 전체 요청 기록은 폴링·SSE 로 급증 가능. 제외 패턴·GET 토글 필수. 시연 DB(Supabase) 부하 주의.
- **민감정보**: `detail` 에 요청 본문/쿼리 전체를 넣지 말 것(비밀번호·토큰 유출). method/path/status/크기 수준만.
- **성능**: 비동기(applicationTaskExecutor) 권장. 동기면 각 요청에 DB insert 1회 추가 → 지연.
- **역할 가시성**: 일반 HTTP 로그는 department/request null → ADMIN 만 봄(결정 3). FE 가 사원/부서관리자
  화면에서 "내 요청 로그"를 기대하면 범위 불일치 → **FE 와 노출 대상 합의 필요**.
- **action 정확일치 필터**: "모든 GET" 같은 와일드카드 검색 불가(현 쿼리 제약). 필요 시 읽기 쿼리 확장은 별건.

## 4. 예상 시간

| 항목 | 예상 |
|---|---|
| 2-1 AuditWriter | 0.5일 |
| 2-2 Jwt attribute | 15분 |
| 2-3 AuditRequestFilter | 0.5일 |
| 2-4~2-6 등록·가드·설정 | 0.5일 |
| 2-7 검증·튜닝 | 0.5일 |
| **합계** | **~2일** |

## 5. 확인 필요 (사용자)

1. 노출 대상: 전체 요청 로그를 **ADMIN 만** 보면 되나? (일반 로거는 부서/요청에 못 걸림 → 사원·부서관리자
   조회엔 안 뜸.) FE 가 누구에게 보여줄 계획인지.
2. GET/조회성 요청도 전부 기록? (폴링·새로고침 다수 → 로그 폭증.) 아니면 상태변경(POST/PUT/PATCH/DELETE)만?
3. 인증 실패(401)·404 도 기록 대상? (목표가 "모든 요청"이면 yes → 바깥 필터 설계.)
4. `detail` 에 어디까지 담나? (method/path/status/소요시간 권장. 본문·쿼리는 민감정보라 제외 권장.)
