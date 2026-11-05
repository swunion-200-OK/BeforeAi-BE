# CI/CD 가이드 (GitHub Actions → EC2)

이 프로젝트는 GitHub Actions로 CI/CD를 돌린다.

| 워크플로우 | 트리거 | 하는 일 |
|-----------|--------|---------|
| `.github/workflows/ci.yml` | `dev` push, `master`/`dev` 로 가는 PR | Gradle `clean build` (테스트 포함, Testcontainers) |
| `.github/workflows/cd.yml` | `master` push, 수동 실행 | `bootJar` 빌드 → EC2로 SCP → systemd 재시작 → 헬스체크 |

흐름: 기능 브랜치 → PR(CI) → `dev` 머지(CI) → `master` 머지(CD, 자동 배포).

---

## 1. GitHub Secrets 등록

리포지토리 → Settings → Secrets and variables → Actions → New repository secret.

| 이름 | 설명 | 예시 |
|------|------|------|
| `EC2_HOST` | EC2 퍼블릭 IP 또는 도메인 | `13.124.xx.xx` |
| `EC2_USER` | SSH 접속 유저 | `ubuntu` |
| `EC2_PORT` | SSH 포트 | `22` |
| `EC2_SSH_KEY` | 접속용 개인키 전체 내용(PEM) | `-----BEGIN ... KEY-----` |

`EC2_SSH_KEY`는 EC2 접속에 쓰는 **개인키 파일 전체**를 붙여넣는다(헤더/푸터 포함).
새 키를 쓰려면 로컬에서 `ssh-keygen -t ed25519 -f deploy_key` 로 만든 뒤
공개키(`deploy_key.pub`)를 EC2의 `~/.ssh/authorized_keys`에 추가하고
개인키(`deploy_key`)를 이 시크릿에 넣는다.

> DB 비밀번호·JWT 시크릿 등 **앱 런타임 값은 GitHub Secrets가 아니라 EC2의 `.env`로 주입**한다.
> (CD는 jar만 옮기고 앱 설정은 서버가 들고 있는 구조)

---

## 2. EC2 최초 1회 세팅

EC2(Ubuntu 기준)에 SSH로 들어가서:

```bash
# 1) Java 25 설치 (Temurin)
sudo apt-get update
sudo apt-get install -y wget apt-transport-https gnupg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/keyrings/adoptium.asc
echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(awk -F= '/VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-25-jdk
java -version   # 25 확인

# 2) 앱 디렉터리
mkdir -p /home/ubuntu/app/incoming

# 3) 환경변수 파일 (아래 .env.example 참고해서 실제 값 채우기)
#    scp 로 올리거나 직접 편집
chmod 600 /home/ubuntu/app/.env

# 4) systemd 서비스 등록
sudo cp beforeai.service /etc/systemd/system/beforeai.service
sudo systemctl daemon-reload
sudo systemctl enable beforeai
```

- `.env`: `deploy/.env.example`를 복사해 채운다. `DB_URL`은 **JDBC 형식**이어야 한다.
- `beforeai.service`: `deploy/beforeai.service` 참고. 유저/경로가 다르면 수정한다.
- CD가 `sudo systemctl restart beforeai` 와 `sudo journalctl` 를 쓰므로,
  배포 유저가 비밀번호 없이 해당 명령을 쓸 수 있어야 한다:

  ```bash
  echo "ubuntu ALL=(ALL) NOPASSWD: /bin/systemctl restart beforeai, /bin/journalctl" | sudo tee /etc/sudoers.d/beforeai
  sudo chmod 440 /etc/sudoers.d/beforeai
  ```

---

## 3. 동작 방식

