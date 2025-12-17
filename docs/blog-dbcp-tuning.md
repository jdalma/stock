# HikariCP 튜닝 실전 가이드: 병목 상황에서 임계점 찾기

> 부하 테스트를 통해 DBCP(Database Connection Pool)의 임계점을 찾고, 설정을 하나씩 튜닝하며 성능을 개선하는 과정을 기록합니다.

---

## 들어가며

"커넥션 풀 사이즈는 몇 개가 적당할까?"

많은 개발자들이 HikariCP의 기본값(10개)을 그대로 사용하거나, 막연히 큰 값을 설정합니다. 하지만 최적의 설정은 애플리케이션의 특성, 쿼리 실행 시간, 동시 사용자 수에 따라 달라집니다.

이 글에서는 **의도적으로 병목 상황을 만들고**, k6 부하 테스트와 Grafana 모니터링을 통해 **임계점을 직접 확인**하면서 각 설정의 역할을 학습합니다.

---

## 실험 환경

### 기술 스택
- **Application**: Spring Boot 3.x + Kotlin
- **Connection Pool**: HikariCP
- **Database**: MySQL 8.0 (Docker)
- **Load Testing**: k6
- **Monitoring**: Prometheus + Grafana

### 병목 시뮬레이션 설정

실제 느린 쿼리 상황을 시뮬레이션하기 위해 두 가지 설정을 적용했습니다:

```kotlin
// ProductService.kt
@Transactional(readOnly = true)
fun getProductById(id: Long): ProductResponse {
    // 병목 시뮬레이션: 50ms 지연
    Thread.sleep(50)

    val product = productRepository.findById(id)
        .orElseThrow { NoSuchElementException("Product not found") }
    return convertToResponse(product)
}
```

```yaml
# application-tuned.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5  # 의도적으로 작게 설정
```

**왜 `@Transactional` + `Thread.sleep` 조합인가?**

`@Transactional` 어노테이션이 적용된 메서드는 **트랜잭션 시작 시점에 커넥션을 획득**하고, **메서드 종료 시점까지 보유**합니다. 따라서 `Thread.sleep(50)`은 단순히 스레드를 멈추는 것이 아니라, **50ms 동안 DB 커넥션을 점유**하는 효과를 냅니다.

---

## Phase 1: 기본값 상태에서 Baseline 측정

### HikariCP 기본값

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `maximum-pool-size` | 10 | 최대 커넥션 수 |
| `minimum-idle` | 10 | 최소 유휴 커넥션 (= maximum-pool-size) |
| `connection-timeout` | 30,000ms | 커넥션 획득 대기 시간 |
| `idle-timeout` | 600,000ms | 유휴 커넥션 제거 시간 (10분) |
| `max-lifetime` | 1,800,000ms | 커넥션 최대 수명 (30분) |

### 부하 테스트 시나리오

```javascript
// k6/scripts/read-test.js
export const options = {
    stages: [
        { duration: '30s', target: 10 },  // Warm-up
        { duration: '1m', target: 50 },   // 부하 증가
        { duration: '1m', target: 100 },  // 최대 부하
        { duration: '30s', target: 0 },   // Cool-down
    ],
};
```

### Baseline 결과

```
[기본값 Pool Size: 10, Sleep: 없음]

✓ http_req_duration (avg): XXms
✓ http_reqs (total): XX,XXX
✓ iterations (rate): XX/s
```

> **측정 포인트**: 응답 시간, TPS, 에러율

---

## Phase 2: 병목 상황 재현

### 설정 변경

```yaml
hikari:
  maximum-pool-size: 5  # 10 → 5로 축소
```

```kotlin
Thread.sleep(50)  // 50ms 지연 추가
```

### 예상 동작

| 동시 요청 수 | 상태 |
|-------------|------|
| ≤ 5 | 모든 요청 즉시 처리 |
| 6~10 | 일부 요청 커넥션 대기 |
| > 10 | pending 큐 증가, 응답 시간 급증 |

### 모니터링 지표

Grafana에서 확인할 핵심 메트릭:

```promql
# 활성 커넥션 수 (사용 중)
hikaricp_connections_active{pool="StockHikariPool"}

# 대기 중인 요청 수 (병목 지표!)
hikaricp_connections_pending{pool="StockHikariPool"}

# 커넥션 획득 시간 (95 percentile)
histogram_quantile(0.95,
  sum(rate(hikaricp_connections_acquire_seconds_bucket[1m])) by (le)
)
```

### 병목 상황 결과

```
[Pool Size: 5, Sleep: 50ms]

✗ http_req_duration (avg): XXXms (↑ XX% 증가)
✗ http_req_failed: XX% 에러 발생
⚠ hikaricp_connections_pending: 최대 XX개
```

---

## Phase 3: Pool Size 튜닝

### 이론: 최적의 Pool Size 공식

HikariCP 공식 문서에서 제안하는 공식:

```
pool size = Tn × (Cm - 1) + 1

Tn = 최대 스레드 수
Cm = 단일 스레드가 보유하는 최대 동시 커넥션 수
```

하지만 실제로는 **부하 테스트를 통한 실험적 접근**이 더 정확합니다.

### 튜닝 실험

