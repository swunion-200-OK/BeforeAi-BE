# CI/CD 계획 (GitHub Actions → EC2)

> 기준 커밋: `dev` 병합 후 (Request/Plan 기능, WebshellClient mTLS, Ed25519 서명 검증 포함)
> 전제: 사용자 1명 = 에이전트(로컬 PC) 1대 (1:1). 승인자 서명 키는 런타임 API/DB로 관리.

---

## 1. 목표와 전략

| 항목 | 결정 |
|------|------|
| 플랫폼 | GitHub Actions |
| 배포 대상 | EC2 (SSH + systemd) |
| 빌드 도구 | Gradle (wrapper, Java 25 Temurin) |
| DB | Supabase PostgreSQL (운영), Testcontainers postgres:17 (테스트) |
| 트리거(CI) | `dev` push, `master`/`dev` 로 가는 PR |
| 트리거(CD) | `master` push, 수동(`workflow_dispatch`) |
| 배포 방식 | bootJar → SCP → `app.jar` 교체 → systemd restart → 헬스체크 → 실패 시 롤백 |

흐름: 기능 브랜치 → PR(CI) → `dev` 머지(CI) → `master` 머지(CD 자동 배포).

---

## 2. 파이프라인 구성

### CI (`.github/workflows/ci.yml`)
1. checkout
2. JDK 25 (temurin) 설정
3. Gradle 셋업(캐시)
4. `./gradlew clean build` — 컴파일 + 전체 테스트
   - **Testcontainers 가 postgres:17-alpine 을 띄우므로 Docker 필요** → `ubuntu-latest` 기본 제공
   - Request/Plan, Webshell, 서명 검증 테스트 모두 이 한 번에 포함됨
5. 테스트 리포트 아티팩트 업로드

### CD (`.github/workflows/cd.yml`)
1. checkout → JDK 25 → Gradle 셋업
2. `./gradlew clean bootJar -x test` (테스트는 CI에서 수행)
3. 실행형 jar 경로 해석(`*-plain.jar` 제외)
4. SCP 로 EC2 `~/app/incoming/` 전송
5. SSH:
   - 기존 `app.jar` → `app.jar.bak` 백업
   - 새 jar 로 교체, `systemctl restart beforeai`
   - `/actuator/health` 가 `UP` 될 때까지 최대 150초 폴링
   - 실패 시 `app.jar.bak` 롤백 + `journalctl` 출력 후 실패

---

## 3. dev 병합으로 바뀐 점과 CI/CD 영향

| 변경 | CI | CD |
|------|----|----|
| Request/Plan 기능 + 테스트 | `clean build` 가 자동 포함, **워크플로우 변경 없음** | 없음 |
| `WebshellClient` (내부망 mTLS, RestClient) | 주소 미설정이면 호출 안 함 → 영향 없음 | **운영 환경변수 2개 추가 필요** |
| `Ed25519SignatureVerifier` (실제 서명 검증) | 테스트 포함, 영향 없음 | 없음 |
| `V4__add_plan_timestamps.sql` | Flyway 자동 적용(Testcontainers) | 기동 시 자동 적용 |
| SSE 스트리밍(`RequestStreamService`) | 테스트 포함 | **리버스 프록시 쓰면 버퍼링/타임아웃 주의** |

### 3-1. 새 운영 환경변수 (`.env`)
```
WEBSHELL_BASE_URL     # 내부망 웹쉘 주소 (예: https://webshell.internal:8443). 비면 kill 호출 502
WEBSHELL_SSL_BUNDLE   # mTLS 클라이언트 인증서 SSL 번들 이름. spring.ssl.bundle.pem.<이름> 로 등록
```
- 둘 다 비워도 앱은 정상 기동. 웹쉘 kill API 만 502(`WEBSHELL_UNAVAILABLE`).
- mTLS 를 실제로 쓸 때 SSL 번들(keystore/truststore PEM)을 서버에 두고 번들 이름을 지정.

### 3-2. SSE 주의 (운영 배포 시)
`/requests/{id}/stream` 류의 SSE 를 쓰면, 앞에 Nginx 등을 두는 경우:
- `proxy_buffering off;`
- `proxy_read_timeout` 을 충분히 길게
- 현재 CD 는 앱을 8080 에 직접 띄우므로 리버스 프록시가 없으면 해당 없음.

---

## 4. 변경이 필요 없는 것 (확정)

- **CI 워크플로우**: 신규 코드/테스트가 `clean build` 에 자동 포함 → 그대로 둠.
- **승인자 서명 키(signing_keys)**: 런타임 API/DB. `.env`·CD 와 무관.
  운영 규칙은 `.docs/signing-key-admin-proof.md` 가 정식 문서.
- **배포 메커니즘**: jar 교체 + systemd restart 방식 유지.

---

## 5. 산출물

| 파일 | 역할 | 상태 |
|------|------|------|
| `.github/workflows/ci.yml` | CI | 유지 |
| `.github/workflows/cd.yml` | CD | 유지 |
| `deploy/beforeai.service` | systemd 유닛 | 유지 |
| `deploy/.env.example` | 운영 환경변수 템플릿 | **WEBSHELL_BASE_URL/SSL_BUNDLE 추가** |
| `deploy/README.md` | 배포 가이드 | 웹쉘 mTLS·SSE 항목 보강 |

> `deploy/signing-keys.md` 는 `.docs/signing-key-admin-proof.md` 와 중복이라 제거하고,
> 서명 키 운영은 `.docs/signing-key-admin-proof.md` 로 일원화한다.

---

## 6. 사용자가 해야 할 작업

1. **GitHub Secrets**: `EC2_HOST`, `EC2_USER`, `EC2_PORT`, `EC2_SSH_KEY`
2. **EC2 세팅**: Java 25, `~/app/incoming`, `.env`(권한 600), systemd 등록, sudoers
3. `master` push → 자동 배포
4. (웹쉘 연동 시) `.env` 에 `WEBSHELL_BASE_URL`/`WEBSHELL_SSL_BUNDLE` + SSL 번들 설정
