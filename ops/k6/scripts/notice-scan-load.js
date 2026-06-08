import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const poolId = __ENV.POOL_ID || '1';
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '2');

function cookieHeader() {
  if (__ENV.COOKIE_HEADER) {
    return __ENV.COOKIE_HEADER;
  }
  if (__ENV.ACCESS_TOKEN) {
    return `swimpulse_access_token=${__ENV.ACCESS_TOKEN}`;
  }
  throw new Error('Set COOKIE_HEADER or ACCESS_TOKEN for authenticated notice scan load test.');
}

export const options = {
  vus: Number(__ENV.VUS || '1'),
  duration: __ENV.DURATION || '20s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<5000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const response = http.post(
    `${baseUrl}/api/pools/${poolId}/notices/scan`,
    null,
    {
      headers: {
        Cookie: cookieHeader(),
      },
      tags: {
        scenario: 'notice_scan_load',
        endpoint: '/api/pools/{poolId}/notices/scan',
      },
    }
  );

  check(response, {
    'notice scan status is 200 or 400 or 401': (r) => [200, 400, 401].includes(r.status),
  });

  sleep(pauseSeconds);
}
