import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const duration = __ENV.DURATION || '30s';
const batchStartTime = __ENV.BATCH_START_TIME || '35s';
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '0.1');
const vus = Number(__ENV.VUS || '10');

const legacyDuration = new Trend('legacy_match_duration', true);
const batchDuration = new Trend('batch_match_duration', true);
const legacyServerDuration = new Trend('legacy_server_duration', true);
const batchServerDuration = new Trend('batch_server_duration', true);
const legacyFailureRate = new Rate('legacy_match_failed');
const batchFailureRate = new Rate('batch_match_failed');

export const options = {
  scenarios: {
    legacy_existing_candidates: {
      executor: 'constant-vus',
      exec: 'legacy',
      vus,
      duration,
      gracefulStop: '2s',
    },
    batch_existing_candidates: {
      executor: 'constant-vus',
      exec: 'batch',
      vus,
      duration,
      startTime: batchStartTime,
      gracefulStop: '2s',
    },
  },
  thresholds: {
    legacy_match_failed: ['rate<0.01'],
    batch_match_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  request('legacy', legacyDuration, legacyServerDuration, legacyFailureRate, false);
  request('batch', batchDuration, batchServerDuration, batchFailureRate, false);
}

export function legacy() {
  request('legacy', legacyDuration, legacyServerDuration, legacyFailureRate, true);
}

export function batch() {
  request('batch', batchDuration, batchServerDuration, batchFailureRate, true);
}

function request(strategy, durationMetric, serverDurationMetric, failureMetric, shouldSleep) {
  const response = http.get(
    `${baseUrl}/internal/loadtest/location-match?strategy=${strategy}`,
    {
      tags: {
        scenario: `${strategy}_existing_candidates`,
        endpoint: '/internal/loadtest/location-match',
        strategy,
      },
    },
  );
  durationMetric.add(response.timings.duration);

  let body = null;
  try {
    body = response.json();
  } catch (error) {
    body = null;
  }
  if (body !== null && Number.isFinite(body.elapsedMicros)) {
    serverDurationMetric.add(body.elapsedMicros / 1000);
  }
  const passed = check(response, {
    [`${strategy} status is 200`]: (r) => r.status === 200,
    [`${strategy} matches all 10 candidates`]: () =>
      body !== null && body.candidateCount === 10 && body.matchedCount === 10,
  });
  failureMetric.add(!passed);

  if (shouldSleep) {
    sleep(pauseSeconds);
  }
}
