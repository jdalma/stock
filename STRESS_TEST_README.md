# DBCP 부하 테스트 실행 가이드

## 빠른 시작

### 1. Docker 환경 시작

```bash
# 모든 컨테이너 시작 (MySQL, InfluxDB, Grafana, Prometheus)
docker-compose up -d

# 상태 확인
docker-compose ps

# MySQL 준비 상태 확인 (healthy 될 때까지 대기)
docker-compose logs -f mysql
```

### 2. 애플리케이션 실행

```bash
# 베이스라인 테스트 (기본 HikariCP 설정: pool-size=10)
./gradlew bootRun --args='--spring.profiles.active=local'

# 또는 튜닝된 설정으로 실행 (pool-size=30)
./gradlew bootRun --args='--spring.profiles.active=tuned'
```

### 3. 헬스체크

```bash
# 애플리케이션 상태 확인
curl http://localhost:8080/actuator/health

# HikariCP 메트릭 확인
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

### 4. k6 테스트 실행

```bash
# k6 설치 (macOS)
brew install k6

# Phase 1: 읽기 테스트 (InfluxDB로 메트릭 전송)
k6 run --out influxdb=http://localhost:8086/k6 k6/scripts/phase1-read-test.js

# Phase 2: 쓰기 테스트
k6 run --out influxdb=http://localhost:8086/k6 k6/scripts/phase2-write-test.js

# Phase 3: 혼합 테스트
k6 run --out influxdb=http://localhost:8086/k6 k6/scripts/phase3-mixed-test.js
```

### 5. 모니터링

- **Grafana**: http://localhost:3000 (admin/admin)
  - DBCP Stress Test Dashboard 자동 프로비저닝됨
- **Prometheus**: http://localhost:9090
- **Actuator**: http://localhost:8080/actuator/prometheus

---

## 테스트 시나리오

### Phase 1: 읽기 테스트
- **목적**: SELECT 쿼리 위주의 읽기 부하에서 임계점 측정
- **부하 패턴**: 1 → 10 → 20 → 50 → 100 VU 점진 증가
- **예상 임계점**: VU 20~30 (pool-size=10 기준)

### Phase 2: 쓰기 테스트
- **목적**: INSERT 트랜잭션의 커넥션 점유 시간 분석
- **부하 패턴**: 1 → 10 → 20 → 30 VU
- **예상 임계점**: VU 10~15 (쓰기는 읽기보다 빨리 포화)

### Phase 3: 혼합 테스트
- **목적**: 실제 트래픽 패턴 시뮬레이션
- **읽기:쓰기 비율**: 80:20
- **부하 패턴**: 1 → 50 → 100 VU

---

## 설정 비교

| 설정 | Baseline (local) | Tuned |
|------|------------------|-------|
| `maximum-pool-size` | 10 | 30 |
| `minimum-idle` | 10 | 15 |
| `connection-timeout` | 30s | 10s |
| `idle-timeout` | 10m | 5m |
| `max-lifetime` | 30m | 15m |
| `leak-detection` | OFF | 60s |

---

## 임계점 관찰 포인트

테스트 중 Grafana 대시보드에서 다음을 관찰:

1. **Connection Pool Status**
   - `Active Connections`가 `max-pool-size`에 도달하면 포화
   - `Pending Connections`가 1 이상이면 대기 발생

2. **Connection Acquire Time**
   - P95가 급증하면 커넥션 획득 지연

3. **Response Time**
   - P95가 baseline 대비 2배 이상이면 성능 저하 시작

4. **Error Rate**
   - 10% 초과 시 서비스 불안정

---

## 결과 비교 방법

```bash
# 베이스라인 테스트 실행
./gradlew bootRun --args='--spring.profiles.active=local'
k6 run --out influxdb=http://localhost:8086/k6 k6/scripts/phase1-read-test.js

# 애플리케이션 재시작 (튜닝 설정)
# Ctrl+C로 종료 후
./gradlew bootRun --args='--spring.profiles.active=tuned'
k6 run --out influxdb=http://localhost:8086/k6 k6/scripts/phase1-read-test.js

# Grafana에서 시간대별 비교
```

---

## 정리

```bash
# 모든 컨테이너 중지 및 삭제
docker-compose down

# 볼륨까지 삭제 (데이터 초기화)
docker-compose down -v
```

---

## 파일 구조

```
├── docker-compose.yml           # Docker 환경 설정
├── docker/
│   ├── prometheus/prometheus.yml
│   ├── grafana/provisioning/
│   │   ├── datasources/datasources.yml
│   │   └── dashboards/
│   │       ├── dashboards.yml
│   │       └── dbcp-stress-test.json
│   └── mysql-init/01-init.sql
├── k6/
│   ├── scripts/
│   │   ├── phase1-read-test.js
│   │   ├── phase2-write-test.js
│   │   └── phase3-mixed-test.js
│   └── reports/                 # 테스트 결과 JSON
├── src/main/resources/
│   ├── application.yml          # 기본 설정 (AWS)
│   ├── application-local.yml    # 로컬 테스트 (baseline)
│   └── application-tuned.yml    # 튜닝된 설정
└── docs/
    └── DBCP_STRESS_TEST_PLAN.md # 상세 계획 문서
```
