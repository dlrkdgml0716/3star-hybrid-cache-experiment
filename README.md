# Caffeine·Redis 하이브리드 캐시 실험

팀 캡스톤 프로젝트 종료 후 개인적으로 탐구한 **다중 인스턴스의 L1 캐시 정합성 문제**를 설명하기 위한 독립 실험 프로젝트입니다. 원래 팀 프로젝트의 업무 코드, Git 이력, 환경 파일, 사용자 데이터는 포함하지 않습니다. 단순한 테스트 도메인에서 캐시 동작과 측정 방법을 확인할 수 있도록 구성했습니다.

단일 키 구성에서 정상 부하 3회와 구독 중단 1회를 실행했습니다. 측정 조건과 원시 결과는 아래에 정리했습니다.

## 목적과 범위

- 같은 앱을 두 인스턴스로 실행해 Caffeine L1 → Redis L2 → MySQL 조회 구조를 관찰합니다.
- 데이터 수정 후 Redis L2를 제거하고 Pub/Sub으로 각 인스턴스의 L1을 무효화합니다.
- k6로 목표 RPS와 읽기/쓰기 비율을 설정하고 실제 HTTP RPS, 실패율, dropped iterations를 기록합니다.
- stale read, 메시지 발행 → 수신 지연, 구독 중단 중 수신하지 못한 메시지와 재연결 후 stale 잔존을 관찰합니다.
- 핵심 목적은 Pub/Sub 무효화가 적용된 두 인스턴스에서 정상 부하 중 stale read와 메시지 전달을 관찰하고, 구독 중단 시 옛 L1 값이 얼마나 오래 남는지 확인하는 것입니다. 무효화가 없는 구성과의 개선폭은 측정하지 않습니다.

정상적으로 연결된 구독자가 무효화 메시지를 수신하면 해당 L1 항목을 제거하고, 이후 조회는 L2 또는 DB 경로를 다시 거칩니다. 다만 DB 커밋 후 다른 인스턴스의 L1 무효화가 완료되기까지 불일치 구간이 있을 수 있고, 조회와 캐시 적재가 무효화와 겹칠 수도 있습니다. 구독이 끊긴 동안의 Pub/Sub 메시지는 재전달되지 않습니다. 관찰 범위는 **이 구현의 정상 부하 동작과 구독 중단 상황**이며, 모든 읽기가 즉시 최신 값을 반환한다는 보장은 아닙니다.

Java 17, Spring Boot 3.4.5, Gradle 8.14.3을 사용합니다. Caffeine, Spring Data Redis, MySQL JDBC의 버전은 Spring Boot 3.4.5의 의존성 관리 설정을 따릅니다. 이번 빌드의 JAR에서 확인한 버전은 각각 3.1.8, 3.4.5, 9.1.0입니다. Redis 7, MySQL 8.0 컨테이너를 사용하며, k6 버전은 실행 기록에 남깁니다.

## 구조와 동작

```text
HTTP → LabController → ItemService → HybridCache
                                     ├─ L1 Caffeine
                                     ├─ L2 Redis → L1 적재
                                     └─ ItemRepository → MySQL → L1/L2 적재

app-1의 touch(쓰기) → ID 1의 MySQL version 증가 → 트랜잭션 commit 완료
      → app-1의 L1 제거 → Redis L2 제거 → 무효화 메시지 발행
      → app-2가 메시지를 받아 ID 1의 L1 제거
```

초기 데이터는 ID 1 한 건이며 쓰기마다 정수 version을 1 증가시킵니다. app-1은 ID 1을 수정하고 app-2는 같은 ID를 읽습니다. 정상 부하의 stale 판정은 모든 쓰기의 커밋 시점 구간과 모든 읽기의 캐시 조회 구간·반환 version을 대조합니다. 별도 stale probe는 자신의 쓰기 version을 기준으로 하므로 동시 k6 쓰기와 혼합하지 않습니다. 무효화 메시지의 발행·수신 timestamp 차이는 별도로 메시지 전달 지연을 측정하는 데 사용합니다. 테스트 도메인은 매장·회원·주문 도메인과 독립적입니다.

