import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '0.5');
const poolIds = (__ENV.POOL_IDS || '1,2,3,4,5')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);

if (poolIds.length === 0) {
  throw new Error('Set POOL_IDS with at least one pool id.');
}

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
  vus: Number(__ENV.VUS || '5'),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<5000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const poolId = poolIds[(__VU + __ITER) % poolIds.length];
  const response = http.post(
    `${baseUrl}/api/pools/${poolId}/notices/scan`,
    null,
    {
      headers: {
        Cookie: cookieHeader(),
      },
      tags: {
        scenario: 'notice_scan_multi_pool_load',
        endpoint: '/api/pools/{poolId}/notices/scan',
        pool_group: 'multi',
      },
    },
  );

  check(response, {
    'notice scan status is 200 or 400 or 401': (r) => [200, 400, 401].includes(r.status),
  });

  sleep(pauseSeconds);
}