1. `master`에 push → `cd.yml` 실행
2. 러너에서 `./gradlew clean bootJar -x test` 로 실행 가능한 jar 생성
3. jar을 EC2 `/home/ubuntu/app/incoming/` 로 SCP
4. SSH로 접속해서:
   - 기존 `app.jar`를 `app.jar.bak`로 백업
   - 새 jar을 `app.jar`로 교체
   - `systemctl restart beforeai`
   - `/actuator/health`가 `UP` 될 때까지 최대 150초(5초 × 30회) 대기
   - 실패하면 `app.jar.bak`로 롤백 후 로그 출력하고 실패 처리

---

## 4. 포트 / 보안그룹

- 앱은 8080에서 뜬다. 외부에 바로 노출하려면 보안그룹에서 8080(또는 리버스 프록시용 80/443)을 연다.
- SSH(22)는 GitHub Actions 러너에서 접속 가능해야 한다. IP 고정이 어려우면 22를 열되
  키 기반 인증만 허용한다(비밀번호 로그인 비활성화 권장).

---

## 5. 승인자 서명 키 / 웹쉘 연동 (런타임·선택 설정)

### 5-1. 승인자 서명 키 (signing_keys)

사용자(승인자) 1명당 로컬 PC 1대가 Ed25519 키페어를 만들어 공개키만 서버에 등록한다.
**런타임 DB/API로 동작**하므로 `.env`·CD·재배포와 무관하다.

- 요약: PC에서 키 생성 → `POST /api/v1/me/signing-keys` 제출(PENDING)
  → 관리자가 `POST /api/v1/admin/signing-keys/{keyId}/register` 등록(REGISTERED)
- 사용자당 활성 키 1개. 사용자 N명이면 키 N개가 DB에 쌓인다(서버 설정 불필요).
- 서명 규칙·부트스트랩·기기 교체 등 **정식 운영 문서: `.docs/signing-key-admin-proof.md`**
- 스키마는 Flyway(`V2`)가 기동 시 자동 적용.

### 5-2. (선택) 웹쉘 공개키 핀 `WEBSHELL_*`

`GET /api/v1/webshell/public-key` 가 내려 주는 웹쉘 시스템 전체의 단일 신원 키.
승인자 서명 키와는 다른 값이다. 웹쉘 crypto 준비 전에는 비워둬도 된다(그 API만 502).
쓸 거라면 공개키만 `.env`에 넣고 `sudo systemctl restart beforeai` 로 교체(재배포 불필요).

### 5-3. (선택) 웹쉘 내부망 연동 mTLS `WEBSHELL_BASE_URL` / `WEBSHELL_SSL_BUNDLE`

실행 중단(kill) 등 내부망 웹쉘 API 호출용. 둘 다 비면 그 호출만 502, 앱은 정상 기동.

- `WEBSHELL_BASE_URL`: 내부망 웹쉘 주소 (예: `https://webshell.internal:8443`)
- `WEBSHELL_SSL_BUNDLE`: mTLS 클라이언트 인증서 SSL 번들 이름.
  `application.properties` 에 아래처럼 번들을 등록하고 그 이름을 적는다:
  ```properties
  spring.ssl.bundle.pem.webshell.keystore.certificate=file:/home/ubuntu/app/certs/client.crt
  spring.ssl.bundle.pem.webshell.keystore.private-key=file:/home/ubuntu/app/certs/client.key
  spring.ssl.bundle.pem.webshell.truststore.certificate=file:/home/ubuntu/app/certs/ca.crt
  ```
  인증서 파일은 서버에만 두고 `chmod 600`. git 에 올리지 않는다.

### 5-4. (선택) SSE 와 리버스 프록시

요청 진행 스트리밍(SSE)을 쓰고 앞에 Nginx 등을 둘 경우:
- `proxy_buffering off;`
- `proxy_read_timeout` 을 넉넉히(예: `3600s`)

현재 CD 는 앱을 8080 에 직접 띄우므로 리버스 프록시가 없으면 해당 없음.

---

## 6. 문제 해결

```bash
# 서비스 상태/로그
sudo systemctl status beforeai
sudo journalctl -u beforeai -f

# 헬스체크 수동 확인
curl -s http://localhost:8080/actuator/health
```
