# 네트워크 입구 구성 — 외부(Cloudflare) + 내부(Tailscale)

## 구조

```
외부 사용자 ──HTTPS──> Cloudflare(도메인) ──> EC2:443 ──Nginx──> 127.0.0.1:8080 (앱)
                                                       └ X-Access-Network: external (강제)

내부 사용자 ──HTTPS──> tailscale serve(:443 tailnet) ──> 127.0.0.1:8081 ──Nginx──> 127.0.0.1:8080 (앱)
                                                                   └ X-Access-Network: internal (강제)
```

앱은 `X-Access-Network` 헤더로 INTERNAL/EXTERNAL을 판별한다(소켓 IP 아님). 두 입구가 모두
루프백(127.0.0.1:8080)으로 프록시되므로 IP로는 구분 불가 → **입구별로 헤더를 강제**하는 방식.

- 외부 입구(Nginx :443): 들어온 `X-Access-Network` 를 무시하고 항상 `external` 로 덮어씀 → 위조 불가.
- 내부 입구(Nginx 127.0.0.1:8081): 항상 `internal`. 이 포트는 루프백에만 bind 되어 tailscale serve 만 접근.
- 앱 8080 포트는 **외부에 직접 노출 금지**(보안그룹/방화벽에서 8080 차단). 반드시 두 입구만 통함.

## 앱 설정 (.env on EC2)

```
# 리버스 프록시 뒤에서 헤더 기반 판별 활성
NETWORK_TRUST_PROXY_HEADER=true
# (기본 헤더명이 X-Access-Network 라 NETWORK_TRUSTED_HEADER 는 생략 가능)
FORWARD_HEADERS_STRATEGY=framework
# HTTPS 뒤이므로 리프레시 쿠키 Secure 유지
AUTH_REFRESH_COOKIE_SECURE=true
# CORS: 외부 프론트 도메인
CORS_ALLOWED_ORIGINS=https://swunion.pages.dev,https://*.pages.dev
```

> 참고: `CORS_ALLOWED_ORIGINS` 는 **브라우저 프론트의 출처**다(API 도메인이 아님).
> 프론트가 `swunion-server.siyoung.site` 를 호출하는 쪽이고, 값에는 프론트 배포 출처를 넣는다.

> 중요: `NETWORK_TRUST_PROXY_HEADER=true` 로 켜는 순간, 앱은 소켓 IP 를 보지 않고 헤더만 본다.
> 따라서 **반드시 두 Nginx 입구를 통해서만** 트래픽이 오게 하고, 8080 직접 접근을 막아야 한다.
> (안 그러면 헤더 없는 직접 요청은 EXTERNAL 로 처리되어 내부망 API 가 막히고, 헤더를 위조한
>  직접 요청은 internal 로 통과한다. 8080 차단이 안전장치.)

## 1) 외부 — Nginx + Cloudflare

1. `beforeai.conf` 의 인증서 경로, CORS 도메인 확인(server_name 은 swunion-server.siyoung.site 로 설정됨).
2. 인증서: Cloudflare 대시보드 → SSL/TLS → Origin Server → Origin Certificate 발급 →
   `/etc/ssl/beforeai/origin.pem`, `origin.key` 로 저장. Cloudflare SSL 모드는 **Full (strict)** 권장.
3. 설치:
   ```bash
   sudo cp beforeai.conf /etc/nginx/sites-available/beforeai.conf
   sudo ln -s /etc/nginx/sites-available/beforeai.conf /etc/nginx/sites-enabled/
   sudo nginx -t && sudo systemctl reload nginx
   ```
4. Cloudflare DNS: A 레코드(도메인 → EC2 공인 IP), 프록시 ON(주황 구름).
5. EC2 보안그룹: 443 은 Cloudflare IP 대역만 허용 권장, 8080 은 외부 차단.

## 2) 내부 — Tailscale serve

1. 내부용 Nginx 입구 설치:
   ```bash
   sudo cp beforeai-internal.conf /etc/nginx/sites-available/beforeai-internal.conf
   sudo ln -s /etc/nginx/sites-available/beforeai-internal.conf /etc/nginx/sites-enabled/
   sudo nginx -t && sudo systemctl reload nginx
   ```
2. tailscale serve 로 tailnet HTTPS → 내부 Nginx(8081) 프록시:
   ```bash
   # MagicDNS/HTTPS 인증서 자동. tailnet 내에서 https://<machine>.<tailnet>.ts.net 로 접근
   sudo tailscale serve --bg --https=443 http://127.0.0.1:8081
   # 확인
   tailscale serve status
   ```
3. 내부 사용자는 Tailscale 에 연결된 상태에서 `https://<machine>.<tailnet>.ts.net` 로 접근 →
   internal 로 판별되어 관리자 API·OTP 등록·승인 서명 등 내부망 전용 기능 사용 가능.

## 검증

```bash
# 외부(도메인)에서 /me 호출 → 응답 헤더 X-Network: EXTERNAL 이어야
curl -si https://swunion-server.siyoung.site/api/v1/me -H "Authorization: Bearer <tok>" | grep -i x-network

# 내부(tailnet)에서 /me 호출 → X-Network: INTERNAL 이어야
curl -si https://<machine>.<tailnet>.ts.net/api/v1/me -H "Authorization: Bearer <tok>" | grep -i x-network

# 8080 직접 접근이 외부에서 막혀 있는지(보안그룹) 확인
```

## 주의
- `map $http_upgrade $connection_upgrade` 블록은 http 컨텍스트에 하나만 있어야 한다.
  두 conf 에 중복되면 한쪽에서 제거하거나 `/etc/nginx/conf.d/` 공통 파일로 뺀다.
- SSE 는 양쪽 입구 모두 버퍼링 off 로 설정돼 있다.
- 감사 로그 IP(AuditExportService)는 현재 소켓 주소를 기록하므로 프록시 뒤에선 127.0.0.1 로 남는다.
  실제 클라이언트 IP 기록이 필요하면 X-Forwarded-For 파싱 반영이 별도로 필요(후속).
