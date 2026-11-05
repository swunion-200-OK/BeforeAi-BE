# 웹쉘 콜백 mTLS 설정 (대표 웹쉘 1대)

앱 앞의 Nginx는 앱에 http로 붙으므로 클라이언트 인증서가 앱까지 오지 않습니다. 그래서 웹쉘 콜백은 앱의 전용 TLS 포트로 직접 받습니다. 이 포트는 tailnet 주소에만 열고, `/api/v1/internal/**` 외의 경로는 404로 막습니다.

개인키와 실제 주소는 이 저장소에 커밋하지 않습니다. 아래 `<...>`는 서버에서 채우는 값입니다.

## 인증서

CA 1개를 양쪽이 함께 신뢰합니다.

| 인증서 | CN | 쓰는 곳 |
| --- | --- | --- |
| 콜백 서버 인증서 | 임의. SAN에 웹쉘이 접속할 서버의 tailnet 이름/IP 포함 | 서버 콜백 포트 |
| 웹쉘 클라이언트 인증서 | `beforeai-webshell` | 웹쉘 → 서버 콜백 |
| 백엔드 클라이언트 인증서 | `beforeai-backend` | 서버 → 웹쉘 호출 |

## 서버 환경 변수

```bash
# 콜백 수신
WEBSHELL_CALLBACK_TLS_ENABLED=true
WEBSHELL_CALLBACK_TLS_PORT=8443
WEBSHELL_CALLBACK_TLS_ADDRESS=<서버 tailnet IP>
WEBSHELL_CALLBACK_TLS_CERTIFICATE=<서버 인증서 PEM 경로>
WEBSHELL_CALLBACK_TLS_PRIVATE_KEY=<서버 개인키 PEM 경로, 암호 없는 PKCS#8>
WEBSHELL_CALLBACK_TLS_CLIENT_CA=<CA 인증서 PEM 경로>
WEBSHELL_CALLBACK_CLIENT_CN=beforeai-webshell

# 웹쉘 호출
WEBSHELL_BASE_URL=https://<웹쉘 tailnet 주소>:<포트>
WEBSHELL_SSL_BUNDLE=webshell
SPRING_SSL_BUNDLE_PEM_WEBSHELL_KEYSTORE_CERTIFICATE=file:<backend 인증서 PEM 경로>
SPRING_SSL_BUNDLE_PEM_WEBSHELL_KEYSTORE_PRIVATE_KEY=file:<backend 개인키 PEM 경로>
SPRING_SSL_BUNDLE_PEM_WEBSHELL_TRUSTSTORE_CERTIFICATE=file:<CA 인증서 PEM 경로>
```

`WEBSHELL_CALLBACK_TLS_ENABLED=true`인데 주소나 파일 경로가 비어 있으면 앱이 기동하지 않습니다. 앱을 시작할 때 tailnet 주소가 없으면 포트 바인딩에 실패하므로 `tailscaled`가 먼저 떠 있어야 합니다.

## 웹쉘 쪽 설정

- 콜백 주소: `https://<서버 tailnet 주소>:8443/api/v1/internal/executions/{executionId}/events`
- 콜백 클라이언트 인증서: CN `beforeai-webshell`, 서버 인증서 검증에 공통 CA 사용
- 서버에서 오는 요청의 클라이언트 인증서: CN `beforeai-backend`를 공통 CA로 검증
- 웹쉘 설정 항목 이름은 웹쉘 저장소를 따릅니다.

## 확인

```bash
# 인증서 없이: TLS 핸드셰이크 실패가 정상
curl --cacert <CA> https://<서버 tailnet 주소>:8443/api/v1/internal/executions/00000000-0000-0000-0000-000000000000/events
# 웹쉘 인증서로 (본문 없음): 400이면 인증 통과. 401이면 CN 불일치, 403이면 출발지가 INTERNAL_CIDRS 밖
curl --cacert <CA> --cert <webshell 인증서> --key <webshell 개인키> -X POST -H 'Content-Type: application/json' \
  https://<서버 tailnet 주소>:8443/api/v1/internal/executions/00000000-0000-0000-0000-000000000000/events
```
