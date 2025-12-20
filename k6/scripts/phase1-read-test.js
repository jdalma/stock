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
        // Warm-up
        { duration: '20s', target: 10 },

        // Tomcat 스레드 포화 (max: 100)
        { duration: '30s', target: 50 },

        // Tomcat 초과
        { duration: '30s', target: 100 },

        // 고부하
        { duration: '30s', target: 125 },

        // 최대 부하
        { duration: '30s', target: 150 },

        // Cool-down
        { duration: '20s', target: 0 },
      ],
    },
  },

  // 성능 임계값 설정
  thresholds: {
    http_req_duration: ['p(95)<3000'],       // 95%ile 응답시간 < 3초
    error_rate: ['rate<0.1'],                // 에러율 < 10%
    single_product_latency: ['p(95)<3000'],  // 단일 조회 < 3초
    list_product_latency: ['p(95)<3000'],    // 목록 조회 < 3초
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
    'single: response time < 3s': (r) => r.timings.duration < 3000,
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
    'list: response time < 3s': (r) => r.timings.duration < 3000,
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
}
