import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://backend:8080').replace(/\/$/, '');
const latitude = __ENV.LATITUDE || '37.5665';
const longitude = __ENV.LONGITUDE || '126.9780';
const limit = __ENV.LIMIT || '20';
const pauseSeconds = Number(__ENV.SLEEP_SECONDS || '1');
const url = `${baseUrl}/api/pools/nearby?latitude=${encodeURIComponent(latitude)}&longitude=${encodeURIComponent(longitude)}&limit=${encodeURIComponent(limit)}`;

export const options = {
  vus: Number(__ENV.VUS || '5'),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const response = http.get(url, {
    tags: {
      scenario: 'nearby_load',
      endpoint: '/api/pools/nearby',
    },
  });

  check(response, {
    'nearby status is 200': (r) => r.status === 200,
    'nearby response is json array': (r) => {
      try {
        return Array.isArray(r.json());
      } catch (error) {
        return false;
      }
    },
  });

  sleep(pauseSeconds);
}
