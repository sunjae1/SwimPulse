import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const runLabel = (__ENV.RUN_LABEL || 'baseline').trim();
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '0.5');
const failureLogLimitPerVu = Number(__ENV.FAILURE_LOG_LIMIT_PER_VU || '3');
const registerDevice = (__ENV.REGISTER_DEVICE || 'true').toLowerCase() === 'true';
const loadtestTokenCount = Number(__ENV.LOADTEST_TOKEN_COUNT || '0');
const setupSubscription = (__ENV.SETUP_SUBSCRIPTION || 'true').toLowerCase() === 'true';
const poolId = Number(__ENV.POOL_ID || '1');
const subscriptionTitle = __ENV.TITLE || 'k6 notification test subscription';
const listPage = Number(__ENV.LIST_PAGE || '0');
const listPageSize = Number(__ENV.LIST_PAGE_SIZE || '20');
const accessTokens = (__ENV.ACCESS_TOKENS || __ENV.ACCESS_TOKEN || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);

const validResponseRate = new Rate('notification_test_valid_response');
const responseDuration = new Trend('notification_test_duration');
const queuedNotifications = new Counter('notification_test_queued');
const deviceRegistrations = new Counter('notification_test_device_registrations');
const notificationListCount = new Trend('notification_test_list_count');
const notificationListTotalCount = new Trend('notification_test_list_total_count');
const notificationListUnreadCount = new Trend('notification_test_list_unread_count');
const notificationListDuration = new Trend('notification_test_list_duration');
const failedResponses = new Counter('notification_test_failed_responses');

let failureLogCount = 0;
let deviceRegistered = false;

if (accessTokens.length === 0 && !__ENV.COOKIE_HEADER && loadtestTokenCount <= 0) {
  throw new Error('Set ACCESS_TOKEN, ACCESS_TOKENS, COOKIE_HEADER, or LOADTEST_TOKEN_COUNT for authenticated notification load test.');
}

