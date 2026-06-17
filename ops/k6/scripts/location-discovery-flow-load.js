import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const locationQuery = __ENV.LOCATION_QUERY || '부천시청';
const locationDisplay = __ENV.LOCATION_DISPLAY || '5';
const nearbyLimit = __ENV.NEARBY_LIMIT || '10';
const candidateQuery = __ENV.CANDIDATE_QUERY || '체육센터';
const candidateDisplay = __ENV.CANDIDATE_DISPLAY || '10';
const candidateRadius = __ENV.RADIUS || '5000';
const createCandidate = (__ENV.CREATE_CANDIDATE || 'false').toLowerCase() === 'true';
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '2');
const stepPauseSeconds = Number(__ENV.STEP_SLEEP_SECONDS || '0');
const failureLogLimitPerVu = Number(__ENV.FAILURE_LOG_LIMIT_PER_VU || '3');

const flowCompleted = new Rate('location_discovery_flow_completed');
const stepValidResponse = new Rate('location_discovery_step_valid_response');
const stepDuration = new Trend('location_discovery_step_duration');
const resultCount = new Trend('location_discovery_result_count');
const failedSteps = new Counter('location_discovery_failed_steps');
const createdPools = new Counter('location_discovery_created_pools');

let failureLogCount = 0;

export const options = {
  vus: Number(__ENV.VUS || '2'),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: [__ENV.HTTP_P95_THRESHOLD || 'p(95)<5000'],
    location_discovery_flow_completed: ['rate>0.99'],
    location_discovery_step_valid_response: ['rate>0.99'],
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

function requestParams(step, endpoint, extraHeaders = {}) {
  return {
    headers: extraHeaders,
    tags: {
      scenario: 'location_discovery_flow',
      step,
      endpoint,
    },
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
  }

  return String(response.body).replace(/\s+/g, ' ').slice(0, 240);
}

function recordStep(step, response, valid) {
  stepDuration.add(response.timings.duration, { step });
  stepValidResponse.add(valid, { step });
  if (valid) {
    return;
  }

  const summary = failureSummary(response);
  failedSteps.add(1, {
    step,
    status: String(response.status || 'network_error'),
    failure_summary: summary,
  });

  if (failureLogCount >= failureLogLimitPerVu) {
    return;
  }

  failureLogCount += 1;
  console.warn(
    `[location-flow-failure] vu=${__VU} iter=${__ITER} step=${step} status=${response.status} body=${summary}`,
  );
}

function pauseBetweenSteps() {
  if (stepPauseSeconds > 0) {
    sleep(stepPauseSeconds);
  }
}

function chooseLocationCandidate(candidates) {
  return candidates.find((candidate) => candidate.roadAddress || candidate.address) || null;
}

function choosePoolCandidate(candidates) {
  return candidates.find((candidate) => candidate.alreadyExists === false) || null;
}

function addressOf(candidate) {
  return candidate.roadAddress || candidate.address;
}

function searchLocations() {
  const url =
    `${baseUrl}/api/locations/search?query=${encodeURIComponent(locationQuery)}` +
    `&display=${encodeURIComponent(locationDisplay)}`;
  const response = http.get(url, requestParams('location_search', '/api/locations/search'));
  const body = safeJson(response);
  const valid = response.status === 200 && Array.isArray(body) && chooseLocationCandidate(body) !== null;
  check(response, {
    'flow location search status is 200': () => response.status === 200,
    'flow location search has selectable candidate': () => Array.isArray(body) && chooseLocationCandidate(body) !== null,
  });
  if (Array.isArray(body)) {
    resultCount.add(body.length, { step: 'location_search' });
  }
  recordStep('location_search', response, valid);
  return valid ? chooseLocationCandidate(body) : null;
}

function geocodeSelectedLocation(candidate) {
  const address = addressOf(candidate);
  const response = http.get(
    `${baseUrl}/api/locations/geocode?address=${encodeURIComponent(address)}`,
    requestParams('location_geocode', '/api/locations/geocode'),
  );
  const body = safeJson(response);
  const valid =
    response.status === 200 &&
    body !== null &&
    typeof body.latitude === 'number' &&
    typeof body.longitude === 'number';
  check(response, {
    'flow geocode status is 200': () => response.status === 200,
    'flow geocode has coordinates': () =>
      body !== null && typeof body.latitude === 'number' && typeof body.longitude === 'number',
  });
  if (valid) {
    resultCount.add(1, { step: 'location_geocode' });
  }
  recordStep('location_geocode', response, valid);
  return valid ? body : null;
}

function loadNearbyPools(location) {
  const response = http.get(
    `${baseUrl}/api/pools/nearby?latitude=${encodeURIComponent(location.latitude)}` +
      `&longitude=${encodeURIComponent(location.longitude)}` +
      `&limit=${encodeURIComponent(nearbyLimit)}`,
    requestParams('nearby_pools', '/api/pools/nearby'),
  );
  const body = safeJson(response);
  const valid = response.status === 200 && Array.isArray(body);
  check(response, {
    'flow nearby status is 200': () => response.status === 200,
    'flow nearby response is array': () => Array.isArray(body),
  });
  if (Array.isArray(body)) {
    resultCount.add(body.length, { step: 'nearby_pools' });
  }
  recordStep('nearby_pools', response, valid);
  return valid;
}

function loadPoolLocationCandidates(location) {
  const response = http.get(
    `${baseUrl}/api/pools/location-candidates?latitude=${encodeURIComponent(location.latitude)}` +
      `&longitude=${encodeURIComponent(location.longitude)}` +
      `&radius=${encodeURIComponent(candidateRadius)}` +
      `&query=${encodeURIComponent(candidateQuery)}` +
      `&display=${encodeURIComponent(candidateDisplay)}`,
    requestParams('pool_location_candidates', '/api/pools/location-candidates'),
  );
  const body = safeJson(response);
  const valid = response.status === 200 && Array.isArray(body);
  check(response, {
    'flow pool candidates status is 200': () => response.status === 200,
    'flow pool candidates response is array': () => Array.isArray(body),
  });
  if (Array.isArray(body)) {
    resultCount.add(body.length, { step: 'pool_location_candidates' });
  }
  recordStep('pool_location_candidates', response, valid);
  return valid ? body : null;
}

function createPoolFromCandidate(candidates) {
  const candidate = choosePoolCandidate(candidates);
  if (candidate === null) {
    return true;
  }

  const cookie = cookieHeader();
  if (!cookie) {
    console.warn('CREATE_CANDIDATE=true requires COOKIE_HEADER or ACCESS_TOKEN.');
    return false;
  }

  const response = http.post(
    `${baseUrl}/api/pools/from-location-candidate`,
    JSON.stringify({
      title: candidate.title,
      address: candidate.address,
      roadAddress: candidate.roadAddress,
      link: candidate.link,
      latitude: candidate.latitude,
      longitude: candidate.longitude,
    }),
    requestParams('create_pool_candidate', '/api/pools/from-location-candidate', {
      Cookie: cookie,
      'Content-Type': 'application/json',
    }),
  );
  const body = safeJson(response);
  const valid = response.status === 201 && body !== null && typeof body.id === 'number';
  check(response, {
    'flow create candidate status is 201': () => response.status === 201,
    'flow create candidate has id': () => body !== null && typeof body.id === 'number',
  });
  if (valid) {
    createdPools.add(1);
    resultCount.add(1, { step: 'create_pool_candidate' });
  }
  recordStep('create_pool_candidate', response, valid);
  return valid;
}

export default function () {
  let completed = false;

  const selectedLocation = searchLocations();
  if (selectedLocation !== null) {
    pauseBetweenSteps();
    const geocoded = geocodeSelectedLocation(selectedLocation);
    if (geocoded !== null) {
      pauseBetweenSteps();
      const nearbyOk = loadNearbyPools(geocoded);
      pauseBetweenSteps();
      const poolCandidates = loadPoolLocationCandidates(geocoded);
      if (nearbyOk && poolCandidates !== null) {
        pauseBetweenSteps();
        completed = createCandidate ? createPoolFromCandidate(poolCandidates) : true;
      }
    }
  }

  flowCompleted.add(completed);
  sleep(pauseSeconds);
}
