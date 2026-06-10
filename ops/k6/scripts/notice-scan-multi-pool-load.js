import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '0.5');
const poolIds = (__ENV.POOL_IDS || '10,13,16,22,23,28,30,32,33,36')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);

const functionalFailureRate = new Rate('notice_scan_functional_failed');
const responsesWithNotices = new Counter('notice_scan_responses_with_notices');
const responsesWithoutNotices = new Counter('notice_scan_responses_without_notices');
const sharedResponses = new Counter('notice_scan_shared_responses');
const invalidResponses = new Counter('notice_scan_invalid_responses');

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
    notice_scan_functional_failed: ['rate<0.05'],
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

  let body = null;
  if (response.status === 200) {
    try {
      body = response.json();
    } catch (error) {
      body = null;
    }
  }

  const hasValidNotices = body !== null && Array.isArray(body.notices);
  const hasValidMessage =
    body !== null && typeof body.message === 'string' && body.message.trim().length > 0;
  const matchesRequestedPool =
    body !== null && String(body.poolId) === String(poolId);
  const isFunctionallyValid =
    response.status === 200 &&
    hasValidNotices &&
    hasValidMessage &&
    matchesRequestedPool;

  check(response, {
    'notice scan status is 200': (r) => r.status === 200,
    'notice scan response has notices array': () => hasValidNotices,
    'notice scan response has message': () => hasValidMessage,
    'notice scan response matches requested pool': () => matchesRequestedPool,
  });

  functionalFailureRate.add(!isFunctionallyValid, { pool_id: poolId });
  if (!isFunctionallyValid) {
    invalidResponses.add(1, {
      pool_id: poolId,
      status: String(response.status),
    });
  } else {
    if (body.notices.length > 0) {
      responsesWithNotices.add(1, { pool_id: poolId });
    } else {
      responsesWithoutNotices.add(1, {
        pool_id: poolId,
        message: body.message,
      });
    }
    if (body.sharedResult === true) {
      sharedResponses.add(1, { pool_id: poolId });
    }
  }

  sleep(pauseSeconds);
}
