import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

// Phase 5 load smoke for POST /api/v1/trips/plan, the two PLAN acceptance thresholds in one run.
// The cold_path scenario jitters the endpoints every iteration so each request lands on a novel
// geohash-6 cell and misses the cache, measuring the full fan-out under the 3 second cold budget.
// The warm_path scenario drives 200 requests per second at one fixed trip whose entries setup has
// already warmed into Caffeine, measuring the p95 under 300 milliseconds warm hit target. Point it
// at a running stack with OSRM up, TRUECOST_BASE_URL overrides the default localhost:8080.

const BASE = __ENV.TRUECOST_BASE_URL || 'http://localhost:8080';
const PLAN_URL = `${BASE}/api/v1/trips/plan`;
const PARAMS = { headers: { 'Content-Type': 'application/json' } };

const warmDuration = new Trend('warm_duration', true);
const coldDuration = new Trend('cold_duration', true);

const WARM_TRIP = JSON.stringify({
  originLat: 40.7505,
  originLng: -73.9934,
  pickupLocationCode: 'EWR',
  destLat: 39.95,
  destLng: -75.16,
  destinationCode: 'PHL',
  departureAt: '2026-08-14T22:00:00Z',
  returnAt: '2026-08-16T18:00:00Z',
  hasPersonalEzpass: false,
  carClass: 'MIDSIZE',
});

export const options = {
  scenarios: {
    cold_path: {
      executor: 'per-vu-iterations',
      exec: 'coldPath',
      vus: 1,
      iterations: 10,
      startTime: '0s',
      maxDuration: '60s',
    },
    warm_path: {
      executor: 'constant-arrival-rate',
      exec: 'warmPath',
      rate: 200,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      startTime: '65s',
    },
  },
  thresholds: {
    cold_duration: ['p(95)<3000'],
    warm_duration: ['p(95)<300'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  http.post(PLAN_URL, WARM_TRIP, PARAMS);
}

export function coldPath() {
  const jitter = () => (Math.random() - 0.5) * 0.2;
  const body = JSON.stringify({
    originLat: 40.75 + jitter(),
    originLng: -73.99 + jitter(),
    pickupLocationCode: 'EWR',
    destLat: 39.95 + jitter(),
    destLng: -75.16 + jitter(),
    destinationCode: 'PHL',
    departureAt: '2026-08-14T22:00:00Z',
    returnAt: '2026-08-16T18:00:00Z',
    hasPersonalEzpass: false,
    carClass: 'MIDSIZE',
  });
  const res = http.post(PLAN_URL, body, PARAMS);
  coldDuration.add(res.timings.duration);
  check(res, { 'cold path status 200': (r) => r.status === 200 });
}

export function warmPath() {
  const res = http.post(PLAN_URL, WARM_TRIP, PARAMS);
  warmDuration.add(res.timings.duration);
  check(res, { 'warm path status 200': (r) => r.status === 200 });
}
