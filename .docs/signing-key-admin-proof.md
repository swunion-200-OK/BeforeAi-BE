# 승인자 서명 키 등록 — 관리자 서명 규칙과 운영 절차

> 대상 API: `POST /api/v1/me/signing-keys`, `POST /api/v1/admin/signing-keys/{keyId}/register`, `DELETE /api/v1/admin/signing-keys/{keyId}`
> 기준 코드: `SigningKeyService`, `SigningKeyAdminService`, `support/Ed25519SignatureVerifier`
> 명세(`.docs/BeforeAI.postman_collection.v2.json`)에 서명 대상·형식이 정해지지 않아 서버에서 임의로 정한 규칙입니다. 웹쉘 계약이 확정되면 맞춰 바꿔야 합니다.

---

## 1. 호환성 변경 (기존 클라이언트 영향)

| API | 이전 | 이후 |
|---|---|---|
| `POST /me/signing-keys` | 패딩 없는 Base64 공개키(43자)도 허용 | **패딩 포함 표준 Base64(44자)만 허용**, 그 외 `400 VALIDATION_ERROR` |
| `POST /admin/signing-keys/{keyId}/register` | `adminProof.signature`가 비어 있지만 않으면 통과 | **실제 Ed25519 서명 검증**. 더미 값(`sig-example` 등)은 `400`/`403` |
| `DELETE /admin/signing-keys/{keyId}` | 본문 없음 | **`REGISTERED` 키는 본문 `{"adminProof": {keyId, signature}}` 필수**(없으면 `400`). `PENDING` 키는 지금처럼 본문 없이 폐기 |

- 프리셋 적용(`PUT /admin/policy/preset`), 승인 기준표 수정(`PUT /admin/approval-matrix`)의 `adminProof`는 **변경 없음**(필드 존재만 확인).
- 같은 키를 다른 표기로 제출해 공개키 전역 unique 검사를 피하지 못하게 하려고 표준 표기만 받습니다. 저장값은 제출한 문자열 그대로입니다.

---

## 2. 등록 API의 관리자 서명 규칙

### 2-1. 요청 본문

```json
{
  "adminProof": {
    "keyId": "<관리자 본인의 signing key ID (signing_keys.id)>",
    "signature": "<64바이트 Ed25519 서명의 Base64>"
  }
}
```

### 2-2. 서명할 내용

UTF-8 문자열 하나에 서명합니다. 줄바꿈·공백을 붙이지 않습니다.

```
beforeai:register-signing-key:{등록할 keyId}:{등록할 publicKey}
```

- `{등록할 keyId}`: 경로의 `keyId` (UUID 소문자 하이픈 표기)
- `{등록할 publicKey}`: 등록 대상 키의 `publicKey` 값 그대로 (`GET /admin/signing-keys` 응답의 값)
- 공개키까지 서명에 넣으므로 관리자가 확인한 그 키만 등록됩니다.

### 2-3. 서명 키 조건

| 조건 | 허용 |
|---|---|
| 요청한 관리자 **본인** 소유이고 상태가 `REGISTERED`인 키 | ✅ |
| 관리자 본인 소유의 `PENDING` 키를 **그 키 자신의 등록**에 사용 (첫 관리자 키, 3장) | ✅ |
| 다른 사용자 소유 키 (그 키 자신의 등록이라도) | ❌ `403 KEY_NOT_REGISTERED` |
| 본인 소유지만 `PENDING`/`REVOKED`인 키로 다른 키 등록 | ❌ `403 KEY_NOT_REGISTERED` |

### 2-4. 처리 순서와 오류 응답

검증 순서: adminProof 필드 확인 → 대상 키 조회 → 대상 상태(PENDING) 확인 → 서명 키 조건 → 서명 검증 → `REGISTERED` + 새 정책 버전 저장(커밋) → 웹쉘에 정책 묶음 배포

웹쉘 반영은 공개키를 따로 보내지 않고, 등록된 공개키 전체가 들어간 정책 묶음(`POST /v1/policies`)으로 합니다(3.4 정책 배포와 같은 흐름). 응답의 `policyVersion`은 새로 만든 정책 버전입니다. 아직 정책이 하나도 없으면(규모 프리셋 적용 전) 키만 `REGISTERED`가 되고 `policyVersion`은 `0`이며, 이후 첫 정책 버전에 함께 들어갑니다.

| 상황 | 응답 |
|---|---|
| `adminProof` 누락, `keyId`/`signature` 누락 | `400 VALIDATION_ERROR` |
| 대상 키 없음 | `404 NOT_FOUND` |
| 대상 키가 `PENDING`이 아님 | `409 INVALID_STATE` |
| 서명 키가 없거나 2-3 조건 위반 | `403 KEY_NOT_REGISTERED` |
| 서명이 Base64가 아니거나 64바이트가 아님 | `400 VALIDATION_ERROR` |
| 형식은 맞지만 서명 불일치 (다른 내용·다른 개인키) | `403 FORBIDDEN_ROLE` |
| 웹쉘 반영 실패 | `502 WEBSHELL_UNAVAILABLE` (`details.mutationCommitted=true`) |

