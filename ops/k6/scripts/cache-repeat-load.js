import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const target = (__ENV.TARGET || 'location-search').trim();
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '0.2');
const failureLogLimitPerVu = Number(__ENV.FAILURE_LOG_LIMIT_PER_VU || '3');

const validResponseRate = new Rate('cache_repeat_valid_response');
const resultCount = new Trend('cache_repeat_result_count');
const failedResponses = new Counter('cache_repeat_failed_responses');
const sharedNoticeResponses = new Counter('cache_repeat_notice_shared_responses');
const latestNoticeCheckFailures = new Counter('cache_repeat_notice_latest_check_failures');

let failureLogCount = 0;

export const options = {
  vus: Number(__ENV.VUS || '5'),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: [__ENV.HTTP_P95_THRESHOLD || 'p(95)<5000'],
    cache_repeat_valid_response: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

function cookieHeader() {
  if (__ENV.COOKIE_HEADER) {
    return __ENV.COOKIE_HEADER;
  }
  if (__ENV.ACCESS_TOKEN) {
    return `swimpulse_access_token=${__ENV.ACCESS_TOKEN}`;
  }
  return null;
}

function buildRequest() {
  if (target === 'location-search') {
    const query = __ENV.QUERY || '서울 수영장';
    const display = __ENV.DISPLAY || '10';
    const latitude = __ENV.LATITUDE;
    const longitude = __ENV.LONGITUDE;
    const params = [
      `query=${encodeURIComponent(query)}`,
      `display=${encodeURIComponent(display)}`,
    ];
    if (latitude) {
      params.push(`latitude=${encodeURIComponent(latitude)}`);
    }
    if (longitude) {
      params.push(`longitude=${encodeURIComponent(longitude)}`);
    }
    return {
      method: 'GET',
      url: `${baseUrl}/api/locations/search?${params.join('&')}`,
      endpoint: '/api/locations/search',
    };
  }

  if (target === 'geocode') {
    const address = __ENV.ADDRESS || '서울특별시 중구 세종대로 110';
    return {
      method: 'GET',
      url: `${baseUrl}/api/locations/geocode?address=${encodeURIComponent(address)}`,
      endpoint: '/api/locations/geocode',
    };
  }

  if (target === 'reverse-geocode') {
    const latitude = __ENV.LATITUDE || '37.5665';
    const longitude = __ENV.LONGITUDE || '126.9780';
    return {
      method: 'GET',
      url: `${baseUrl}/api/locations/reverse-geocode?latitude=${encodeURIComponent(latitude)}&longitude=${encodeURIComponent(longitude)}`,
      endpoint: '/api/locations/reverse-geocode',
    };
  }

  if (target === 'nearby') {
    const latitude = __ENV.LATITUDE || '37.5665';
    const longitude = __ENV.LONGITUDE || '126.9780';
    const limit = __ENV.LIMIT || '20';
    return {
      method: 'GET',
      url: `${baseUrl}/api/pools/nearby?latitude=${encodeURIComponent(latitude)}&longitude=${encodeURIComponent(longitude)}&limit=${encodeURIComponent(limit)}`,
      endpoint: '/api/pools/nearby',
    };
  }

  if (target === 'pool-location-candidates') {
    const latitude = __ENV.LATITUDE || '37.5665';
    const longitude = __ENV.LONGITUDE || '126.9780';
    const radius = __ENV.RADIUS || '5000';
    const query = __ENV.QUERY;
    const params = [
      `latitude=${encodeURIComponent(latitude)}`,
      `longitude=${encodeURIComponent(longitude)}`,
      `radius=${encodeURIComponent(radius)}`,
    ];
    if (query) {
      params.push(`query=${encodeURIComponent(query)}`);
    }
    return {
      method: 'GET',
      url: `${baseUrl}/api/pools/location-candidates?${params.join('&')}`,
      endpoint: '/api/pools/location-candidates',
    };
  }

  if (target === 'notice-scan') {
    const poolId = __ENV.POOL_ID || '16';
    return {
      method: 'POST',
      url: `${baseUrl}/api/pools/${encodeURIComponent(poolId)}/notices/scan`,
      endpoint: '/api/pools/{poolId}/notices/scan',
      poolId,
    };
  }

  throw new Error(`Unsupported TARGET: ${target}`);
}

function execute(request) {
  const params = {
    tags: {
      scenario: 'cache_repeat_load',
      target,
      endpoint: request.endpoint,
    },
  };

  if (target === 'notice-scan') {
    const cookie = cookieHeader();
    if (!cookie) {
      throw new Error('Set COOKIE_HEADER or ACCESS_TOKEN for TARGET=notice-scan.');
    }
    params.headers = { Cookie: cookie };
    return http.post(request.url, null, params);
  }

  return http.get(request.url, params);
}

function validateResponse(response, request) {
  if (target === 'location-search' || target === 'nearby' || target === 'pool-location-candidates') {
    let body = null;
    try {
      body = response.json();
    } catch (error) {
      body = null;
    }
    const valid = response.status === 200 && Array.isArray(body);
    if (valid) {
      resultCount.add(body.length, { target });
    }
    return {
      valid,
      checks: {
        'cache repeat status is 200': response.status === 200,
        'cache repeat response is array': Array.isArray(body),
      },
    };
  }

  if (target === 'geocode' || target === 'reverse-geocode') {
    let body = null;
    try {
      body = response.json();
    } catch (error) {
      body = null;
    }
    const valid =
      response.status === 200 &&
      body !== null &&
      typeof body.address === 'string' &&
      typeof body.latitude === 'number' &&
      typeof body.longitude === 'number';
    if (valid) {
      resultCount.add(1, { target });
    }
    return {
      valid,
      checks: {
        'cache repeat status is 200': response.status === 200,
        'cache repeat response has address': body !== null && typeof body.address === 'string',
        'cache repeat response has coordinates':
          body !== null && typeof body.latitude === 'number' && typeof body.longitude === 'number',
      },
    };
  }

  if (target === 'notice-scan') {
    let body = null;
    try {
      body = response.json();
    } catch (error) {
      body = null;
    }
    const valid =
      response.status === 200 &&
      body !== null &&
      String(body.poolId) === String(request.poolId) &&
      Array.isArray(body.notices) &&
      typeof body.message === 'string';
    if (valid) {
      resultCount.add(body.notices.length, { target });
      if (body.sharedResult === true) {
        sharedNoticeResponses.add(1, { pool_id: request.poolId });
      }
      if (body.latestCheckFailed === true) {
        latestNoticeCheckFailures.add(1, { pool_id: request.poolId });
      }
    }
    return {
      valid,
      checks: {
        'cache repeat status is 200': response.status === 200,
        'cache repeat notice response has notices': body !== null && Array.isArray(body.notices),
        'cache repeat notice response has message': body !== null && typeof body.message === 'string',
        'cache repeat notice response matches pool': body !== null && String(body.poolId) === String(request.poolId),
      },
    };
  }

  return {
    valid: false,
    checks: {
      'cache repeat unsupported target': false,
    },
  };
}

function failureSummary(response) {
  if (response.error) {
    return String(response.error).slice(0, 180);
  }

  if (!response.body) {
    return '<empty body>';
  }

  try {
    const body = response.json();
    const parts = [];
    if (body.status !== undefined) {
      parts.push(`status=${body.status}`);
    }
    if (body.error) {
      parts.push(`error=${body.error}`);
    }
    if (body.message) {
      parts.push(`message=${body.message}`);
    }
    if (body.path) {
      parts.push(`path=${body.path}`);
    }
    if (parts.length > 0) {
      return parts.join(' | ').replace(/\s+/g, ' ').slice(0, 240);
    }
  } catch (error) {
    // Fall through to plain body snippet.
  }

  return String(response.body).replace(/\s+/g, ' ').slice(0, 240);
}

function recordFailure(response, request, validation) {
  if (validation.valid) {
    return;
  }

  const summary = failureSummary(response);
  failedResponses.add(1, {
    target,
    endpoint: request.endpoint,
    status: String(response.status || 'network_error'),
    failure_summary: summary,
  });

  if (failureLogCount >= failureLogLimitPerVu) {
    return;
  }

  failureLogCount += 1;
  console.warn(
    `[cache-repeat-failure] vu=${__VU} iter=${__ITER} target=${target} status=${response.status} url=${request.url} body=${summary}`,
  );
}

export default function () {
  const request = buildRequest();
  const response = execute(request);
  const validation = validateResponse(response, request);

  check(response, validation.checks);
  validResponseRate.add(validation.valid, { target });
  recordFailure(response, request, validation);

  sleep(pauseSeconds);
}