| 위치 | 역할 |
|---|---|
| `src/main/java/.../item/` | 테스트 데이터 조회·version 증가, 명시적인 트랜잭션 |
| `src/main/java/.../cache/` | L1/L2, Pub/Sub, 메시지 ID·발행/수신 시각 기록 |
| `src/main/java/.../api/` | 조회·수정·실험 제어 API |
| `src/test/` | Redis 모킹 및 별도 H2를 사용하는 단위 테스트 |
| `k6/load.js` | RPS 부하 생성, 실측 요청 수·비율·지연·실패 기록 |
| `scripts/` | PowerShell 실험 도구와 결과 집계 |
| `compose.yaml`, `infra/init.sql` | 별도 MySQL·Redis, 앱 두 개, 합성 데이터 |
| `results/` | 결과 보관 안내. 기존 결과를 가져오지 않음 |

## 빌드와 실행

PowerShell에서 이 폴더로 이동합니다. Java 17, Docker Compose, k6가 필요합니다. macOS/Linux 빌드는 `./gradlew`; 실험 스크립트는 PowerShell 7의 `pwsh`로 실행할 수 있습니다.

```powershell
.\gradlew.bat test
.\gradlew.bat build
docker compose up -d --build
Invoke-RestMethod http://127.0.0.1:8084/actuator/health
Invoke-RestMethod http://127.0.0.1:18084/actuator/health
```

Compose의 DB 계정 정보는 로컬 실험용 예시값입니다. 기존 프로젝트의 DB·Redis에 연결하지 마세요. 앱 포트는 호스트의 127.0.0.1에만 바인딩하며 DB·Redis 포트는 호스트에 노출하지 않습니다.

초기 SQL은 **새 MySQL 볼륨을 처음 만들 때만** 실행되어 ID 1 한 건을 생성합니다. 이후에는 `mysql-single-key-data` 볼륨의 DB 데이터와 version이 유지되며, `docker compose down`도 볼륨을 삭제하지 않습니다. 이 실험은 팀 프로젝트와 별도의 Compose 환경과 볼륨을 사용합니다.

## API

| 메서드·경로 | 기능 |
|---|---|
| GET `/items/1` | L1/L2를 사용하는 읽기. 실험의 읽기 요청은 app-2로 전송 |
| GET `/items/1/db` | DB 직접 읽기. 참조값 확인에 사용 |
| POST `/items/1/touch` | version 1 증가 후 캐시 무효화. 실험의 쓰기 요청은 app-1로 전송 |
| GET `/experiment/metrics` | 프로세스 식별자, TTL, 카운터, 보관된 메시지 이벤트 |
| GET `/experiment/measurements` | 쓰기 커밋·읽기 조회 구간 및 version 이벤트 스냅샷 |
| POST `/experiment/subscription?enabled=false/true` | 앱 프로세스와 L1 캐시는 유지하고 Redis Pub/Sub 메시지 수신만 중지·재개 |
| DELETE `/experiment/cache` | 호출 인스턴스 L1과 ID 1의 L2 항목만 제거 |

인증이 없는 **로컬 실험 전용 API**입니다. 구독 제어·데이터 수정 API를 운영 서비스에 노출하는 설계가 아닙니다.

## 실험

서로 독립적인 실험은 동시에 실행하지 않습니다. 정상 부하와 별도 stale probe도 동시에 실행하지 않습니다. 각 실험을 시작하기 전에 두 인스턴스의 `subscriberListening`이 true인지 확인합니다. 구독 중단 실험은 그 확인 이후 reader의 구독만 끊습니다. 정상 부하 스크립트는 캐시나 DB를 초기화하지 않으므로 직전 실행 상태가 히트율 등에 영향을 줄 수 있습니다.

### 정상 부하

```powershell
.\scripts\Invoke-Load.ps1 -Rps 100 -WriteRatio 0.1 -Duration 30s
```

