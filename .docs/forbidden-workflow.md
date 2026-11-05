# 3.4 금지 작업 API 구현 계약

컬렉션의 3.4 예시를 기반으로 채택한 구현 계약이다. 원본 명세 및 웹쉘의 서명 계약은 아직 미확정이며 아래 내용과 구분한다.

## API와 권한

모든 경로는 `/api/v1` 기준이며 Bearer 인증이 필요하다.

| 메서드·경로 | 권한·망 | 요청 | 성공 |
|---|---|---|---|
| GET `/departments/{deptId}/forbidden-rules` | 모든 로그인 사용자, 전체 망 | `cursor`, `limit` | 200 `{items,nextCursor}` |
| POST `/departments/{deptId}/forbidden-proposals` | 해당 부서 DEPT_MANAGER, 내부망 | `taskType,target,reason` | 201 제안 |
| GET `/admin/forbidden-proposals` | ADMIN, 내부망 | `status`, `cursor`, `limit` | 200 `{items,nextCursor}` |
| POST `/admin/forbidden-proposals/{proposalId}/confirm` | ADMIN, 내부망 | `adminProof` | 200 `{proposalId,status,rule,policyVersion}` |
| POST `/admin/forbidden-proposals/{proposalId}/reject` | ADMIN, 내부망 | `reason` | 200 `{proposalId,status}` |
| POST `/admin/forbidden-rules` | ADMIN, 내부망 | `departmentId,taskType,target,reason,adminProof` | 201 규칙 |
| POST `/admin/forbidden-rules/{ruleId}/release` | ADMIN, 내부망 | `reason`, 선택 `adminProof` | 200 `{ruleId,status}` |

제안과 규칙의 공통 응답 필드는 `id,departmentId,taskType,target,reason,status`다. 반려 사유와 해제 사유는 DB의 별도 필드에 보존한다. 대상은 필수이고 최대 1024자이며 사유는 공백일 수 없다.

목록은 `(createdAt DESC,id DESC)` 순서, limit 기본 20·범위 1~100이다. cursor는 기존 `CreatedAtCursor` 형식이며 다음 페이지가 없으면 nextCursor=null이다. status를 생략하면 모든 제안 상태를 반환한다.

규칙 조회에는 **CONFIRMED와 RELEASE_PENDING 모두 포함**한다. 타 부서 규칙도 로그인 사용자가 조회할 수 있다. 제안은 실제 DB의 사용자 역할과 소속으로 검사하며 ADMIN의 대리 제안은 허용하지 않는다.

## 상태 변경

- 제안은 OPEN으로 생성한다. OPEN만 확정·반려할 수 있다.
- 확정은 제안 값을 복사한 규칙 하나와 새 정책 버전을 생성한다. 반려는 규칙을 생성하지 않는다.
- 직접 추가는 제안 없이 CONFIRMED 규칙과 정책 버전을 생성한다. 확정·직접 추가 전에 규모 프리셋 정책이 있어야 한다. 없으면 404이며 변경은 롤백된다.
- 해제 요청은 CONFIRMED → RELEASE_PENDING으로만 전환한다. **금지는 계속 유효하다.** 실제 해제는 웹쉘 콘솔에서 관리자가 확인해야 한다.
- 해제 요청에 adminProof가 있으면 RELEASE_PENDING 규칙을 포함한 정책 버전을 즉시 생성·배포한다. 없으면 서비스에 요청과 사유만 저장하고 다음 서명된 정책 변경에 포함한다. 서명 없이 웹쉘 정책을 보내지 않는다.
- 이미 처리한 제안·규칙을 다시 변경하면 409 INVALID_STATE다. 대상이 없으면 404 NOT_FOUND다.

## 정책 저장과 배포

정책 변경은 고정 잠금 행(`policy_change_lock`)으로 직렬화한다. 제안 확정·반려는 대상 행도 잠가 중복 규칙 및 상태 덮어쓰기를 방지한다.