| Pool Size | Avg Response | P95 Response | Error Rate | Pending Max |
|-----------|--------------|--------------|------------|-------------|
| 5 | - | - | - | - |
| 10 | - | - | - | - |
| 15 | - | - | - | - |
| 20 | - | - | - | - |

### 결과 분석

> TODO: 실험 결과 채우기

**핵심 인사이트:**
- Pool Size를 늘린다고 무조건 성능이 좋아지지 않음
- DB 서버의 `max_connections`와 애플리케이션 인스턴스 수 고려 필요
- 과도한 커넥션은 오히려 DB 부하 증가

---

## Phase 4: Timeout 튜닝

### connection-timeout

커넥션을 획득하기 위해 **대기하는 최대 시간**입니다.

```yaml
hikari:
  connection-timeout: 30000  # 기본값: 30초
```

**튜닝 포인트:**
- 너무 길면: 사용자가 오래 대기
- 너무 짧으면: 일시적 부하에도 에러 발생

```
[connection-timeout 변경 실험]

30초: 에러 없음, 응답 지연 발생
10초: 피크 시간 XX% 에러
5초: 피크 시간 XX% 에러
```

### idle-timeout

유휴 커넥션이 **풀에서 제거되기까지의 시간**입니다.

```yaml
hikari:
  idle-timeout: 600000  # 기본값: 10분
```

**주의사항:**
- `minimum-idle < maximum-pool-size`일 때만 적용
- DB의 `wait_timeout`보다 짧게 설정 권장

### max-lifetime

커넥션의 **최대 수명**입니다. 이 시간이 지나면 강제로 폐기됩니다.

```yaml
hikari:
  max-lifetime: 1800000  # 기본값: 30분
```

**중요한 이유:**
- DB의 `wait_timeout`(기본 8시간)보다 **반드시 짧게** 설정
- 네트워크 장비, 방화벽의 idle connection timeout 고려
- 권장: DB timeout의 2/3 이하

---

## Phase 5: 검증 및 모니터링 설정

### leak-detection-threshold

커넥션 누수를 감지하는 임계 시간입니다.

```yaml
hikari:
  leak-detection-threshold: 2000  # 2초
```

**동작 방식:**
- 커넥션 획득 후 지정 시간 내에 반환되지 않으면 경고 로그
- 개발/테스트 환경에서 활성화 권장
- 프로덕션에서는 0(비활성화) 또는 긴 시간 설정

```
[누수 감지 로그 예시]
WARN  c.z.h.p.ProxyLeakTask - Connection leak detection triggered for conn0
       on thread http-nio-8080-exec-1, stack trace follows
```

### validation-timeout

커넥션 유효성 검사 타임아웃입니다.

```yaml
hikari:
  validation-timeout: 5000  # 기본값: 5초
```

---

## 최종 튜닝 결과 비교

### Before (기본값)

```yaml
hikari:
  maximum-pool-size: 10
  connection-timeout: 30000
  # 나머지 기본값
```

```
[부하 테스트 결과]
Avg Response: XXms
P95 Response: XXms
Error Rate: XX%
Max TPS: XX/s
```

### After (튜닝 후)

```yaml
hikari:
  maximum-pool-size: XX
  minimum-idle: XX
  connection-timeout: XXms
  idle-timeout: XXms
  max-lifetime: XXms
  leak-detection-threshold: 2000
```

```
[부하 테스트 결과]
Avg Response: XXms (↓ XX% 개선)
P95 Response: XXms (↓ XX% 개선)
Error Rate: XX% (↓ XX% 개선)
Max TPS: XX/s (↑ XX% 개선)
```

---

## 정리: HikariCP 튜닝 체크리스트

### 필수 확인 사항

- [ ] DB `max_connections` 확인 (MySQL: `SHOW VARIABLES LIKE 'max_connections'`)
- [ ] 애플리케이션 인스턴스 수 × pool size < DB max_connections
- [ ] `max-lifetime` < DB `wait_timeout`
- [ ] 실제 쿼리 실행 시간 측정

### 권장 설정 템플릿

```yaml
spring:
  datasource:
    hikari:
      pool-name: MyHikariPool

      # Pool Size
      maximum-pool-size: ${HIKARI_MAX_POOL:10}
      minimum-idle: ${HIKARI_MIN_IDLE:10}

      # Timeout
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

      # Validation
      validation-timeout: 5000

      # Monitoring (개발 환경)
      leak-detection-threshold: 2000
      register-mbeans: true
```

### 모니터링 필수 지표

| 지표 | 정상 범위 | 경고 조건 |
|------|----------|----------|
| `connections_active` | < 80% of max | > 90% 지속 |
| `connections_pending` | 0 | > 0 지속 |
| `connections_acquire_seconds` (p95) | < 10ms | > 100ms |

---

## 마치며

DBCP 튜닝은 "정답"이 없습니다.

핵심은 **모니터링 → 병목 식별 → 가설 수립 → 실험 → 검증**의 사이클을 반복하는 것입니다.

이 글에서 다룬 실험들을 직접 재현해보면서, 여러분의 애플리케이션에 맞는 최적의 설정을 찾아보시기 바랍니다.

---

## 참고 자료

- [HikariCP GitHub - About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby)
- [Spring Boot - Connection Pool Properties](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html#application-properties.data.spring.datasource.hikari)
