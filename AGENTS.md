# 작업 안내
- 개인 캐시 실험용 독립 프로젝트다. 원래 팀 저장소의 업무 코드·Git 이력·설정·자격 증명을 복사하지 않는다.
- Java 17, Spring Boot 3.4.5, Gradle 8.14.3. PowerShell: `./gradlew.bat test`, `./gradlew.bat build`.
- 앱 코드: `src/main/java/io/github/dlrkdgml0716/cachelab/`. 측정 도구: `k6/`, `scripts/`.
- 단위 테스트는 Redis·Docker·실제 MySQL을 사용하지 않는다. 서버·부하·장애 실험은 명시적 요청 없이 실행하지 않는다.
- 결과를 만들거나 꾸며내지 않는다. 측정 실패와 stale 0건을 구분한다. 실행 조건·원시 관측·코드 해시를 보존한다.
- 현재 코드에서 직접 생성하고 검증한 결과만 기록한다. 정상 환경 성능과 장애 조건을 구분한다.
- 무효화는 DB 트랜잭션 종료 후 실행한다. Redis·DB 간 원자성이나 강한 정합성을 가정하지 않는다.
- Git commit/push 및 공개는 사용자 지시가 있을 때 수행한다.
