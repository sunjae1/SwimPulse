import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const runLabel = (__ENV.RUN_LABEL || 'baseline').trim();
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '0.5');
const failureLogLimitPerVu = Number(__ENV.FAILURE_LOG_LIMIT_PER_VU || '3');
const poolId = Number(__ENV.POOL_ID || '1');
const title = __ENV.TITLE || 'k6 subscription load test';
const noticeRegistrationPeriodId = __ENV.NOTICE_REGISTRATION_PERIOD_ID
  ? Number(__ENV.NOTICE_REGISTRATION_PERIOD_ID)
  : null;
const unsubscribeAfter = (__ENV.UNSUBSCRIBE_AFTER || 'false').toLowerCase() === 'true';
const uniqueEvents = (__ENV.UNIQUE_EVENTS || 'false').toLowerCase() === 'true';
const loadtestTokenCount = Number(__ENV.LOADTEST_TOKEN_COUNT || '0');
const accessTokens = (__ENV.ACCESS_TOKENS || __ENV.ACCESS_TOKEN || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);

const validResponseRate = new Rate('subscription_valid_response');
const responseDuration = new Trend('subscription_duration');
const createdOrReusedSubscriptions = new Counter('subscription_created_or_reused');
const unsubscribedSubscriptions = new Counter('subscription_unsubscribed');
const failedResponses = new Counter('subscription_failed_responses');

let failureLogCount = 0;

if (accessTokens.length === 0 && !__ENV.COOKIE_HEADER && loadtestTokenCount <= 0) {
  throw new Error('Set ACCESS_TOKEN, ACCESS_TOKENS, COOKIE_HEADER, or LOADTEST_TOKEN_COUNT for authenticated subscription load test.');
}

