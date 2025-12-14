/**
 * Phase 1: 읽기 테스트 (SELECT)
 *
 * 목적: HikariCP 기본 설정(pool-size=10)에서 읽기 작업의 임계점 측정
 *
 * 테스트 시나리오:
 * 1. 점진적으로 VU(가상 사용자) 증가
 * 2. 단일 상품 조회 + 상품 목록 조회 반복
 * 3. Connection Pool 포화 시점 관찰
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ===========================================
// 커스텀 메트릭 정의
// ===========================================
const errorRate = new Rate('error_rate');
const connectionErrors = new Counter('connection_errors');
const singleProductLatency = new Trend('single_product_latency');
const listProductLatency = new Trend('list_product_latency');

// ===========================================
// 테스트 설정
// ===========================================
export const options = {
  scenarios: {
    ramping_load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        // Warm-up: 서서히 부하 증가
        { duration: '30s', target: 5 },

        // Pool Size 근접 (10개 커넥션)
        { duration: '1m', target: 10 },

        // Pool 초과 시작 - 대기 발생 예상
        { duration: '1m', target: 20 },

        // 스트레스 테스트
        { duration: '1m', target: 30 },

        // 고부하
        { duration: '1m', target: 50 },

        // 극한 부하
        { duration: '1m', target: 75 },

        // 최대 부하
        { duration: '1m', target: 100 },

        // Cool-down
        { duration: '30s', target: 0 },
      ],
    },
  },

  // 성능 임계값 설정
  thresholds: {
    http_req_duration: ['p(95)<2000'],      // 95%ile 응답시간 < 2초
    error_rate: ['rate<0.1'],                // 에러율 < 10%
    single_product_latency: ['p(95)<1000'],  // 단일 조회 < 1초
    list_product_latency: ['p(95)<1500'],    // 목록 조회 < 1.5초
  },
};

// ===========================================
// 환경 변수
// ===========================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_COUNT = parseInt(__ENV.PRODUCT_COUNT) || 100;

// ===========================================
// 메인 테스트 함수
// ===========================================
export default function () {
  // 랜덤 상품 ID 선택 (1 ~ PRODUCT_COUNT)
  const productId = Math.floor(Math.random() * PRODUCT_COUNT) + 1;

  // -----------------------------------------
  // 1. 단일 상품 조회
  // -----------------------------------------
  const singleResponse = http.get(`${BASE_URL}/api/products/${productId}`, {
    tags: { name: 'GetProductById' },
    timeout: '30s',
  });

  const singleSuccess = check(singleResponse, {
    'single: status is 200': (r) => r.status === 200,
    'single: has product id': (r) => {
      try {
        return JSON.parse(r.body).id !== undefined;
      } catch {
        return false;
      }
    },
    'single: response time < 1s': (r) => r.timings.duration < 1000,
  });

  // 메트릭 기록
  singleProductLatency.add(singleResponse.timings.duration);

  if (!singleSuccess) {
    errorRate.add(1);
    if (singleResponse.status >= 500 || singleResponse.status === 0) {
      connectionErrors.add(1);
    }
  } else {
    errorRate.add(0);
  }

  // 요청 간 짧은 대기 (실제 사용자 시뮬레이션)
  sleep(0.1);

  // -----------------------------------------
  // 2. 상품 목록 조회 (페이징)
  // -----------------------------------------
  const page = Math.floor(Math.random() * 10);
  const size = 10;

  const listResponse = http.get(`${BASE_URL}/api/products?page=${page}&size=${size}`, {
    tags: { name: 'GetProductList' },
    timeout: '30s',
  });

  const listSuccess = check(listResponse, {
    'list: status is 200': (r) => r.status === 200,
    'list: has products array': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.products && Array.isArray(body.products);
      } catch {
        return false;
      }
    },
    'list: response time < 1.5s': (r) => r.timings.duration < 1500,
  });

  // 메트릭 기록
  listProductLatency.add(listResponse.timings.duration);

  if (!listSuccess) {
    errorRate.add(1);
    if (listResponse.status >= 500 || listResponse.status === 0) {
      connectionErrors.add(1);
    }
  } else {
    errorRate.add(0);
  }

  // 요청 간 대기
  sleep(0.1);
}

// ===========================================
// 테스트 완료 후 요약 리포트
// ===========================================
export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');

  return {
    [`reports/phase1-read-${timestamp}.json`]: JSON.stringify(data, null, 2),
    stdout: generateTextSummary(data),
  };
}

function generateTextSummary(data) {
  const metrics = data.metrics;

  return `
================================================================================
                         Phase 1: 읽기 테스트 결과
================================================================================

📊 HTTP 요청 통계
--------------------------------------------------------------------------------
  총 요청 수:        ${metrics.http_reqs?.values?.count || 0}
  평균 응답 시간:     ${(metrics.http_req_duration?.values?.avg || 0).toFixed(2)}ms
  P50 응답 시간:     ${(metrics.http_req_duration?.values?.['p(50)'] || 0).toFixed(2)}ms
  P95 응답 시간:     ${(metrics.http_req_duration?.values?.['p(95)'] || 0).toFixed(2)}ms
  P99 응답 시간:     ${(metrics.http_req_duration?.values?.['p(99)'] || 0).toFixed(2)}ms
  최대 응답 시간:     ${(metrics.http_req_duration?.values?.max || 0).toFixed(2)}ms

🚨 에러 통계
--------------------------------------------------------------------------------
  에러율:            ${((metrics.error_rate?.values?.rate || 0) * 100).toFixed(2)}%
  커넥션 에러:       ${metrics.connection_errors?.values?.count || 0}

📈 API별 레이턴시
--------------------------------------------------------------------------------
  단일 상품 조회 P95: ${(metrics.single_product_latency?.values?.['p(95)'] || 0).toFixed(2)}ms
  상품 목록 조회 P95: ${(metrics.list_product_latency?.values?.['p(95)'] || 0).toFixed(2)}ms

================================================================================
`;
}
