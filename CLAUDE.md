# CLAUDE.md

이 저장소의 공통 에이전트 가이드는 `AGENT.md`에 있습니다. 아래 import로 함께 읽습니다.

@AGENT.md

## Claude Code 전용 메모

- 응답과 커밋 메시지, 코드 주석은 한국어를 기본으로 합니다.
- 엔티티나 스키마를 바꿀 때는 엔티티 매핑과 Flyway 마이그레이션을 한 번에 함께 수정하고, `./gradlew test`로 `ddl-auto=validate` 검증까지 확인합니다.
- 테스트는 Testcontainers를 쓰므로 Docker가 실행 중이어야 합니다. Docker가 없어 테스트를 돌리지 못했다면 그 사실을 그대로 보고합니다.
- `.env` 파일은 읽거나 출력하지 않습니다. 접속 정보가 필요하면 환경변수 이름(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)만 언급합니다.
- API를 구현할 때는 `.docs/BeforeAI.postman_collection.v2.json`의 해당 엔드포인트 설명과 예시 응답을 먼저 확인합니다.