목표 RPS는 k6 iteration/s이고 iteration당 HTTP 요청은 하나입니다. 실측 HTTP RPS에는 실패 요청 및 종료 대기 영향이 포함될 수 있으며, 성공 처리량·DB 처리량과 다릅니다. 목표 미달, 오류, dropped iterations는 결과와 함께 해석해야 합니다. 읽기/쓰기 선택은 확률적이므로 실측 비율도 저장합니다.

정상 부하 중 app-1의 모든 쓰기는 version과 DB 커밋 전·후 시각을, app-2의 모든 읽기는 반환 version·L1/L2/DB 출처·캐시 조회 시작·끝 시각을 기록합니다. 읽기 시작 전에 더 높은 version의 커밋 완료가 확인되면 `stale`, 다음 version의 커밋이 읽기 구간과 겹칠 수 있으면 `uncertain`, 그 외에는 `fresh`로 분류합니다. 부하 전 DB version과 부하 후 DB version, k6의 읽기·쓰기 요청 수, 이벤트 수가 일치해야 집계를 인정합니다. `stale-summary.json`은 개별 판정과 분모를 함께 보관합니다. 조회 직전에 reader L1을 한 번 준비하며, DB version과 캐시 상태는 리셋하지 않습니다.

이 저장소에서 공개한 정상 부하 실험은 목표 **100 RPS·30초·쓰기 선택 확률 10%** 조건만 사용합니다. 이 조건의 기본 VU 설정은 `preAllocatedVUs=50`, `maxVUs=200`이며, 공개된 세 실행 모두 목표 요청률에 도달했고 dropped iteration은 0건이었습니다. `k6-summary.json`에는 적용값·실측 RPS·dropped iterations·HTTP 실패율이 남습니다. 다른 RPS에서의 성능은 측정하지 않았습니다.

한 DB 행에 쓰기가 집중되므로 목표 RPS가 미달할 수 있습니다. 실행 스크립트는 누락 0건, HTTP 실패 0건, 실측 HTTP RPS가 목표의 95% 이상인 경우에만 부하 검증을 통과시키고, 실패한 실행도 `results/runs/`에 기록합니다. 이 기준은 부하 생성 확인용이며 캐시 성능·정합성 보증이 아닙니다.

스크립트 변수는 `LAB_*`입니다. k6의 `-e`는 스크립트 변수이며, OS의 네이티브 `K6_*` 옵션과 구분합니다. 알려진 실행 모드 변경 환경변수를 거부하고 실제 executor/rate도 검사합니다.

### Stale read와 폴링

```powershell
.\scripts\Invoke-StaleProbe.ps1 -Samples 100 -DelaysMs 0,5,10,20,50,100
# 동일 관측 후 최신 값 반환까지의 HTTP 폴링도 기록
.\scripts\Invoke-StaleProbe.ps1 -Samples 100 -Polling
```

관측 순서는 **reader 캐시 준비 → writer 쓰기 응답 → Δ 대기 → reader 한 번 조회**입니다. `observed.version < written.version`이면 stale입니다. 분모는 해당 Δ에서 성공적으로 관측한 읽기 수이며 시도 수·오류 수도 별도 기록합니다. 관측이 없으면 비율은 null입니다.

t0는 쓰기 HTTP 응답을 수신하고 역직렬화한 뒤입니다. actual dispatch delay와 GET RTT를 저장하며, Δ는 서버 캐시 조회 시각을 뜻하지 않습니다. PowerShell 타이머·HTTP 지연 때문에 매우 짧은 불일치 창을 놓칠 수 있습니다. **0건은 완전한 정합성의 증명이 아닙니다.** Δ별 실행 순서·캐시 상태도 결과에 영향을 줍니다.

이 probe는 별도의 단독 실험으로 실행합니다. 다른 쓰기와 섞이면 자신의 쓰기 version만 기준으로 한 결과를 전체 stale 비율로 해석할 수 없습니다. 정상 부하에서 반환된 모든 읽기의 판정은 위의 서버 이벤트 집계를 사용합니다.

