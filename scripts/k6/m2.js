// M2 실험 부하: 주문(POST /api/orders)과 조회(GET /api/campaigns)를 일정한 속도로 동시에 보낸다.
// 사용: docker run --rm -i --network ad-commerce-lab_default -e ORDER_RATE=5 -e READ_RATE=20 -e DURATION=60s grafana/k6:2.4.0 run - < scripts/k6/m2.js
import http from 'k6/http';

const BASE = __ENV.BASE || 'http://gateway:8080';
const RUN = __ENV.RUN || 'run';
const DURATION = __ENV.DURATION || '60s';

const scenario = (rate, exec) => ({
  executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: DURATION,
  preAllocatedVUs: 50, maxVUs: 400, exec,
});

export const options = {
  scenarios: {
    orders: scenario(Number(__ENV.ORDER_RATE || 5), 'order'),
    reads: scenario(Number(__ENV.READ_RATE || 20), 'read'),
  },
  // 시나리오별 숫자를 요약에 남기려고 건 임계값이다(합격 판정용 아님).
  thresholds: {
    'http_req_duration{scenario:orders}': ['p(95)>=0'],
    'http_req_duration{scenario:reads}': ['p(95)>=0'],
    'http_req_failed{scenario:orders}': ['rate>=0'],
    'http_req_failed{scenario:reads}': ['rate>=0'],
    'http_reqs{scenario:orders}': ['count>=0'],
    'http_reqs{scenario:reads}': ['count>=0'],
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
};

const headers = { 'X-Api-Key': 'demo-partner-key', 'Content-Type': 'application/json' };
const body = JSON.stringify({ productId: 1, budget: 300000, startDate: '2026-12-01', endDate: '2026-12-07' });

export function order() {
  const key = `${RUN}-${__VU}-${__ITER}-${Date.now()}`;
  http.post(`${BASE}/api/orders`, body, { headers: { ...headers, 'Idempotency-Key': key }, timeout: '10s' });
}

export function read() {
  http.get(`${BASE}/api/campaigns`, { headers, timeout: '10s' });
}

export function handleSummary(data) {
  const v = (name) => (data.metrics[name] || {}).values || {};
  const pick = (s) => ({
    count: v(`http_reqs{scenario:${s}}`).count,
    p50ms: Math.round(v(`http_req_duration{scenario:${s}}`)['p(50)']),
    p95ms: Math.round(v(`http_req_duration{scenario:${s}}`)['p(95)']),
    maxms: Math.round(v(`http_req_duration{scenario:${s}}`).max),
    failRate: Number((v(`http_req_failed{scenario:${s}}`).rate || 0).toFixed(3)),
  });
  const summary = { orders: pick('orders'), reads: pick('reads'), dropped: v('dropped_iterations').count || 0 };
  return { stdout: `SUMMARY ${JSON.stringify(summary)}\n` };
}