웹쉘 반영 실패를 뺀 오류에서는 대상 키 상태가 바뀌지 않습니다. 웹쉘 반영 실패는 키 상태와 정책 버전이 이미 저장된 뒤이므로 `REGISTERED`로 남고, 정책 재전송(기본 30초)이 다시 배포합니다. 서명 불일치를 `401`이 아닌 `403`으로 둔 이유는 토큰은 유효하므로 프론트가 로그아웃으로 처리하지 않게 하기 위해서입니다.

### 2-5. 관리자 기기에서 서명 만들기

```bash
KEY_ID=<등록할 keyId>
PUBKEY=<등록할 publicKey>

printf '%s' "beforeai:register-signing-key:$KEY_ID:$PUBKEY" > /tmp/register-msg
SIGNATURE=$(openssl pkeyutl -sign -inkey ~/.beforeai/signing_priv.pem -rawin -in /tmp/register-msg | base64 | tr -d '\n')
echo "$SIGNATURE"   # 88자
```

- **OpenSSL 3.0 이상**이 필요합니다. macOS 기본 `/usr/bin/openssl`(LibreSSL)은 Ed25519를 지원하지 않으므로 Homebrew `openssl@3` 등을 사용합니다.
- Linux `base64`는 76자마다 줄을 바꾸므로 `tr -d '\n'`이 필요합니다.
- 메시지 파일에 줄바꿈이 들어가면(`echo` 사용 등) 서명이 맞지 않습니다. `printf '%s'`를 씁니다.

---

## 3. 운영 절차

### 3-1. 배포 직후 첫 관리자 키 등록 (부트스트랩)

처음에는 `REGISTERED` 관리자 키가 없어 다른 키를 등록할 수 없으므로, 관리자가 자기 키를 자기 키로 서명해 등록합니다.

1. 관리자 PC에서 키페어 생성
   ```bash
   mkdir -p ~/.beforeai
   openssl genpkey -algorithm Ed25519 -out ~/.beforeai/signing_priv.pem
   chmod 600 ~/.beforeai/signing_priv.pem
   PUBKEY=$(openssl pkey -in ~/.beforeai/signing_priv.pem -pubout -outform DER | tail -c 32 | base64)
   ```
2. 내부망에서 관리자 토큰으로 공개키 제출 → 응답의 `id`를 `KEY_ID`로 기록
   ```
   POST /api/v1/me/signing-keys
   { "algorithm": "Ed25519", "publicKey": "$PUBKEY", "deviceName": "admin-pc" }
   ```
3. 2-5 방법으로 그 키 자신에 대한 서명 생성
4. 등록 요청 (`adminProof.keyId`와 경로 `keyId`가 같음)
   ```
   POST /api/v1/admin/signing-keys/$KEY_ID/register
   { "adminProof": { "keyId": "$KEY_ID", "signature": "$SIGNATURE" } }
   ```
5. `GET /api/v1/me/signing-keys`에서 `status: REGISTERED` 확인

### 3-2. 승인자 키 등록 (일반)

1. 승인자가 자기 PC에서 키페어 생성 후 `POST /me/signing-keys` 제출 (내부망)
2. 관리자가 `GET /admin/signing-keys`에서 대상 키의 `id`·`publicKey`·`userId` 확인
3. 관리자가 **본인의 REGISTERED 키**로 2-2 문자열에 서명
4. `POST /admin/signing-keys/{keyId}/register` 호출 (`adminProof.keyId` = 관리자 키 ID)

### 3-3. 기기 교체

사용자당 폐기되지 않은 키는 1개뿐이므로 이전 키를 먼저 폐기합니다.

1. 관리자가 `DELETE /admin/signing-keys/{이전 keyId}` → `204`
   - `REGISTERED` 키는 본문에 `adminProof`가 필요하며, 키를 뺀 새 정책 버전이 웹쉘에 배포됩니다. 정책의 adminProof처럼 키 ID·서명이 있는지만 확인합니다.
   - `PENDING` 키는 웹쉘 정책에 들어간 적이 없으므로 본문 없이 바로 `REVOKED`가 됩니다.
2. 새 PC에서 3-2 절차 반복

### 3-4. 관리자 본인 키를 바꿀 때

관리자 키를 폐기하면 그 관리자는 다른 키를 등록할 수 없게 됩니다. 이때는 새 키를 3-1 절차(자기 서명)로 다시 등록합니다. 다른 관리자가 있으면 3-2 절차로 등록해도 됩니다.

---

## 4. 이번 범위에서 다루지 않은 것

- 정책 묶음의 adminProof 서명 대상: 키 등록 때는 2-2 등록 서명을 그 정책 버전의 adminProof로 함께 보관·전달합니다. 웹쉘 `/v1/policies`가 adminProof를 정책 내용 기준으로 검증한다면 서명 대상을 웹쉘과 맞춰야 합니다.
- 정책 API(프리셋·승인 기준표)의 adminProof 실제 검증: 서명 대상인 정책 해시를 클라이언트가 재현할 방법(JCS 정식 구현 또는 서명 대상 조회 API)이 필요합니다.
- 웹쉘 반영 성공 후 DB 커밋 실패 시 정합성 보정.