폴링 elapsed는 쓰기 응답 이후 최신 값을 반환받는 데 걸린 시간입니다. HTTP·대기·클라이언트 비용이 포함되어 **Pub/Sub 메시지 전송 지연과 다른 지표**입니다. timeout·조회 실패도 원시 기록에 남깁니다.

### Subscriber 중단

```powershell
.\scripts\Invoke-SubscriberFailure.ps1 -DowntimeSeconds 10
# 같은 방법으로 30/60초 조건도 개별 실행
```

reader 앱 프로세스와 Caffeine L1 캐시는 그대로 두고 Redis Pub/Sub 메시지를 받는 Spring 리스너만 중지합니다. Redis L2 접근과 HTTP 요청 처리는 계속됩니다. 앱 프로세스를 종료하거나 네트워크 전체를 끊는 실험과는 다릅니다.

- 캐시 준비 후 구독 중단을 확인하고 k6 부하를 실행합니다.
- writer가 발행 성공으로 기록한 메시지 ID와 reader의 수신 ID를 비교합니다.
- 비율은 **계측 창의 발행 ID 중 그 창에서 수신하지 않은 ID 비율**입니다.
- 시간 계측은 구독 중단 응답을 받은 뒤 시작해 재연결 확인 때 끝납니다. 이 구간에는 스냅샷·k6 준비·종료·재연결 확인이 포함되며, 리스너가 실제로 연결을 끊기 시작한 순간부터의 정확한 단절 시간은 아닙니다.
- 부하 종료 후 구독을 복구하고 DB 참조 version과 reader 반환 version을 비교합니다. 이후 쓰기를 멈춰 둡니다.
- 복구 후 보관된 이벤트에서 누락 ID 수신 여부도 확인합니다. 관측 기간 밖에서 영원히 재전달되지 않는다는 실험 증명은 아닙니다.

재시작, 이벤트 버퍼 초과, 관측 실패, 발행 0건은 정상 0% 결과로 대체하지 않습니다. 구독 복구 실패 시 스크립트가 복구를 재시도하며 실패 경고와 수동 복구 경로를 제공합니다.

## 결과 기록과 해석

매 실행은 `results/runs/<UTC 시각>-<고유 ID>/`에 저장합니다.

- `manifest.json`: 실행 조건·환경·소스 SHA-256, 결과 파일 경로와 해시, 종료 상태.
- 원시 카운터·메시지 이벤트 스냅샷과 k6 로그·요약.
- `error-summary.json`: 두 인스턴스의 캐시 Redis 경로 처리 오류(get/put/delete/publish), 잘못된 무효화 메시지, 음수 시각 차이의 실행 전후 카운터 차이. get/put/publish 오류에는 직렬화·역직렬화 실패도 포함될 수 있습니다. 하나라도 증가하면 실행을 실패로 표시합니다.
- 정상 부하의 각 읽기·쓰기 version 및 시각 구간과 stale/fresh/uncertain 판정. 단독 probe는 reference/observed version, 오류·RTT·실제 dispatch 지연.
- 메시지 delay는 **발행 직전 wall clock timestamp → 원격 listener 진입**입니다. L1 제거 완료 시각도 별도 기록합니다. DB commit→최신 읽기 지연과 다릅니다.
- mean/p95는 해당 writer의 실행 창에서 발행한 ID와 매칭된 원격 수신 표본만으로 계산합니다. p95는 nearest-rank이며 시간창이 다른 lifetime 평균을 빼서 만들지 않습니다.
- 메시지 미수신 표본은 delay 계산에서 제외되므로 표본 수·유실 지표와 함께 읽어야 합니다.

원시 실행 폴더는 기본적으로 Git에서 제외합니다. 공개할 실행을 선별해 `results/published/<run-id>/`로 **전체 실행 묶음**을 복사하고 실행 환경·반복 횟수·알려진 한계를 설명합니다. 결과 한 개만 떼어 기존 조건의 결과라고 표시하지 않습니다. 오래된 실험 자료와 새 구현의 결과를 혼합하지 않습니다.