export const options = {
  vus: Number(__ENV.VUS || '3'),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: [__ENV.HTTP_P95_THRESHOLD || 'p(95)<1000'],
    notification_test_valid_response: ['rate>0.95'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  if (__ENV.COOKIE_HEADER || accessTokens.length > 0 || loadtestTokenCount <= 0) {
    return { tokens: accessTokens };
  }

  const tokenResponse = http.post(`${baseUrl}/internal/loadtest/auth/tokens?count=${encodeURIComponent(loadtestTokenCount)}`, null, {
    tags: {
      scenario: 'notification_test_load',
      run_label: runLabel,
      endpoint: '/internal/loadtest/auth/tokens',
      operation: 'issue_loadtest_tokens',
    },
  });
  if (tokenResponse.status !== 200) {
    throw new Error(`Failed to issue loadtest tokens. status=${tokenResponse.status} body=${String(tokenResponse.body).slice(0, 240)}`);
  }
  const tokenBody = tokenResponse.json();
  if (!tokenBody || !Array.isArray(tokenBody.tokens) || tokenBody.tokens.length === 0) {
    throw new Error('Loadtest token response does not contain tokens.');
  }

  const tokens = tokenBody.tokens.map((entry) => entry.token);
  if (setupSubscription) {
    for (const token of tokens) {
      ensureSubscription(token);
    }
  }
  return { tokens };
}

function cookieHeader(data) {
  if (__ENV.COOKIE_HEADER) {
    return __ENV.COOKIE_HEADER;
  }
  const tokens = data && Array.isArray(data.tokens) && data.tokens.length > 0 ? data.tokens : accessTokens;
  const token = tokens[(__VU - 1) % tokens.length];
  return `swimpulse_access_token=${token}`;
}

function isoAfter(days) {
  return new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString();
}

function ensureSubscription(token) {
  const response = http.post(
    `${baseUrl}/api/subscriptions`,
    JSON.stringify({
      poolId,
      title: subscriptionTitle,
      registrationStartsAt: __ENV.REGISTRATION_STARTS_AT || isoAfter(2),
      registrationEndsAt: __ENV.REGISTRATION_ENDS_AT || isoAfter(7),
      noticeRegistrationPeriodId: __ENV.NOTICE_REGISTRATION_PERIOD_ID
        ? Number(__ENV.NOTICE_REGISTRATION_PERIOD_ID)
        : null,
    }),
    {
      headers: {
        Cookie: `swimpulse_access_token=${token}`,
        'Content-Type': 'application/json',
      },
      tags: {
        scenario: 'notification_test_load',
        run_label: runLabel,
        endpoint: '/api/subscriptions',
        operation: 'setup_subscription',
      },
    },
  );
  if (response.status !== 200) {
    throw new Error(`Failed to setup subscription for loadtest user. status=${response.status} body=${String(response.body).slice(0, 240)}`);
  }
}

function deviceId() {
  return __ENV.DEVICE_ID || `k6-notification-device-vu-${__VU}`;
}

function fcmToken() {
  return __ENV.FCM_TOKEN || `k6-mock-fcm-token-vu-${__VU}`;
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

function recordFailure(response, operation) {
  const summary = failureSummary(response);
  failedResponses.add(1, {
    run_label: runLabel,
    operation,
    status: String(response.status || 'network_error'),
    failure_summary: summary,
  });
  if (failureLogCount >= failureLogLimitPerVu) {
    return;
  }
  failureLogCount += 1;
  console.warn(`[notification-test-failure] vu=${__VU} iter=${__ITER} operation=${operation} status=${response.status} body=${summary}`);
}

function ensureDeviceRegistered(data) {
  if (!registerDevice || deviceRegistered) {
    return true;
  }
  const response = http.post(
    `${baseUrl}/api/notifications/device-tokens`,
    JSON.stringify({
      deviceId: deviceId(),
      fcmToken: fcmToken(),
    }),
    {
      headers: {
        Cookie: cookieHeader(data),
        'Content-Type': 'application/json',
      },
      tags: {
        scenario: 'notification_test_load',
        run_label: runLabel,
        endpoint: '/api/notifications/device-tokens',
        operation: 'register_device',
      },
    },
  );
  const valid = response.status === 204;
  check(response, {
    'notification device registration status is 204': () => valid,
  });
  if (!valid) {
    recordFailure(response, 'register_device');
    return false;
  }
  deviceRegistered = true;
  deviceRegistrations.add(1, { run_label: runLabel });
  return true;
}

function loadNotifications(data) {
  const response = http.get(`${baseUrl}/api/notifications?page=${encodeURIComponent(listPage)}&size=${encodeURIComponent(listPageSize)}`, {
    headers: { Cookie: cookieHeader(data) },
    tags: {
      scenario: 'notification_test_load',
      run_label: runLabel,
      endpoint: '/api/notifications',
      operation: 'list_notifications',
    },
  });
  const body = safeJson(response);
  const valid =
    response.status === 200 &&
    body !== null &&
    Array.isArray(body.content) &&
    typeof body.totalElements === 'number' &&
    typeof body.unreadElements === 'number';
  check(response, {
    'notification list status is 200': () => response.status === 200,
    'notification list response is page': () => body !== null && Array.isArray(body.content),
  });
  if (valid) {
    notificationListCount.add(body.content.length, { run_label: runLabel });
    notificationListTotalCount.add(body.totalElements, { run_label: runLabel });
    notificationListUnreadCount.add(body.unreadElements, { run_label: runLabel });
    notificationListDuration.add(response.timings.duration, { run_label: runLabel });
  } else {
    recordFailure(response, 'list_notifications');
  }
}

export default function (data) {
  if (!ensureDeviceRegistered(data)) {
    validResponseRate.add(false, { run_label: runLabel });
    sleep(pauseSeconds);
    return;
  }

  const response = http.post(`${baseUrl}/api/notifications/test`, null, {
    headers: { Cookie: cookieHeader(data) },
    tags: {
      scenario: 'notification_test_load',
      run_label: runLabel,
      endpoint: '/api/notifications/test',
      operation: 'queue_test_notification',
    },
  });

  const body = safeJson(response);
  const valid =
    response.status === 200 &&
    body !== null &&
    typeof body.id === 'number' &&
    body.status === 'QUEUED';

  check(response, {
    'notification test status is 200': () => response.status === 200,
    'notification test response has id': () => body !== null && typeof body.id === 'number',
    'notification test response is queued': () => body !== null && body.status === 'QUEUED',
  });

  validResponseRate.add(valid, { run_label: runLabel });
  responseDuration.add(response.timings.duration, { run_label: runLabel });

  if (!valid) {
    recordFailure(response, 'queue_test_notification');
    sleep(pauseSeconds);
    return;
  }

  queuedNotifications.add(1, { run_label: runLabel });
  if ((__ENV.LIST_AFTER_QUEUE || 'false').toLowerCase() === 'true') {
    loadNotifications(data);
  }

  sleep(pauseSeconds);
}