export const options = {
  vus: Number(__ENV.VUS || '5'),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: [__ENV.HTTP_P95_THRESHOLD || 'p(95)<1000'],
    subscription_valid_response: ['rate>0.95'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
	const timing = {
		registrationStartsAt: __ENV.REGISTRATION_STARTS_AT || isoAfter(2),
		registrationEndsAt: __ENV.REGISTRATION_ENDS_AT || isoAfter(7),
	};
	if (__ENV.COOKIE_HEADER || accessTokens.length > 0 || loadtestTokenCount <= 0) {
		return {
			tokens: accessTokens,
			registrationStartsAt: timing.registrationStartsAt,
			registrationEndsAt: timing.registrationEndsAt,
		};
	}

  const response = http.post(`${baseUrl}/internal/loadtest/auth/tokens?count=${encodeURIComponent(loadtestTokenCount)}`, null, {
    tags: {
      scenario: 'subscription_load',
      run_label: runLabel,
      endpoint: '/internal/loadtest/auth/tokens',
      operation: 'issue_loadtest_tokens',
    },
  });
  if (response.status !== 200) {
    throw new Error(`Failed to issue loadtest tokens. status=${response.status} body=${String(response.body).slice(0, 240)}`);
  }

  const body = response.json();
  if (!body || !Array.isArray(body.tokens) || body.tokens.length === 0) {
    throw new Error('Loadtest token response does not contain tokens.');
  }
	return {
		tokens: body.tokens.map((entry) => entry.token),
		registrationStartsAt: timing.registrationStartsAt,
		registrationEndsAt: timing.registrationEndsAt,
	};
}

function cookieHeader(data) {
  if (__ENV.COOKIE_HEADER) {
    return __ENV.COOKIE_HEADER;
  }
  const tokens = data && Array.isArray(data.tokens) && data.tokens.length > 0 ? data.tokens : accessTokens;
  const token = tokens[(__VU - 1) % tokens.length];
  return `swimpulse_access_token=${token}`;
}

function isoAfter(days, minutesOffset = 0) {
	return new Date(Date.now() + days * 24 * 60 * 60 * 1000 + minutesOffset * 60 * 1000).toISOString();
}

function isoFrom(baseIso, minutesOffset = 0) {
	return new Date(Date.parse(baseIso) + minutesOffset * 60 * 1000).toISOString();
}

function requestBody(data) {
	const offset = uniqueEvents ? (__VU * 100000 + __ITER) * 10 : 0;
	const baseStartsAt = data.registrationStartsAt || __ENV.REGISTRATION_STARTS_AT || isoAfter(2);
	const baseEndsAt = data.registrationEndsAt || __ENV.REGISTRATION_ENDS_AT || isoAfter(7);
	return {
		poolId,
		title: uniqueEvents ? `${title} ${__VU}-${__ITER}` : title,
		registrationStartsAt: offset === 0 ? baseStartsAt : isoFrom(baseStartsAt, offset),
		registrationEndsAt: offset === 0 ? baseEndsAt : isoFrom(baseEndsAt, offset),
		noticeRegistrationPeriodId,
	};
}

function safeJson(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}

function failureSummary(response) {
  if (response.error) {
    return String(response.error).slice(0, 180);
  }
  if (!response.body) {
    return '<empty body>';
  }
  const body = safeJson(response);
  if (body !== null && typeof body === 'object') {
    return [
      body.status === undefined ? null : `status=${body.status}`,
      body.error ? `error=${body.error}` : null,
      body.message ? `message=${body.message}` : null,
      body.path ? `path=${body.path}` : null,
    ]
      .filter(Boolean)
      .join(' | ')
      .replace(/\s+/g, ' ')
      .slice(0, 260);
  }
  return String(response.body).replace(/\s+/g, ' ').slice(0, 260);
}

function recordFailure(response) {
  const summary = failureSummary(response);
  failedResponses.add(1, {
    run_label: runLabel,
    status: String(response.status || 'network_error'),
    failure_summary: summary,
  });
  if (failureLogCount >= failureLogLimitPerVu) {
    return;
  }
  failureLogCount += 1;
  console.warn(`[subscription-failure] vu=${__VU} iter=${__ITER} status=${response.status} body=${summary}`);
}

function unsubscribe(eventId, data) {
  const response = http.del(`${baseUrl}/api/subscriptions?eventId=${encodeURIComponent(eventId)}`, null, {
    headers: { Cookie: cookieHeader(data) },
    tags: {
      scenario: 'subscription_load',
      run_label: runLabel,
      endpoint: '/api/subscriptions',
      operation: 'unsubscribe',
    },
  });
  const valid = response.status === 200 || response.status === 204;
  check(response, {
    'subscription unsubscribe status is 2xx': () => valid,
  });
  if (valid) {
    unsubscribedSubscriptions.add(1, { run_label: runLabel });
  }
}

export default function (data) {
	const response = http.post(`${baseUrl}/api/subscriptions`, JSON.stringify(requestBody(data)), {
    headers: {
      Cookie: cookieHeader(data),
      'Content-Type': 'application/json',
    },
    tags: {
      scenario: 'subscription_load',
      run_label: runLabel,
      endpoint: '/api/subscriptions',
      operation: 'subscribe',
    },
  });

  const body = safeJson(response);
  const valid =
    response.status === 200 &&
    body !== null &&
    typeof body.id === 'number' &&
    body.event !== null &&
    body.event !== undefined &&
    typeof body.event.id === 'number';

  check(response, {
    'subscription status is 200': () => response.status === 200,
    'subscription response has id': () => body !== null && typeof body.id === 'number',
    'subscription response has event': () =>
      body !== null && body.event !== null && body.event !== undefined && typeof body.event.id === 'number',
  });

  validResponseRate.add(valid, { run_label: runLabel });
  responseDuration.add(response.timings.duration, { run_label: runLabel });

  if (!valid) {
    recordFailure(response);
    sleep(pauseSeconds);
    return;
  }

  createdOrReusedSubscriptions.add(1, { run_label: runLabel });
  if (unsubscribeAfter) {
    unsubscribe(body.event.id, data);
  }

  sleep(pauseSeconds);
}
