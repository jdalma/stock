# 메트릭 수집 아키텍처

## 개요

이 프로젝트는 Spring Boot 애플리케이션의 성능 메트릭을 수집하고 시각화하기 위해 **Prometheus + Grafana** 스택을 사용합니다.

---

## 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              데이터 흐름                                      │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────┐         ┌──────────────────────┐         ┌──────────────┐
│   Spring Boot App    │         │     Prometheus       │         │   Grafana    │
│                      │  PULL   │                      │  QUERY  │              │
│  ┌────────────────┐  │ ◄────── │  ┌────────────────┐  │ ◄────── │  Dashboard   │
│  │ Micrometer     │  │  HTTP   │  │ Time Series DB │  │ PromQL │              │
│  │ (메트릭 수집)   │  │  GET    │  │ (저장소)        │  │        │              │
│  └───────┬────────┘  │         │  └────────────────┘  │         │              │
│          │           │         │                      │         │              │
│  ┌───────▼────────┐  │         │  5초마다 스크랩      │         │              │
│  │ /actuator/     │──┼─────────┼──────────────────────┤         │              │
│  │ prometheus     │  │         │                      │         │              │
│  └────────────────┘  │         │                      │         │              │
│                      │         │                      │         │              │
│  - HikariCP 메트릭   │         │  - 7일간 저장        │         │  - 시각화     │
│  - Tomcat 메트릭     │         │  - 집계/계산         │         │  - 알람       │
│  - JVM 메트릭        │         │                      │         │              │
│  - HTTP 메트릭       │         │                      │         │              │
└──────────────────────┘         └──────────────────────┘         └──────────────┘
       :8080                            :9090                          :3000
```

---

## 각 구성요소의 역할

### 1. Spring Actuator + Micrometer

**역할**: 메트릭 생성 및 HTTP 엔드포인트로 노출

#### 설정 (application-tuned.yml)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus  # 엔드포인트 노출
      base-path: /actuator
  metrics:
    tags:
      application: ${spring.application.name}
```

#### 노출되는 메트릭 종류

| 카테고리 | 메트릭 예시 | 설명 |
|---------|------------|------|
| HikariCP | `hikaricp_connections_active` | 활성 커넥션 수 |
| HikariCP | `hikaricp_connections_idle` | 유휴 커넥션 수 |
| HikariCP | `hikaricp_connections_pending` | 대기 중인 요청 수 |
| HikariCP | `hikaricp_connections_acquire_seconds` | 커넥션 획득 시간 |
| Tomcat | `tomcat_threads_busy_threads` | 사용 중인 스레드 수 |
| Tomcat | `tomcat_threads_current_threads` | 현재 스레드 수 |
| HTTP | `http_server_requests_seconds` | API 응답 시간 |
| JVM | `jvm_memory_used_bytes` | JVM 메모리 사용량 |
| JVM | `jvm_threads_states_threads` | JVM 스레드 상태 |

#### 실제 출력 예시 (`GET /actuator/prometheus`)

```
# HELP hikaricp_connections Total connections
# TYPE hikaricp_connections gauge
hikaricp_connections{application="stock-test",pool="StockHikariPool"} 10.0

# HELP hikaricp_connections_active Active connections
# TYPE hikaricp_connections_active gauge
hikaricp_connections_active{application="stock-test",pool="StockHikariPool"} 0.0

# HELP hikaricp_connections_acquire_seconds Connection acquire time
# TYPE hikaricp_connections_acquire_seconds histogram
hikaricp_connections_acquire_seconds_bucket{pool="StockHikariPool",le="0.001"} 1
hikaricp_connections_acquire_seconds_bucket{pool="StockHikariPool",le="0.002"} 2
hikaricp_connections_acquire_seconds_sum{pool="StockHikariPool"} 0.005
hikaricp_connections_acquire_seconds_count{pool="StockHikariPool"} 3
```

---

### 2. Prometheus