전체 정책 payload는 `version,preset,approvalMatrix,forbiddenRules,signingKeys`다. 금지 규칙과 REGISTERED 서명키는 ID 문자열 오름차순으로 배열에 넣는다. 승인 기준표와 후보 배열 순서는 제출 순서를 보존한다. 해시는 **전체 payload**를 JCS 정규화한 UTF-8 바이트의 SHA-256(`sha256:` 접두어)이다. 이전의 프리셋·승인 기준표만 포함하던 근사 해시와 값이 달라진다.

`policy_versions.policy_payload`에 전체 payload와 기존 필드의 adminProof를 보존한다. 과거 버전에는 payload가 null이며 임의로 현재 규칙을 끼워 넣어 재구성하지 않는다. 이후 프리셋·승인 기준표 변경도 동일한 경로로 금지 규칙·서명키를 포함한다.

1. 규칙 변경과 새 버전 스냅샷을 같은 DB 트랜잭션으로 커밋한다.
2. 별도 트랜잭션에서 잠금을 획득하고 최신 버전을 웹쉘 `POST /v1/policies`로 배포한다. body는 `{policy,adminProof}`다.
3. 응답의 `version,policyHash,applied`가 저장값과 일치할 때만 webshellApplied=true로 기록한다.

배포 연결 실패 또는 응답 불일치는 **502 WEBSHELL_UNAVAILABLE**이다. 이때 서비스의 확정·규칙·버전 저장은 이미 커밋되었으며 롤백되지 않는다. 오류 details에는 `policyVersion,policyId,mutationCommitted:true`를 담는다. 같은 확정 요청을 다시 만들지 말고 목록을 재조회한다. 배포 실패는 규칙이 웹쉘에 적용됐다는 뜻이 아니다.

기본 30초 간격으로 최신 미적용 스냅샷을 같은 adminProof로 재전송한다. 프로세스 재시작 후에도 DB에서 읽는다. 이전 버전은 최신 정책으로 대체되면 재전송하지 않아 정책 역행을 막는다. 최신 정책에는 앞선 규칙 변경이 함께 들어간다. 원격 적용 후 DB 기록이 실패할 수도 있으므로 웹쉘은 같은 버전·같은 해시 재전송을 멱등 처리해야 한다.

## 설정과 검증 범위

- 실제 정책 배포는 기존 `WEBSHELL_BASE_URL`, `WEBSHELL_SSL_BUNDLE` 연결 설정을 사용한다. mTLS 운영에서는 HTTPS 주소와 인증서 번들을 함께 설정한다. 미설정 연결은 502다.
- `webshell.policy-stub=true`를 명시한 경우에만 새 정책 배포용 개발 스텁을 사용한다. 기본값은 실제 HTTP 클라이언트다. 테스트 설정은 스텁을 사용한다.
- `webshell.policy-retry-enabled=false`로 자동 재전송을 끌 수 있다. 간격은 `webshell.policy-retry-delay`(밀리초, 기본 30000)다.
- 공개키 등록·폐기도 새 정책 버전을 만들어 같은 정책 배포 흐름으로 웹쉘에 반영한다(`SigningKeyCommandService`). kill-all은 웹쉘 `POST /v1/kill-all`을 실제로 호출한다. 기존 스텁 포트(`WebshellPolicyClient`)는 삭제했다.
- 정책 API의 adminProof는 기존처럼 키 ID·서명 존재만 검사한다. 최근 추가된 공개키 등록용 Ed25519 검증 규칙과는 별개다. 최종 서명 검증은 웹쉘 책임이며 실제 서명 대상과 사전 서명 payload 전달 계약은 추가 합의가 필요하다.
- 웹쉘 RELEASE_PENDING_LOCAL 처리, 실제 mTLS 인증서 연결, 서명 호환성은 실제 웹쉘 환경에서 별도 확인해야 한다. 자동 테스트의 HTTP 응답 모사와 개발 스텁만으로 검증 완료로 간주하지 않는다.

V5는 정책 스냅샷·잠금 행 및 목록용 복합 인덱스를 추가한다. 스키마 검증은 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew test`로 실행한다.
