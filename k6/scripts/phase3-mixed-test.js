/**
 * Phase 3: 혼합 테스트 (읽기 80% + 쓰기 20%)
 *
 * 목적: 실제 트래픽 패턴을 시뮬레이션하여 HikariCP 임계점 측정
 *
 * 테스트 시나리오:
 * 1. 읽기:쓰기 = 8:2 비율로 혼합
 * 2. 점진적 부하 증가
 * 3. 실제 워크로드에서의 Connection Pool 동작 관찰
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ===========================================
// 커스텀 메트릭 정의
// ===========================================
const errorRate = new Rate('error_rate');
const connectionErrors = new Counter('connection_errors');
const readLatency = new Trend('read_latency');
const writeLatency = new Trend('write_latency');
const readCount = new Counter('read_operations');
const writeCount = new Counter('write_operations');

// ===========================================
// 테스트 설정
// ===========================================
export const options = {
  scenarios: {
    mixed_load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        // Warm-up
        { duration: '30s', target: 5 },

        // 저부하
        { duration: '1m', target: 10 },

        // 중부하 - Pool 근접
        { duration: '1m', target: 20 },

        // 고부하 - Pool 초과
        { duration: '1m', target: 35 },

        // 스트레스
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

  // 성능 임계값
  thresholds: {
    http_req_duration: ['p(95)<2500'],
    error_rate: ['rate<0.1'],
    read_latency: ['p(95)<1500'],
    write_latency: ['p(95)<3000'],
  },
};

// ===========================================
// 환경 변수
// ===========================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_COUNT = parseInt(__ENV.PRODUCT_COUNT) || 100;
const READ_RATIO = parseFloat(__ENV.READ_RATIO) || 0.8;  // 80% 읽기

// ===========================================
// 테스트 데이터 생성
// ===========================================
const categories = ['상의', '하의', '아우터', '원피스', '신발', '액세서리'];
const brands = ['ZARA', 'H&M', 'UNIQLO', '무신사스탠다드', '에잇세컨즈', '탑텐'];

function generateProductData() {
  const timestamp = Date.now();
  const random = Math.floor(Math.random() * 10000);

  return {
    name: `혼합테스트 상품 ${timestamp}-${random}`,
    category: categories[Math.floor(Math.random() * categories.length)],
    price: Math.floor(Math.random() * 180000) + 20000,
    brand: brands[Math.floor(Math.random() * brands.length)],
  };
}

// ===========================================
// 읽기 작업
// ===========================================
function performRead() {
  const productId = Math.floor(Math.random() * PRODUCT_COUNT) + 1;
  const isListQuery = Math.random() < 0.3;  // 30% 목록 조회, 70% 단일 조회

  let response;
  let operationType;

  if (isListQuery) {
    const page = Math.floor(Math.random() * 10);
    response = http.get(`${BASE_URL}/api/products?page=${page}&size=10`, {
      tags: { name: 'ReadOperation', type: 'list' },
      timeout: '30s',
    });
    operationType = 'list';
  } else {
    response = http.get(`${BASE_URL}/api/products/${productId}`, {
      tags: { name: 'ReadOperation', type: 'single' },
      timeout: '30s',
    });
    operationType = 'single';
  }

  const success = check(response, {
    [`read(${operationType}): status is 200`]: (r) => r.status === 200,
    [`read(${operationType}): response time ok`]: (r) => r.timings.duration < 1500,
  });

  readLatency.add(response.timings.duration);
  readCount.add(1);

  return { success, status: response.status };
}

// ===========================================
// 쓰기 작업
// ===========================================
function performWrite() {
  const productData = generateProductData();

  const response = http.post(
    `${BASE_URL}/api/products`,
    JSON.stringify(productData),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'WriteOperation' },
      timeout: '30s',
    }
  );

  const success = check(response, {
    'write: status is 201': (r) => r.status === 201,
    'write: response time ok': (r) => r.timings.duration < 3000,
  });

  writeLatency.add(response.timings.duration);
  writeCount.add(1);

  return { success, status: response.status };
}

// ===========================================
// 메인 테스트 함수
// ===========================================
export default function () {
  // 읽기/쓰기 결정 (기본 80:20)
  const isRead = Math.random() < READ_RATIO;

  let result;
  if (isRead) {
    result = performRead();
  } else {
    result = performWrite();
  }

  // 에러 기록
  if (!result.success) {
    errorRate.add(1);
    if (result.status >= 500 || result.status === 0) {
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
    [`reports/phase3-mixed-${timestamp}.json`]: JSON.stringify(data, null, 2),
    stdout: generateTextSummary(data),
  };
}

function generateTextSummary(data) {
  const metrics = data.metrics;

  const readOps = metrics.read_operations?.values?.count || 0;
  const writeOps = metrics.write_operations?.values?.count || 0;
  const totalOps = readOps + writeOps;
  const actualReadRatio = totalOps > 0 ? ((readOps / totalOps) * 100).toFixed(1) : 0;

  return `
================================================================================
                      Phase 3: 혼합 테스트 결과 (읽기:쓰기 = ${actualReadRatio}%:${(100 - actualReadRatio).toFixed(1)}%)
================================================================================

📊 HTTP 요청 통계
--------------------------------------------------------------------------------
  총 요청 수:        ${metrics.http_reqs?.values?.count || 0}
  읽기 작업:         ${readOps}
  쓰기 작업:         ${writeOps}
  평균 응답 시간:     ${(metrics.http_req_duration?.values?.avg || 0).toFixed(2)}ms
  P50 응답 시간:     ${(metrics.http_req_duration?.values?.['p(50)'] || 0).toFixed(2)}ms
  P95 응답 시간:     ${(metrics.http_req_duration?.values?.['p(95)'] || 0).toFixed(2)}ms
  P99 응답 시간:     ${(metrics.http_req_duration?.values?.['p(99)'] || 0).toFixed(2)}ms
  최대 응답 시간:     ${(metrics.http_req_duration?.values?.max || 0).toFixed(2)}ms

🚨 에러 통계
--------------------------------------------------------------------------------
  에러율:            ${((metrics.error_rate?.values?.rate || 0) * 100).toFixed(2)}%
  커넥션 에러:       ${metrics.connection_errors?.values?.count || 0}

📈 작업별 레이턴시
--------------------------------------------------------------------------------
  읽기 작업 P95:     ${(metrics.read_latency?.values?.['p(95)'] || 0).toFixed(2)}ms
  쓰기 작업 P95:     ${(metrics.write_latency?.values?.['p(95)'] || 0).toFixed(2)}ms

================================================================================
`;
}
