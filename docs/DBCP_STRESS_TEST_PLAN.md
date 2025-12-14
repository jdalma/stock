# DBCP 부하 테스트 & 최적화 가이드

## 목차
1. [프로젝트 개요](#1-프로젝트-개요)
2. [환경 구성](#2-환경-구성)
3. [HikariCP 기본값 이해](#3-hikaricp-기본값-이해)
4. [부하 테스트 시나리오](#4-부하-테스트-시나리오)
5. [모니터링 설정](#5-모니터링-설정)
6. [테스트 실행 단계](#6-테스트-실행-단계)
7. [임계점 분석 방법](#7-임계점-분석-방법)
8. [HikariCP 튜닝 전략](#8-hikaricp-튜닝-전략)
9. [결과 비교 분석](#9-결과-비교-분석)

---

## 1. 프로젝트 개요

### 목표
> **HikariCP 기본 설정의 임계점을 부하 테스트로 측정하고, 동일 환경에서 설정 튜닝만으로 성능 개선을 입증한다**

### 현재 애플리케이션 분석

| 항목 | 현재 상태 |
|------|----------|
| **프레임워크** | Spring Boot 3.5.6 + Kotlin |
| **DB** | MySQL |
| **Connection Pool** | HikariCP (기본값) |
| **주요 API** | 상품 조회 (JSON 메타데이터 역직렬화 포함) |

### API 엔드포인트

| 엔드포인트 | 메서드 | 설명 | 특성 |
|-----------|--------|------|------|
| `/api/products/{id}` | GET | 단일 상품 조회 | 단순 조회 + JSON 파싱 |
| `/api/products` | GET | 상품 목록 조회 | 페이징 + N개 JSON 파싱 |
| `/api/products/search` | GET | 상품 검색 | LIKE 쿼리 + 페이징 |

### 예상 병목 지점
1. **DB Connection 부족**: 동시 요청 증가 시 connection pool 고갈
2. **Connection Wait Time**: pool 부족 시 대기 시간 증가
3. **JSON 역직렬화**: 메타데이터가 크고 복잡함 (reviews, inventory 등)

---

## 2. 환경 구성

### 2.1 Docker Compose (MySQL + 모니터링)

`docker-compose.yml` 파일 생성:

```yaml
version: '3.8'

services:
  # MySQL 데이터베이스
  mysql:
    image: mysql:8.0
    container_name: stress-test-mysql
    environment:
      MYSQL_ROOT_PASSWORD: rootpassword
      MYSQL_DATABASE: stockdb
      MYSQL_USER: testuser
      MYSQL_PASSWORD: testpassword
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./mysql-init:/docker-entrypoint-initdb.d
    command: >
      --max_connections=200
      --innodb_buffer_pool_size=256M
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  # InfluxDB (k6 메트릭 저장)
  influxdb:
    image: influxdb:1.8
    container_name: stress-test-influxdb
    environment:
      INFLUXDB_DB: k6
      INFLUXDB_ADMIN_USER: admin
      INFLUXDB_ADMIN_PASSWORD: adminpassword
    ports:
      - "8086:8086"
    volumes:
      - influxdb_data:/var/lib/influxdb

  # Grafana (대시보드)
  grafana:
    image: grafana/grafana:latest
    container_name: stress-test-grafana
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: admin
    ports:
      - "3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
      - ./grafana/provisioning:/etc/grafana/provisioning
    depends_on:
      - influxdb

  # Prometheus (애플리케이션 메트릭)
  prometheus:
    image: prom/prometheus:latest
    container_name: stress-test-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'

volumes:
  mysql_data:
  influxdb_data:
  grafana_data:
  prometheus_data:
```

### 2.2 Prometheus 설정

`prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

### 2.3 로컬 테스트용 application-local.yml

```yaml
spring:
  application:
    name: stock-test

  datasource:
    url: jdbc:mysql://localhost:3306/stockdb
    username: testuser
    password: testpassword
    driver-class-name: com.mysql.cj.jdbc.Driver

    # HikariCP 기본 설정 (명시적 선언 - 실제 기본값)
    hikari:
      pool-name: StockHikariPool
      # 아래는 HikariCP 기본값 - Phase 1에서는 이대로 사용
      maximum-pool-size: 10
      minimum-idle: 10
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
    show-sql: false

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,hikaricp
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: ${spring.application.name}
```

### 2.4 k6 설치

```bash
# macOS
brew install k6

# 또는 Docker로 실행
docker pull grafana/k6
```

---

## 3. HikariCP 기본값 이해

### 핵심 설정 파라미터

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `maximum-pool-size` | **10** | 풀에서 유지하는 최대 커넥션 수 |
| `minimum-idle` | `maximum-pool-size`와 동일 | 유휴 상태로 유지할 최소 커넥션 수 |
| `connection-timeout` | **30000ms** (30초) | 커넥션을 얻기 위해 대기하는 최대 시간 |
| `idle-timeout` | **600000ms** (10분) | 유휴 커넥션이 풀에서 제거되기까지의 시간 |
| `max-lifetime` | **1800000ms** (30분) | 커넥션이 풀에서 살아있을 수 있는 최대 시간 |
| `leak-detection-threshold` | **0** (비활성화) | 커넥션 누수 감지 임계값 |

### 기본값의 의미

```
maximum-pool-size = 10

→ 동시에 10개의 DB 커넥션만 사용 가능
→ 11번째 요청부터는 대기 (최대 30초)
→ 30초 내에 커넥션을 얻지 못하면 SQLException 발생
```

### 예상 임계점

**이론적 최대 TPS 계산**:
```
TPS = (pool_size × 1000) / avg_query_time_ms

예시:
- pool_size = 10
- avg_query_time = 50ms
- 이론적 TPS = (10 × 1000) / 50 = 200 TPS
```

실제로는 다음 요인으로 더 낮을 수 있음:
- 네트워크 지연
- JSON 역직렬화 시간
- GC 오버헤드
- 기타 애플리케이션 로직

---

## 4. 부하 테스트 시나리오

### 4.1 Phase 1: 읽기 테스트 (SELECT)

`k6/scripts/phase1-read-test.js`:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// 커스텀 메트릭
const errorRate = new Rate('errors');
const connectionErrors = new Counter('connection_errors');
const responseTime = new Trend('response_time');

// 테스트 설정
export const options = {
  scenarios: {
    // 점진적 부하 증가
    ramping_load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 10 },   // Warm-up
        { duration: '1m', target: 20 },    // Pool size 도달
        { duration: '1m', target: 30 },    // Pool 초과 시작
        { duration: '1m', target: 50 },    // 스트레스 테스트
        { duration: '1m', target: 75 },    // 고부하
        { duration: '1m', target: 100 },   // 극한 부하
        { duration: '30s', target: 0 },    // Cool-down
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000'],  // 95% 요청이 2초 이내
    errors: ['rate<0.1'],                // 에러율 10% 미만
  },
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  // 랜덤 상품 ID (1-100)
  const productId = Math.floor(Math.random() * 100) + 1;

  // 단일 상품 조회
  const singleResponse = http.get(`${BASE_URL}/api/products/${productId}`, {
    tags: { name: 'GetProductById' },
  });

  check(singleResponse, {
    'single product status is 200': (r) => r.status === 200,
    'single product has id': (r) => JSON.parse(r.body).id !== undefined,
  });

  if (singleResponse.status !== 200) {
    errorRate.add(1);
    if (singleResponse.status === 500 || singleResponse.status === 503) {
      connectionErrors.add(1);
    }
  } else {
    errorRate.add(0);
  }

  responseTime.add(singleResponse.timings.duration);

  sleep(0.1); // 100ms 대기

  // 상품 목록 조회 (페이징)
  const page = Math.floor(Math.random() * 10);
  const listResponse = http.get(`${BASE_URL}/api/products?page=${page}&size=10`, {
    tags: { name: 'GetProductList' },
  });

  check(listResponse, {
    'list status is 200': (r) => r.status === 200,
    'list has products': (r) => JSON.parse(r.body).products.length > 0,
  });

  if (listResponse.status !== 200) {
    errorRate.add(1);
    if (listResponse.status === 500 || listResponse.status === 503) {
      connectionErrors.add(1);
    }
  } else {
    errorRate.add(0);
  }

  sleep(0.1);
}

export function handleSummary(data) {
  return {
    'reports/phase1-summary.json': JSON.stringify(data, null, 2),
  };
}
```

### 4.2 Phase 2: 쓰기 테스트 (INSERT/UPDATE)

먼저 쓰기 API가 필요합니다. 테스트용 API 추가 필요:

`k6/scripts/phase2-write-test.js`:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const connectionErrors = new Counter('connection_errors');

export const options = {
  scenarios: {
    write_load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 5 },
        { duration: '1m', target: 10 },
        { duration: '1m', target: 20 },
        { duration: '1m', target: 30 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<3000'],
    errors: ['rate<0.15'],
  },
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  // 상품 생성 (POST) - API 추가 필요
  const payload = JSON.stringify({
    name: `Test Product ${Date.now()}`,
    category: '테스트',
    price: Math.floor(Math.random() * 100000) + 10000,
    brand: 'TestBrand',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    tags: { name: 'CreateProduct' },
  };

  const createResponse = http.post(`${BASE_URL}/api/products`, payload, params);

  check(createResponse, {
    'create status is 201': (r) => r.status === 201,
  });

  if (createResponse.status !== 201) {
    errorRate.add(1);
    if (createResponse.status === 500 || createResponse.status === 503) {
      connectionErrors.add(1);
    }
  } else {
    errorRate.add(0);
  }

  sleep(0.2);
}
```

### 4.3 Phase 3: 혼합 테스트

`k6/scripts/phase3-mixed-test.js`:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const connectionErrors = new Counter('connection_errors');
const readLatency = new Trend('read_latency');
const writeLatency = new Trend('write_latency');

export const options = {
  scenarios: {
    mixed_load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 25 },
        { duration: '1m', target: 50 },
        { duration: '1m', target: 75 },
        { duration: '1m', target: 100 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2500'],
    errors: ['rate<0.1'],
  },
};

const BASE_URL = 'http://localhost:8080';

// 읽기:쓰기 = 8:2 비율
export default function () {
  const isRead = Math.random() < 0.8;

  if (isRead) {
    // 읽기 작업
    const productId = Math.floor(Math.random() * 100) + 1;
    const response = http.get(`${BASE_URL}/api/products/${productId}`, {
      tags: { name: 'ReadOperation' },
    });

    readLatency.add(response.timings.duration);

    check(response, {
      'read status is 200': (r) => r.status === 200,
    });

    if (response.status !== 200) {
      errorRate.add(1);
      if (response.status >= 500) connectionErrors.add(1);
    } else {
      errorRate.add(0);
    }
  } else {
    // 쓰기 작업 (API 추가 필요)
    const payload = JSON.stringify({
      name: `Mixed Test ${Date.now()}`,
      category: '테스트',
      price: Math.floor(Math.random() * 100000) + 10000,
      brand: 'TestBrand',
    });

    const response = http.post(`${BASE_URL}/api/products`, payload, {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'WriteOperation' },
    });

    writeLatency.add(response.timings.duration);

    check(response, {
      'write status is 201': (r) => r.status === 201,
    });

    if (response.status !== 201) {
      errorRate.add(1);
      if (response.status >= 500) connectionErrors.add(1);
    } else {
      errorRate.add(0);
    }
  }

  sleep(0.1);
}

export function handleSummary(data) {
  return {
    'reports/phase3-summary.json': JSON.stringify(data, null, 2),
  };
}
```

---

## 5. 모니터링 설정

### 5.1 k6 → InfluxDB 연동

테스트 실행 시 InfluxDB로 메트릭 전송:

```bash
k6 run --out influxdb=http://localhost:8086/k6 k6/scripts/phase1-read-test.js
```

### 5.2 Grafana 대시보드 설정

1. **Grafana 접속**: http://localhost:3000 (admin/admin)

2. **데이터소스 추가**:
   - InfluxDB: `http://influxdb:8086`, Database: `k6`
   - Prometheus: `http://prometheus:9090`

3. **추천 대시보드 Import**:
   - k6 Dashboard: ID `2587`
   - HikariCP Dashboard: ID `12727`
   - Spring Boot Statistics: ID `6756`

### 5.3 HikariCP 메트릭 노출

애플리케이션에서 HikariCP 메트릭 확인:

```bash
# Active connections
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active

# Pending connections (대기 중)
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending

# Connection timeout count
curl http://localhost:8080/actuator/metrics/hikaricp.connections.timeout

# Connection acquire time
curl http://localhost:8080/actuator/metrics/hikaricp.connections.acquire
```

### 5.4 핵심 모니터링 지표

| 지표 | 정상 범위 | 경고 | 위험 |
|------|----------|------|------|
| `hikaricp.connections.active` | 0-8 | 9-10 | 10 (고정) |
| `hikaricp.connections.pending` | 0 | 1-5 | >5 |
| `hikaricp.connections.timeout` | 0 | 1+ | 급증 |
| `http_req_duration(p95)` | <500ms | 500-2000ms | >2000ms |
| Error Rate | <1% | 1-5% | >5% |

---

## 6. 테스트 실행 단계

### Step 1: 환경 구성

```bash
# 1. Docker 환경 시작
docker-compose up -d

# 2. MySQL 준비 상태 확인
docker-compose logs -f mysql

# 3. 애플리케이션 실행 (로컬)
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. 헬스체크
curl http://localhost:8080/actuator/health
```

### Step 2: 베이스라인 측정 (기본 설정)

```bash
# Phase 1: 읽기 테스트
k6 run --out influxdb=http://localhost:8086/k6 \
  -e TEST_NAME=baseline-read \
  k6/scripts/phase1-read-test.js

# 결과 기록
mv reports/phase1-summary.json reports/baseline-phase1.json
```

### Step 3: 임계점 식별

테스트 중 다음 징후를 관찰:
- `hikaricp.connections.active` = 10으로 포화
- `hikaricp.connections.pending` > 0 시작
- Response time 급증
- Error rate 상승

### Step 4: HikariCP 튜닝 후 재테스트

```bash
# 튜닝된 설정으로 애플리케이션 재시작
./gradlew bootRun --args='--spring.profiles.active=local-tuned'

# 동일 테스트 재실행
k6 run --out influxdb=http://localhost:8086/k6 \
  -e TEST_NAME=tuned-read \
  k6/scripts/phase1-read-test.js
```

---

## 7. 임계점 분석 방법

### 7.1 임계점 정의

| 임계점 유형 | 정의 | 식별 기준 |
|-------------|------|----------|
| **Saturation Point** | Connection Pool 포화 | `active=pool_size`, `pending>0` |
| **Degradation Point** | 성능 저하 시작 | P95 latency > 2x baseline |
| **Breaking Point** | 서비스 불능 | Error rate > 10% |

### 7.2 분석 체크리스트

```
□ VU(가상 사용자) 수 vs Active Connections 관계
□ 첫 번째 pending connection 발생 시점
□ 첫 번째 connection timeout 발생 시점
□ P95 latency가 baseline 대비 2배가 되는 시점
□ Error rate가 1%를 초과하는 시점
□ 최대 처리 가능 TPS
```

### 7.3 결과 기록 템플릿

```markdown
## 테스트 결과: [Phase X] - [설정명]

### 환경
- Pool Size: X
- Connection Timeout: Xms
- Test Duration: X분

### 임계점
- Saturation Point: VU XX (XX분 XX초)
- Degradation Point: VU XX (XX분 XX초)
- Breaking Point: VU XX (XX분 XX초)

### 성능 지표
| 지표 | 값 |
|------|-----|
| Total Requests | XXX |
| Avg TPS | XXX |
| Max TPS | XXX |
| P50 Latency | XXms |
| P95 Latency | XXms |
| P99 Latency | XXms |
| Error Rate | X.XX% |
| Connection Timeouts | X |
```

---

## 8. HikariCP 튜닝 전략

### 8.1 튜닝 시나리오별 설정

#### Scenario A: 기본값 (Baseline)
```yaml
hikari:
  maximum-pool-size: 10
  minimum-idle: 10
  connection-timeout: 30000
```

#### Scenario B: Pool Size 증가
```yaml
hikari:
  maximum-pool-size: 20
  minimum-idle: 10
  connection-timeout: 30000
```

#### Scenario C: Pool Size + Timeout 조정
```yaml
hikari:
  maximum-pool-size: 30
  minimum-idle: 15
  connection-timeout: 10000  # 빠른 실패
```

#### Scenario D: 최적화 설정
```yaml
hikari:
  maximum-pool-size: 50
  minimum-idle: 20
  connection-timeout: 5000
  idle-timeout: 300000
  max-lifetime: 900000
  leak-detection-threshold: 60000
```

### 8.2 Pool Size 계산 공식

HikariCP 권장 공식:
```
pool_size = (core_count * 2) + effective_spindle_count

예시 (4코어, SSD):
pool_size = (4 * 2) + 1 = 9 ~ 10
```

하지만 실제로는 워크로드에 따라 다름:
```
# 높은 동시성, 짧은 쿼리
pool_size = expected_concurrent_requests * 0.5

# 낮은 동시성, 긴 쿼리
pool_size = expected_concurrent_requests * 1.0
```

### 8.3 튜닝 주의사항

⚠️ **주의**: Pool size를 무작정 늘리면 안됨

| Pool Size | 장점 | 단점 |
|-----------|------|------|
| 작음 (10) | 메모리 절약, DB 부하 낮음 | 동시성 제한 |
| 중간 (30) | 균형잡힌 성능 | - |
| 큼 (100+) | 높은 동시성 | 메모리 증가, DB 과부하, 컨텍스트 스위칭 |

**MySQL max_connections 확인 필수**:
```sql
SHOW VARIABLES LIKE 'max_connections';
-- 기본값: 151
```

---

## 9. 결과 비교 분석

### 9.1 비교 매트릭스

| 설정 | VU | TPS | P95(ms) | Error% | Pool Saturation |
|------|-----|-----|---------|--------|-----------------|
| Baseline (10) | 50 | ? | ? | ? | ? |
| Tuned-B (20) | 50 | ? | ? | ? | ? |
| Tuned-C (30) | 50 | ? | ? | ? | ? |
| Tuned-D (50) | 50 | ? | ? | ? | ? |

### 9.2 개선율 계산

```
TPS 개선율 = ((Tuned_TPS - Baseline_TPS) / Baseline_TPS) × 100%
Latency 개선율 = ((Baseline_P95 - Tuned_P95) / Baseline_P95) × 100%
```

### 9.3 최종 리포트 템플릿

```markdown
# DBCP 최적화 결과 리포트

## Executive Summary
- 기본 설정 대비 TPS XX% 향상
- P95 Latency XX% 감소
- Connection Pool 포화 임계점 VU XX → VU XX 상승

## 권장 설정
​```yaml
hikari:
  maximum-pool-size: XX
  minimum-idle: XX
  connection-timeout: XXms
​```

## 근거
1. ...
2. ...
3. ...
```

---

## 부록

### A. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| Connection timeout 급증 | Pool 부족 | pool size 증가 |
| Idle connection 많음 | Pool 과다 | minimum-idle 감소 |
| 메모리 부족 | Pool 과다 | pool size 감소 |
| 간헐적 끊김 | max-lifetime 문제 | MySQL wait_timeout보다 작게 설정 |

### B. 유용한 명령어

```bash
# k6 실시간 모니터링
k6 run --out influxdb=http://localhost:8086/k6 script.js

# MySQL 현재 연결 수 확인
docker exec -it stress-test-mysql mysql -u root -prootpassword -e "SHOW STATUS LIKE 'Threads_connected';"

# HikariCP 메트릭 전체 조회
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(startswith("hikaricp"))'

# Grafana 대시보드 백업
curl -s http://localhost:3000/api/dashboards/db/k6-load-testing-results -H "Authorization: Bearer YOUR_API_KEY" | jq '.dashboard' > dashboard-backup.json
```

### C. 참고 자료

- [HikariCP GitHub](https://github.com/brettwooldridge/HikariCP)
- [HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [k6 Documentation](https://k6.io/docs/)
- [Spring Boot Actuator Metrics](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
