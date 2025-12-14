/**
 * Phase 2: 쓰기 테스트 (INSERT)
 *
 * 목적: HikariCP 기본 설정에서 쓰기 작업의 임계점 측정
 *
 * 테스트 시나리오:
 * 1. 점진적으로 VU 증가
 * 2. 상품 생성 API 반복 호출
 * 3. 트랜잭션 처리 시 Connection Pool 포화 관찰
 *
 * 주의: 쓰기 작업은 읽기보다 커넥션 점유 시간이 길어 더 빨리 포화됨
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ===========================================
// 커스텀 메트릭 정의
// ===========================================
const errorRate = new Rate('error_rate');
const connectionErrors = new Counter('connection_errors');
const createProductLatency = new Trend('create_product_latency');
const successfulCreates = new Counter('successful_creates');

// ===========================================
// 테스트 설정
// ===========================================
export const options = {
  scenarios: {
    write_load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        // Warm-up
        { duration: '30s', target: 3 },

        // Pool Size 절반
        { duration: '1m', target: 5 },

        // Pool Size 근접
        { duration: '1m', target: 10 },

        // Pool 초과 시작
        { duration: '1m', target: 15 },

        // 스트레스 테스트
        { duration: '1m', target: 20 },

        // 고부하
        { duration: '1m', target: 30 },

        // Cool-down
        { duration: '30s', target: 0 },
      ],
    },
  },

  // 성능 임계값 (쓰기는 읽기보다 관대하게)
  thresholds: {
    http_req_duration: ['p(95)<3000'],      // 95%ile < 3초
    error_rate: ['rate<0.15'],               // 에러율 < 15%
    create_product_latency: ['p(95)<2500'],  // 생성 API < 2.5초
  },
};

// ===========================================
// 환경 변수
// ===========================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// ===========================================
// 테스트 데이터 생성
// ===========================================
const categories = ['상의', '하의', '아우터', '원피스', '신발', '액세서리'];
const brands = ['ZARA', 'H&M', 'UNIQLO', '무신사스탠다드', '에잇세컨즈', '탑텐'];

function generateProductData() {
  const timestamp = Date.now();
  const random = Math.floor(Math.random() * 10000);

  return {
    name: `부하테스트 상품 ${timestamp}-${random}`,
    category: categories[Math.floor(Math.random() * categories.length)],
    price: Math.floor(Math.random() * 180000) + 20000,
    brand: brands[Math.floor(Math.random() * brands.length)],
  };
}

// ===========================================
// 메인 테스트 함수
// ===========================================
export default function () {
  const productData = generateProductData();

  const response = http.post(
    `${BASE_URL}/api/products`,
    JSON.stringify(productData),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: { name: 'CreateProduct' },
      timeout: '30s',
    }
  );

  const success = check(response, {
    'create: status is 201': (r) => r.status === 201,
    'create: has product id': (r) => {
      try {
        return JSON.parse(r.body).id !== undefined;
      } catch {
        return false;
      }
    },
    'create: response time < 2.5s': (r) => r.timings.duration < 2500,
  });

  // 메트릭 기록
  createProductLatency.add(response.timings.duration);

  if (success) {
    successfulCreates.add(1);
    errorRate.add(0);
  } else {
    errorRate.add(1);
    if (response.status >= 500 || response.status === 0) {
      connectionErrors.add(1);
      console.log(`Connection Error: status=${response.status}, body=${response.body}`);
    }
  }

  // 쓰기 작업 간 대기 (DB 부하 분산)
  sleep(0.2);
}

// ===========================================
// 테스트 완료 후 요약 리포트
// ===========================================
export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');

  return {
    [`reports/phase2-write-${timestamp}.json`]: JSON.stringify(data, null, 2),
    stdout: generateTextSummary(data),
  };
}

function generateTextSummary(data) {
  const metrics = data.metrics;

  return `
================================================================================
                         Phase 2: 쓰기 테스트 결과
================================================================================

📊 HTTP 요청 통계
--------------------------------------------------------------------------------
  총 요청 수:        ${metrics.http_reqs?.values?.count || 0}
  성공적인 생성:     ${metrics.successful_creates?.values?.count || 0}
  평균 응답 시간:     ${(metrics.http_req_duration?.values?.avg || 0).toFixed(2)}ms
  P50 응답 시간:     ${(metrics.http_req_duration?.values?.['p(50)'] || 0).toFixed(2)}ms
  P95 응답 시간:     ${(metrics.http_req_duration?.values?.['p(95)'] || 0).toFixed(2)}ms
  P99 응답 시간:     ${(metrics.http_req_duration?.values?.['p(99)'] || 0).toFixed(2)}ms
  최대 응답 시간:     ${(metrics.http_req_duration?.values?.max || 0).toFixed(2)}ms

🚨 에러 통계
--------------------------------------------------------------------------------
  에러율:            ${((metrics.error_rate?.values?.rate || 0) * 100).toFixed(2)}%
  커넥션 에러:       ${metrics.connection_errors?.values?.count || 0}

📈 쓰기 성능
--------------------------------------------------------------------------------
  상품 생성 P95:     ${(metrics.create_product_latency?.values?.['p(95)'] || 0).toFixed(2)}ms

================================================================================
`;
}
