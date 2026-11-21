// M3 노출 부하: 광고 서버처럼 노출을 BATCH건씩 묶어 delivery 수집 API로 보낸다.
// DUP 비율만큼은 이미 보낸 노출을 같은 eventId로 다시 보낸다(재전송). 202를 못 받으면 같은 본문을 다시 보낸다.
// 사용: docker run --rm -i --network ad-commerce-lab_default -e CAMPAIGNS=id1,id2 -e RATE=50 -e BATCH=100 grafana/k6:2.4.0 run - < scripts/k6/m3.js
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const URL = (__ENV.DELIVERY || 'http://delivery:8084') + '/impressions';
const CAMPAIGNS = (__ENV.CAMPAIGNS || '').split(',').filter(Boolean);
const HOT = __ENV.HOT || ''; // 노출을 몰아줄 캠페인(S8)
const HOT_SHARE = Number(__ENV.HOT_SHARE || 0);
const BATCH = Number(__ENV.BATCH || 100);
const DUP = Number(__ENV.DUP || 0);

const uniqueEvents = new Counter('unique_events');
const hotEvents = new Counter('hot_unique_events');
const dupEvents = new Counter('dup_events');
const abandoned = new Counter('abandoned_events');
const retries = new Counter('retries');

export const options = {
  scenarios: {
    impressions: {
      executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 50), timeUnit: '1s',
      duration: __ENV.DURATION || '60s', preAllocatedVUs: 100, maxVUs: 600,
    },
  },
  thresholds: { http_req_duration: ['p(95)>=0'] }, // 요약에 남기려는 것(합격 판정 아님)
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
};

let recent = []; // 가상 사용자마다 최근에 보낸 노출(재전송 후보)

function campaign() {
  return HOT && Math.random() < HOT_SHARE ? HOT : CAMPAIGNS[Math.floor(Math.random() * CAMPAIGNS.length)];
}

export default function () {
  const events = [];
  for (let i = 0; i < BATCH; i++) {
    if (recent.length > 0 && Math.random() < DUP) {
      events.push(recent[Math.floor(Math.random() * recent.length)]);
      dupEvents.add(1);
    } else {
      const e = { eventId: crypto.randomUUID(), campaignId: campaign(), placement: 'search-top', occurredAt: new Date().toISOString() };
      events.push(e);
      uniqueEvents.add(1);
      if (e.campaignId === HOT) hotEvents.add(1);
    }
  }
  recent = recent.concat(events).slice(-1000);
  const body = JSON.stringify(events);
  for (let attempt = 0; attempt < 60; attempt++) {
    if (attempt > 0) { retries.add(1); sleep(1); }
    const r = http.post(URL, body, { headers: { 'Content-Type': 'application/json' }, timeout: '10s' });
    if (r.status === 202) return;
  }
  abandoned.add(BATCH); // 1분 넘게 못 보냄. 이 노출이 기록됐는지는 알 수 없다
}

export function handleSummary(data) {
  const v = (name) => (data.metrics[name] || {}).values || {};
  const d = v('http_req_duration');
  const summary = {
    requests: v('http_reqs').count,
    p50ms: Math.round(d['p(50)']), p95ms: Math.round(d['p(95)']), p99ms: Math.round(d['p(99)']), maxms: Math.round(d.max),
    failedRequests: v('http_req_failed').passes || 0,
    retries: v('retries').count || 0,
    uniqueEvents: v('unique_events').count || 0,
    hotUniqueEvents: v('hot_unique_events').count || 0,
    dupEvents: v('dup_events').count || 0,
    abandonedEvents: v('abandoned_events').count || 0,
    droppedIterations: v('dropped_iterations').count || 0,
    seconds: Math.round(data.state.testRunDurationMs / 1000),
  };
  return { stdout: `SUMMARY ${JSON.stringify(summary)}\n` };
}
