# 커밋 후 무효화 콜백 검증 (2026-10-13)

ItemService.touch()를 @Transactional로 변경하고, 트랜잭션 안에서 캐시 무효화를 요청한다.
HybridCache는 트랜잭션과 동기화가 활성 상태이면 afterCommit 콜백에 실제 무효화를 등록한다.
트랜잭션이 없으면 즉시 실행한다. 콜백은 별도 스레드·영속 메시지 큐가 아니다.

## 실행 결과

- 전체 17개 중 16개 통과, 실제 Redis가 필요한 통합 테스트 1개 조건부 생략.
- bootJar 성공. 전체 테스트를 --rerun-tasks로 다시 실행했다.
- ItemServiceTest 6개: 독립 쓰기 커밋, 외부 트랜잭션 커밋 대기, 외부 예외 롤백,
  rollbackOnly, 트랜잭션 없는 즉시 무효화, 실패한 쓰기의 무효화 없음.
- Spring 캐시·트랜잭션 프록시, 실제 HybridCache, H2와 DataSourceTransactionManager를 사용한다.
- Redis는 mock이다. L2 삭제 Lua 실행과 Pub/Sub 발행 호출을 관찰한다.
- 커밋 전 L1 값 유지·삭제/발행 없음, 커밋 후 삭제/발행·새 값 재조회,
  롤백 후 DB 값과 L1 값 유지·삭제/발행/커밋 계측 없음까지 검증한다.
- 삭제 Lua가 호출되는 순간 별도 H2 연결로 커밋된 DB 값을 확인한다.
- 기존 Spring 캐시 프록시를 통한 DB→L2, L2→L1 경쟁 테스트도 다시 통과했다.

## 명령

PowerShell에서 JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true를 설정한 후 실행했다.

    .\gradlew.bat --no-daemon --gradle-user-home C:\Users\rkdgl\.gradle '-Djava.net.preferIPv4Stack=true' -I scripts/gradle-test-ipv4.gradle test bootJar --rerun-tasks

JUnit XML, Gradle 출력과 소스/검증 산출물 SHA-256은 이 폴더와 manifest.json에 보관한다.
PowerShell이 stderr를 NativeCommandError로 출력한 부분이 있지만 Gradle 종료 코드는 0이고 빌드는 성공했다.

## 해석 범위

공통 캐시가 커밋 시점을 책임지는 처리 구조를 검증했다. 원본 서비스의 업무 코드나 설정을 복사하지 않았다.
공개 실험의 데이터는 독립 Item이며 원본 애플리케이션 전체와 동일한 성능을 입증하는 것은 아니다.
실제 Redis Lua/Pub/Sub, MySQL 격리 수준, 다중 프로세스, k6 부하·장애 실험은 이번에 실행하지 않았다.
DB 커밋과 Redis 작업은 원자적이지 않다. 프로세스 종료·Redis 실패·Pub/Sub 유실의 한계는 유지된다.
과거 실험과 이전 verification 폴더는 해당 당시 구현의 기록이며 이번 코드 결과로 대체 해석하지 않는다.