## 관측 결과 (2026-10-09)

로컬 Windows PC의 Docker Desktop(할당 12 CPU·약 8 GiB 메모리)에서 app 2개·MySQL 8.0·Redis 7을 실행하고, 호스트의 k6 1.6.1로 부하를 주었습니다. 아래 3회는 각각 목표 100 iteration/s, 쓰기 선택 확률 10%, 30초이며, 동일 앱 프로세스를 순서대로 사용했습니다. 실제 쓰기 건수는 확률적 선택 때문에 다릅니다. 각 실행에서 `dropped_iterations=0`, HTTP 실패 0, 계측한 캐시 처리·listener·시계 오류 0, 이벤트·DB version·k6 요청 수 일치를 확인했습니다.

| 실행 | 실측 HTTP RPS | 읽기 / 쓰기 | 확정 stale | 판정 불확실 | 확정 stale / 전체 읽기 | 발행→수신 p95 |
|---|---:|---:|---:|---:|---:|---:|
| [1회](results/published/20261009T070225028Z-65f7c2e2/manifest.json) | 100.00 | 2,701 / 300 | 303 | 638 | 11.22% | 10.36 ms |
| [2회](results/published/20261009T070531143Z-e0ca5b39/manifest.json) | 99.98 | 2,733 / 268 | 164 | 470 | 6.00% | 6.26 ms |
| [3회](results/published/20261009T070618929Z-257b2bda/manifest.json) | 99.99 | 2,667 / 334 | 225 | 574 | 8.44% | 5.76 ms |

`확정 stale`은 기록된 시각을 기준으로 더 높은 version의 커밋 완료가 읽기 시작보다 앞선 경우입니다. 커밋·읽기 구간이 겹칠 수 있는 표본은 `판정 불확실`로 뺐습니다. 따라서 11.22% 등은 **전체 읽기 중 이 규칙으로 확정 판정한 stale의 비율**이지 실제 전체 stale 발생률의 정확한 추정치는 아닙니다. 불확실 표본이 전부 stale이었다고 가정한 상한은 각 실행에서 34.84%, 23.20%, 29.96%입니다. 두 JVM의 시계 차이를 별도로 측정하지 않아 경계에 가까운 판정에는 오차 가능성이 있습니다. 원시 읽기별 판정은 각 `stale-summary.json`에 있습니다. 반복 간 차이가 있어 단일한 발생률로 일반화하지 않습니다.

세 실행의 확정 stale 692건 중 691건은 L1, 1건은 L2에서 반환된 값입니다. 정상 연결 상태에서 발행된 무효화 메시지는 실행별 300/300, 268/268, 334/334건이 원격 listener 진입 이벤트와 매칭됐습니다. 이는 해당 실행 창의 관측 결과이며 모든 상황의 전달 보증을 뜻하지 않습니다.

별도의 [10초 구독 중단 실행](results/published/20261009T070711374Z-1695e3ed/manifest.json)은 목표 100 RPS·쓰기 선택 확률 30%였습니다. 실측 99.78 RPS, 드롭·HTTP 실패·계측 오류 0이었습니다. 구독 중단 응답부터 재연결 확인까지의 계측 구간은 약 13.62초였습니다. 그 창에서 writer가 발행 성공으로 기록한 301개 메시지 ID 중 reader 수신 기록은 0개였고, 복구 후 관측 기간에도 해당 ID의 재수신은 0개였습니다. 복구 후 옛 version이 86회 반환됐으며, DB 기준값을 확인한 뒤 약 46.30초에 최신 version을 처음 관측했습니다. 복구 후 관측에서는 L1 hit 86회, L2 hit 0회, DB load 1회였습니다. 두 계측 시간을 합치면 약 59.92초로 L1의 60초 TTL과 일치하는 양상입니다. 다만 L1 적재 시각부터 잰 정확한 만료 시간은 아니고 마지막 stale과 첫 fresh 관측 사이는 약 536ms입니다. 다른 환경의 보편적인 유실률이나 복구 시간을 뜻하지 않습니다.

