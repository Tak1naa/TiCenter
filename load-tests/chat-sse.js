import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const serverFailures = new Rate('server_failures');
const badOverload = new Rate('overload_without_retry_after');

export const options = {
  scenarios: {
    online_connections: { executor: 'constant-vus', vus: Number(__ENV.VUS || 1000), duration: __ENV.DURATION || '30m' },
  },
  thresholds: {
    checks: ['rate>0.98'],
    server_failures: ['rate==0'],
    overload_without_retry_after: ['rate==0'],
  },
};

const base = __ENV.BASE_URL || 'http://localhost';
const cookie = __ENV.SESSION_COOKIE;

export default function () {
  const response = http.post(`${base}/api/chat/ask`, JSON.stringify({
    question: '请根据资料概括本章核心概念', selectedFileIds: [], provider: 'load-test',
  }), { headers: { 'Content-Type': 'application/json', Cookie: cookie || '', 'X-CSRF-Token': __ENV.CSRF_TOKEN || '' }, timeout: '45s' });
  check(response, {
    'success or explainable overload': r => r.status === 200 || (r.status === 429 && !!r.headers['Retry-After']),
    'not server failure': r => r.status < 500,
  });
  serverFailures.add(response.status >= 500);
  badOverload.add(response.status === 429 && !response.headers['Retry-After']);
  sleep(1);
}
