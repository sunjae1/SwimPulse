import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const runLabel = (__ENV.RUN_LABEL || 'scheduler-notification').trim();
const users = Number(__ENV.USER_COUNT || '100');
const poolId = Number(__ENV.POOL_ID || '1');
const titlePrefix = __ENV.TITLE || 'k6 scheduler due notification';
const startOffsetSeconds = Number(__ENV.START_OFFSET_SECONDS || '-5');
const durationMinutes = Number(__ENV.EVENT_DURATION_MINUTES || '60');
const registerDevices = (__ENV.REGISTER_DEVICES || 'true').toLowerCase() === 'true';
const pollTimeoutSeconds = Number(__ENV.POLL_TIMEOUT_SECONDS || '30');
const pollIntervalSeconds = Number(__ENV.POLL_INTERVAL_SECONDS || '1');
const waitForDelivery = (__ENV.WAIT_FOR_DELIVERY || 'true').toLowerCase() === 'true';
const failureLogLimitPerVu = Number(__ENV.FAILURE_LOG_LIMIT_PER_VU || '3');

const validResponseRate = new Rate('scheduler_notification_valid_response');
const tickDuration = new Trend('scheduler_notification_tick_duration');
const totalNotificationCount = new Trend('scheduler_notification_count');
const sentNotificationCount = new Trend('scheduler_notification_sent_count');
const failedNotificationCount = new Trend('scheduler_notification_failed_count');
const redisQueueLength = new Trend('scheduler_notification_redis_queue_length');

let failureLogCount = 0;

export const options = {
  scenarios: {
    default: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || '1'),
      iterations: Number(__ENV.ITERATIONS || '1'),
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: [__ENV.HTTP_P95_THRESHOLD || 'p(95)<5000'],
    scheduler_notification_valid_response: ['rate>0.95'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  const title = `${titlePrefix} ${Date.now()}`;
  const seedUrl =
    `${baseUrl}/internal/loadtest/scheduler-notifications/seed` +
    `?users=${encodeURIComponent(users)}` +
    `&poolId=${encodeURIComponent(poolId)}` +
    `&title=${encodeURIComponent(title)}` +
    `&startOffsetSeconds=${encodeURIComponent(startOffsetSeconds)}` +
    `&durationMinutes=${encodeURIComponent(durationMinutes)}` +
    `&registerDevices=${encodeURIComponent(registerDevices)}`;

  const response = http.post(seedUrl, null, {
    tags: {
      scenario: 'scheduler_notification_load',
      run_label: runLabel,
      endpoint: '/internal/loadtest/scheduler-notifications/seed',
      operation: 'seed_due_event',
    },
  });
  if (response.status !== 200) {
    throw new Error(`Failed to seed scheduler notification load test. status=${response.status} body=${String(response.body).slice(0, 300)}`);
  }
  const body = response.json();
  if (!body || typeof body.eventId !== 'number') {
    throw new Error(`Seed response does not contain eventId. body=${String(response.body).slice(0, 300)}`);
  }
  return {
    eventId: body.eventId,
    expectedNotifications: Number(body.subscriptionCount || users),
    title,
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

function recordFailure(response, operation) {
  if (failureLogCount >= failureLogLimitPerVu) {
    return;
  }
  failureLogCount += 1;
  console.warn(`[scheduler-notification-failure] vu=${__VU} iter=${__ITER} operation=${operation} status=${response.status} body=${failureSummary(response)}`);
}

function getStatus(eventId) {
  return http.get(`${baseUrl}/internal/loadtest/scheduler-notifications/status?eventId=${encodeURIComponent(eventId)}`, {
    tags: {
      scenario: 'scheduler_notification_load',
      run_label: runLabel,
      endpoint: '/internal/loadtest/scheduler-notifications/status',
      operation: 'poll_status',
    },
  });
}

function recordStatusMetrics(status) {
  totalNotificationCount.add(Number(status.notificationCount || 0), { run_label: runLabel });
  sentNotificationCount.add(Number(status.sentCount || 0), { run_label: runLabel });
  failedNotificationCount.add(Number(status.failedCount || 0), { run_label: runLabel });
  redisQueueLength.add(Number(status.redisQueueLength || 0), { run_label: runLabel });
}

export default function (data) {
  const tickResponse = http.post(
    `${baseUrl}/internal/loadtest/scheduler-notifications/tick?eventId=${encodeURIComponent(data.eventId)}`,
    null,
    {
      tags: {
        scenario: 'scheduler_notification_load',
        run_label: runLabel,
        endpoint: '/internal/loadtest/scheduler-notifications/tick',
        operation: 'scheduler_tick',
      },
    },
  );

  tickDuration.add(tickResponse.timings.duration, { run_label: runLabel });
  const tickBody = safeJson(tickResponse);
  const tickValid = tickResponse.status === 200 && tickBody !== null && tickBody.eventId === data.eventId;
  check(tickResponse, {
    'scheduler tick status is 200': () => tickResponse.status === 200,
    'scheduler tick response has event': () => tickBody !== null && tickBody.eventId === data.eventId,
  });
  if (!tickValid) {
    validResponseRate.add(false, { run_label: runLabel });
    recordFailure(tickResponse, 'scheduler_tick');
    return;
  }

  recordStatusMetrics(tickBody);

  const deadline = Date.now() + pollTimeoutSeconds * 1000;
  let finalStatus = tickBody;
  while (Date.now() < deadline) {
    const createdEnough = Number(finalStatus.notificationCount || 0) >= data.expectedNotifications;
    const deliveredEnough =
      Number(finalStatus.sentCount || 0) + Number(finalStatus.failedCount || 0) >= data.expectedNotifications;
    if (createdEnough && (!waitForDelivery || deliveredEnough)) {
      break;
    }

    sleep(pollIntervalSeconds);
    const statusResponse = getStatus(data.eventId);
    const statusBody = safeJson(statusResponse);
    const statusValid = statusResponse.status === 200 && statusBody !== null && statusBody.eventId === data.eventId;
    check(statusResponse, {
      'scheduler status is 200': () => statusResponse.status === 200,
      'scheduler status response has event': () => statusBody !== null && statusBody.eventId === data.eventId,
    });
    if (!statusValid) {
      recordFailure(statusResponse, 'poll_status');
      continue;
    }
    finalStatus = statusBody;
    recordStatusMetrics(finalStatus);
  }

  const createdEnough = Number(finalStatus.notificationCount || 0) >= data.expectedNotifications;
  const deliveredEnough =
    Number(finalStatus.sentCount || 0) + Number(finalStatus.failedCount || 0) >= data.expectedNotifications;
  const valid = createdEnough && (!waitForDelivery || deliveredEnough);
  validResponseRate.add(valid, { run_label: runLabel });
  check(finalStatus, {
    'scheduler created expected notifications': () => createdEnough,
    'scheduler delivered expected notifications': () => !waitForDelivery || deliveredEnough,
  });
}