## 한계

- cache-aside 조회와 갱신은 원자적이지 않습니다. 무효화 후 진행 중이던 읽기가 낡은 값을 다시 적재할 수 있으며 이를 막는 version fence는 구현하지 않았습니다.
- DB commit과 Redis 삭제·발행 사이에는 장애 창이 있습니다. DB·Redis를 묶는 분산 트랜잭션은 없습니다.
- Redis 조회 경로 처리 실패 시 DB 조회로 우회하지만 L2 제거·발행 실패는 낡은 값을 남길 수 있습니다. 이번 공개 실행은 `error-summary.json`에서 계측한 오류가 0건임을 확인했습니다. 계측 범위 밖의 모든 장애가 없었다는 뜻은 아닙니다.
- L1 60초·L2 120초는 각 항목 적재 시점의 TTL입니다. 낡은 L2에서 L1을 다시 채우면 stale이 L2 TTL 이후에도 남을 수 있어 120초를 전체 정합성 상한이라고 주장하지 않습니다.
- 메시지 시각 차이는 호스트 시계에 의존합니다. 다른 호스트는 시계 동기화가 필요하며 음수 지연은 오류 지표와 원시 이벤트로 남깁니다.
- 발행 명령 성공은 수신·처리 성공이 아닙니다. Redis가 반환하는 subscriber count도 수신 확인 응답이 아닙니다.
- 이벤트는 인스턴스별 제한된 메모리에 보관합니다. overflow·재시작이 있으면 ID 기반 집계와 정상 부하 stale 집계는 실패 처리합니다. 읽기별 기록은 부하에 영향을 줄 수 있으며, 큰 RPS에서 계측 오버헤드는 아직 보정하지 않았습니다.
- 두 JVM의 wall clock으로 읽기·커밋 구간을 대조하므로 시계 오차·조정이 크면 경계 판정이 불안정합니다. 이 실험은 같은 호스트의 Compose 컨테이너를 전제로 하며 `uncertain` 건수도 함께 보고합니다.
- ID 1에 읽기·쓰기가 집중된 단일 PC 실험입니다. 이 결과를 다른 키 분포·워크로드·규모로 일반화하지 않습니다.
- 캐시 없음 대비 응답시간·처리량 개선폭은 비교 실행 전까지 주장하지 않습니다.

## 검증 상태

2026-10-09 확인:
- `gradlew.bat test`, `scripts/Verify-Measurements.ps1`, Docker 빌드와 앱 기동: 통과.
- 100 RPS·쓰기 비율 목표 10%·30초 정상 부하 3회와 10초 구독 중단 1회: 유효성 기준 통과. 각 실행의 원시 파일과 해시는 `results/published/`에 보관.
- 같은 조건의 반복 중 Windows 호스트 k6에서 Winsock `10014` 오류로 요청이 1건씩 실패한 정상 부하 2회는 유효성 기준에서 탈락했고 `results/runs/`에만 보관했습니다. `127.0.0.1` 사용 시에도 발생했으며 근본 원인은 확정하지 않았습니다.

Windows에서 서명 정책으로 스크립트가 차단되면 검토한 스크립트에 대해 프로세스 한정 실행 옵션을 사용할 수 있습니다. 시스템 정책은 변경하지 않습니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Verify-Measurements.ps1
# 실험도 동일한 방식으로 -File 뒤에 해당 스크립트와 인자를 전달
```

## 참고

- [Caffeine](https://github.com/ben-manes/caffeine)
- [Redis Pub/Sub의 전달 특성](https://redis.io/docs/latest/develop/interact/pubsub/)
- [k6 환경변수](https://grafana.com/docs/k6/latest/using-k6/environment-variables/)
- [k6 constant-arrival-rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)
