import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const rate = Number(__ENV.LAB_RPS || 100);
const writeRatio = Number(__ENV.LAB_WRITE_RATIO || 0.1);
const duration = __ENV.LAB_DURATION || '30s';
const urls = (__ENV.LAB_URLS || 'http://127.0.0.1:8084,http://127.0.0.1:18084').split(',');
const output = __ENV.LAB_OUTPUT || 'results/summary.json';
const defaultPreAllocated = rate <= 500 ? 50 : Math.min(200, Math.max(50, Math.ceil(rate * 0.2)));
const defaultMax = rate <= 500 ? 200 : Math.min(600, Math.max(200, Math.ceil(rate * 1.5)));
const preAllocatedVUs = Number(__ENV.LAB_PRE_ALLOCATED_VUS || defaultPreAllocated);
const maxVUs = Number(__ENV.LAB_MAX_VUS || defaultMax);
if (!Number.isInteger(rate) || rate < 1 || !Number.isFinite(writeRatio) || writeRatio < 0 || writeRatio > 1 || urls.length !== 2 || !Number.isInteger(preAllocatedVUs) || !Number.isInteger(maxVUs) || preAllocatedVUs < 1 || maxVUs < preAllocatedVUs) {
  throw new Error('Use a positive RPS, write ratio 0..1 and exactly two instance URLs.');
}
export const options = {
  discardResponseBodies: true,
  scenarios: {
    load: {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '5s',
    },
  },
  thresholds: { http_req_failed: ['rate<0.01'] },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};
const reads = new Counter('lab_reads');
const writes = new Counter('lab_writes');
export function setup() {
  const applied = exec.test.options.scenarios;
  if (!applied.load || applied.load.executor !== 'constant-arrival-rate' || applied.load.rate !== rate) {
    throw new Error('Applied executor/rate differs from requested configuration. Inspect native K6_* options.');
  }
}
export default function () {
  let response;
  let method;
  if (Math.random() < writeRatio) {
    // Writes go to app-1, key 1; reads go to app-2, the same key.
    response = http.post(urls[0] + '/items/1/touch', null, { timeout: '5s' });
    method = 'POST';
    writes.add(1);
  } else {
    response = http.get(urls[1] + '/items/1', { timeout: '5s' });
    method = 'GET';
    reads.add(1);
  }
  if (response.status !== 200) {
    console.error(`request failed: method=${method} status=${response.status} error_code=${response.error_code || 'none'} error=${response.error || 'none'}`);
  }
  check(response, { 'HTTP 200': (r) => r.status === 200 });
}
export function handleSummary(data) {
  const values = (name) => data.metrics[name] ? data.metrics[name].values : {};
  const readCount = values('lab_reads').count || 0;
  const writeCount = values('lab_writes').count || 0;
  const actual = values('http_reqs').rate;
  const report = {
    captured_at: new Date().toISOString(),
    requested: { rps: rate, write_ratio: writeRatio, duration, instance_urls: urls, pre_allocated_vus: preAllocatedVUs, max_vus: maxVUs },
    measured: {
      actual_http_rps: actual == null ? null : actual,
      attainment_percent: actual == null ? null : actual / rate * 100,
      reads: readCount, writes: writeCount,
      actual_write_ratio: readCount + writeCount ? writeCount / (readCount + writeCount) : null,
      dropped_iterations: values('dropped_iterations').count || 0,
      active_vus_max: values('vus').max ?? null,
      available_vus_max: values('vus_max').max ?? null,
      http_failure_ratio: values('http_req_failed').rate,
      http_duration_ms: values('http_req_duration'),
      test_run_duration_ms: data.state.testRunDurationMs,
    },
    metrics: data.metrics,
  };
  return { [output]: JSON.stringify(report, null, 2), stdout: JSON.stringify(report.measured, null, 2) + '\n' };
}
