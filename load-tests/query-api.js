import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: { query: { executor: 'constant-vus', vus: Number(__ENV.VUS || 100), duration: __ENV.DURATION || '5m' } },
  thresholds: { http_req_duration: ['p(95)<300'], http_req_failed: ['rate==0'], checks: ['rate==1'] },
};

const base = __ENV.BASE_URL || 'http://localhost';
export default function () {
  const response = http.get(`${base}/api/files/tree`, { headers: { Cookie: __ENV.SESSION_COOKIE || '' } });
  check(response, { 'public file tree ok': r => r.status === 200 });
  sleep(0.2);
}
