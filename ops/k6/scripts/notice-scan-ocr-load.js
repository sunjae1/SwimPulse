import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const runLabel = (__ENV.RUN_LABEL || 'baseline').trim();
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '0.5');
const failureLogLimitPerVu = Number(__ENV.FAILURE_LOG_LIMIT_PER_VU || '3');
const poolIds = (__ENV.POOL_IDS || __ENV.POOL_ID || '10,13,16,22,23,28,30,32,33,36')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);

const validResponseRate = new Rate('notice_scan_ocr_valid_response');
const noticeCount = new Trend('notice_scan_ocr_notice_count');
const periodCount = new Trend('notice_scan_ocr_period_count');
const scannedLinkCount = new Trend('notice_scan_ocr_scanned_link_count');
const responseDuration = new Trend('notice_scan_ocr_duration');
const extractedNoticeCount = new Counter('notice_scan_ocr_extracted_notices');
const linkOnlyNoticeCount = new Counter('notice_scan_ocr_link_only_notices');
const failedNoticeCount = new Counter('notice_scan_ocr_failed_notices');
const sharedResponseCount = new Counter('notice_scan_ocr_shared_responses');
const waitedResponseCount = new Counter('notice_scan_ocr_waited_responses');
const latestCheckFailureCount = new Counter('notice_scan_ocr_latest_check_failures');
const traceOcrMentionCount = new Counter('notice_scan_ocr_trace_ocr_mentions');
const failedResponseCount = new Counter('notice_scan_ocr_failed_responses');

let failureLogCount = 0;

if (poolIds.length === 0) {
  throw new Error('Set POOL_IDS with at least one pool id.');
}

export const options = {
  vus: Number(__ENV.VUS || '3'),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: [__ENV.HTTP_P95_THRESHOLD || 'p(95)<20000'],
    notice_scan_ocr_valid_response: ['rate>0.95'],
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
  throw new Error('Set COOKIE_HEADER or ACCESS_TOKEN for authenticated notice scan load test.');
}

function nextPoolId() {
  return poolIds[(__VU + __ITER) % poolIds.length];
}

function parseBody(response) {
  if (response.status !== 200) {
    return null;
  }
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
  try {
    const body = response.json();
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
  } catch (error) {
    return String(response.body).replace(/\s+/g, ' ').slice(0, 260);
  }
}

function countPeriods(notices) {
  return notices.reduce((sum, notice) => {
    if (Array.isArray(notice.registrationPeriods)) {
      return sum + notice.registrationPeriods.length;
    }
    return sum + (notice.registrationStartsAt && notice.registrationEndsAt ? 1 : 0);
  }, 0);
}

function recordNoticeStatuses(notices, poolId) {
  for (const notice of notices) {
    const status = notice.extractionStatus || 'UNKNOWN';
    const tags = { run_label: runLabel, pool_id: poolId, extraction_status: status };
    if (status === 'EXTRACTED') {
      extractedNoticeCount.add(1, tags);
    } else if (status === 'LINK_ONLY') {
      linkOnlyNoticeCount.add(1, tags);
    } else if (status === 'FAILED') {
      failedNoticeCount.add(1, tags);
    }
  }
}

function recordFailure(response, poolId) {
  const summary = failureSummary(response);
  failedResponseCount.add(1, {
    run_label: runLabel,
    pool_id: poolId,
    status: String(response.status || 'network_error'),
    failure_summary: summary,
  });

  if (failureLogCount >= failureLogLimitPerVu) {
    return;
  }
  failureLogCount += 1;
  console.warn(
    `[notice-scan-ocr-failure] vu=${__VU} iter=${__ITER} run=${runLabel} poolId=${poolId} status=${response.status} body=${summary}`,
  );
}

export default function () {
  const poolId = nextPoolId();
  const url = `${baseUrl}/api/pools/${encodeURIComponent(poolId)}/notices/scan`;
  const response = http.post(url, null, {
    headers: {
      Cookie: cookieHeader(),
    },
    tags: {
      scenario: 'notice_scan_ocr_load',
      run_label: runLabel,
      endpoint: '/api/pools/{poolId}/notices/scan',
      pool_id: poolId,
    },
  });

  const body = parseBody(response);
  const valid =
    response.status === 200 &&
    body !== null &&
    String(body.poolId) === String(poolId) &&
    Array.isArray(body.notices) &&
    typeof body.message === 'string' &&
    Array.isArray(body.trace);

  check(response, {
    'notice scan OCR status is 200': (r) => r.status === 200,
    'notice scan OCR response has notices': () => body !== null && Array.isArray(body.notices),
    'notice scan OCR response has trace': () => body !== null && Array.isArray(body.trace),
    'notice scan OCR response matches pool': () => body !== null && String(body.poolId) === String(poolId),
  });

  validResponseRate.add(valid, { run_label: runLabel, pool_id: poolId });
  responseDuration.add(response.timings.duration, { run_label: runLabel, pool_id: poolId });

  if (!valid) {
    recordFailure(response, poolId);
    sleep(pauseSeconds);
    return;
  }

  const notices = body.notices;
  const periods = countPeriods(notices);
  noticeCount.add(notices.length, { run_label: runLabel, pool_id: poolId });
  periodCount.add(periods, { run_label: runLabel, pool_id: poolId });
  scannedLinkCount.add(Number(body.scannedLinks || 0), { run_label: runLabel, pool_id: poolId });
  recordNoticeStatuses(notices, poolId);

  if (body.sharedResult === true) {
    sharedResponseCount.add(1, { run_label: runLabel, pool_id: poolId });
  }
  if (body.waitedForActiveScan === true) {
    waitedResponseCount.add(1, { run_label: runLabel, pool_id: poolId });
  }
  if (body.latestCheckFailed === true) {
    latestCheckFailureCount.add(1, { run_label: runLabel, pool_id: poolId });
  }
  if (body.trace.some((line) => String(line).toLowerCase().includes('ocr'))) {
    traceOcrMentionCount.add(1, { run_label: runLabel, pool_id: poolId });
  }

  sleep(pauseSeconds);
}
