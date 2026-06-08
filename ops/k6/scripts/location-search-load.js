import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const query = __ENV.QUERY || '서울 수영장';
const display = __ENV.DISPLAY || '10';
const latitude = __ENV.LATITUDE;
const longitude = __ENV.LONGITUDE;
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '1');
const queryParts = [
  `query=${encodeURIComponent(query)}`,
  `display=${encodeURIComponent(display)}`,
];

if (latitude) {
  queryParts.push(`latitude=${encodeURIComponent(latitude)}`);
}

if (longitude) {
  queryParts.push(`longitude=${encodeURIComponent(longitude)}`);
}

const url = `${baseUrl}/api/locations/search?${queryParts.join('&')}`;

export const options = {
  vus: Number(__ENV.VUS || '5'),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const response = http.get(url, {
    tags: {
      scenario: 'location_search_load',
      endpoint: '/api/locations/search',
    },
  });

  check(response, {
    'location search status is 200': (r) => r.status === 200,
    'location search response is json array': (r) => {
      try {
        return Array.isArray(r.json());
      } catch (error) {
        return false;
      }
    },
  });

  sleep(pauseSeconds);
}