**역할**: 메트릭 수집(Pull 방식) 및 시계열 데이터 저장

#### 설정 (prometheus.yml)

```yaml
global:
  scrape_interval: 5s      # 기본 스크랩 간격
  evaluation_interval: 5s  # 규칙 평가 간격

scrape_configs:
  # Spring Boot 애플리케이션 메트릭
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'    # 메트릭 경로
    static_configs:
      - targets: ['host.docker.internal:8080']  # 대상 서버
    scrape_interval: 5s                     # 5초마다 수집
```

#### Pull 방식 동작 원리

```
┌─────────────┐                    ┌─────────────┐
│ Prometheus  │  GET /actuator/    │ Spring App  │
│             │  prometheus        │             │
│   (수집기)   │ ─────────────────► │  (메트릭    │
│             │                    │   노출)     │
│             │ ◄───────────────── │             │
│             │   메트릭 텍스트     │             │
└─────────────┘                    └─────────────┘
      │
      │ 저장
      ▼
┌─────────────┐
│ TSDB        │
│ (시계열DB)   │
│ 7일 보존    │
└─────────────┘
```

#### 저장 설정 (docker-compose.yml)

```yaml
prometheus:
  command:
    - '--storage.tsdb.retention.time=7d'  # 7일간 데이터 보존
```

---

### 3. Grafana

**역할**: 메트릭 시각화 및 대시보드

#### Prometheus 쿼리 언어 (PromQL) 예시

```promql
# 현재 활성 커넥션 수
hikaricp_connections_active{pool="StockHikariPool"}

# 초당 요청 수 (TPS)
rate(http_server_requests_seconds_count{uri="/api/products"}[1m])

# 응답 시간 95 백분위
histogram_quantile(0.95,
  sum(rate(http_server_requests_seconds_bucket{uri=~"/api/products.*"}[1m])) by (le)
) * 1000

# 커넥션 풀 사용률 (%)
(hikaricp_connections_active / hikaricp_connections_max) * 100
```

---

## Push vs Pull 방식 비교

| 구분 | Pull 방식 (Prometheus) | Push 방식 (InfluxDB 등) |
|------|----------------------|------------------------|
| **동작** | 수집기가 앱에서 데이터를 가져감 | 앱이 수집기로 데이터를 보냄 |
| **주체** | Prometheus가 주도 | 애플리케이션이 주도 |
| **앱 의존성** | 메트릭 노출만 하면 됨 | 전송 로직 필요 |
| **장애 영향** | Prometheus 장애 시 앱 무관 | 수집기 장애 시 데이터 손실 가능 |
| **확장성** | 여러 Prometheus가 같은 앱 스크랩 가능 | 앱이 여러 곳에 Push 필요 |

### Pull 방식의 장점

1. **단순성**: 앱은 `/actuator/prometheus`만 노출하면 됨
2. **독립성**: Prometheus 장애가 앱에 영향 없음
3. **유연성**: 스크랩 간격, 대상 서버를 Prometheus에서 관리
4. **다중 수집**: 여러 Prometheus 인스턴스가 동일 앱 모니터링 가능

---

## 접속 정보

| 서비스 | URL | 용도 |
|--------|-----|------|
| Spring Actuator | http://localhost:8080/actuator/prometheus | 메트릭 원본 데이터 |
| Prometheus | http://localhost:9090 | 메트릭 쿼리 및 확인 |
| Grafana | http://localhost:3000 | 대시보드 (admin/admin) |

---

## 관련 파일

```
├── docker-compose.yml                              # Docker 서비스 정의
├── docker/
│   ├── prometheus/
│   │   └── prometheus.yml                          # Prometheus 설정
│   └── grafana/
│       └── provisioning/
│           ├── datasources/
│           │   └── datasources.yml                 # 데이터소스 설정
│           └── dashboards/
│               └── dbcp-stress-test.json           # 대시보드 정의
└── src/main/resources/
    └── application-tuned.yml                       # Actuator 설정
```
