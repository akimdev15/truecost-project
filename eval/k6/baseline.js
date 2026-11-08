import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

// Latency and cache baseline for POST /api/v1/trips/plan, one profile per run, picked by PROFILE.
// cold sends every request for a trip no earlier request has touched, a new destination point and
// date each time, so every cache class misses and SC5 is measured on the full fetch path. warm
// replays six fixed trips the runner has already planned, which is the SC4 condition. skewed draws
// from fifty trips with an 80/20 split, the hot route shape the prefetcher exists for, at the SC6
// rate of 50 per second. Thresholds are the success criteria, so a run that misses one exits non
// zero rather than reporting a pass the numbers do not support.

const BASE = __ENV.TRUECOST_BASE_URL || 'http://localhost:8080';
const PLAN_URL = `${BASE}/api/v1/trips/plan`;
const PARAMS = { headers: { 'Content-Type': 'application/json' }, timeout: '10s' };
const PROFILE = __ENV.PROFILE || 'warm';
const SEED_DAY = Date.UTC(2026, 11, 4, 23, 0, 0);
const DAY_MS = 86400000;

function trip(destLat, destLng, dayOffset, rentalDays, tag) {
  const depart = SEED_DAY + dayOffset * DAY_MS;
  const ret = depart + rentalDays * DAY_MS - 4 * 3600000;
  return JSON.stringify({
    originLat: 40.7505, originLng: -73.9934, pickupLocationCode: 'EWR',
    destLat, destLng, destinationCode: 'BASE',
    departureAt: new Date(depart).toISOString(), returnAt: new Date(ret).toISOString(),
    hasPersonalEzpass: tag, carClass: 'MIDSIZE',
  });
}

// A 20 by 20 grid of destinations well inside the New Jersey coverage rectangle, spaced wider than
// one geohash cell so each of the 400 points is a distinct route cache key.
function njPoint(i) {
  return [39.95 + (i % 20) * 0.045, -75.05 + (Math.floor(i / 20) % 20) * 0.045];
}

const WARM = [0, 1, 2, 3, 4, 5].map((i) => trip(...njPoint(i * 7), 0, 2, i % 2 === 0));
const SKEWED = Array.from({ length: 50 }, (_, i) => trip(...njPoint(200 + i), i % 7, 2, false));

const profiles = {
  cold: {
    scenarios: { cold: { executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s', duration: '120s',
      preAllocatedVUs: 20, maxVUs: 60 } },
    thresholds: { 'http_req_duration{expected_response:true}': ['p(95)<2500'], http_req_failed: ['rate<0.01'] },
  },
  warm: {
    scenarios: { warm: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '60s',
      preAllocatedVUs: 20, maxVUs: 100 } },
    thresholds: { 'http_req_duration{expected_response:true}': ['p(95)<300', 'p(99)<600'], http_req_failed: ['rate<0.01'] },
  },
  skewed: {
    scenarios: { skewed: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '120s',
      preAllocatedVUs: 30, maxVUs: 150 } },
    thresholds: { http_req_failed: ['rate<0.01'] },
  },
};

export const options = Object.assign({ summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'] },
  profiles[PROFILE]);

export function setup() {
  if (PROFILE === 'warm') {
    WARM.forEach((body) => http.post(PLAN_URL, body, PARAMS));
  }
}

export default function () {
  let body;
  if (PROFILE === 'cold') {
    const n = exec.scenario.iterationInTest;
    body = trip(...njPoint(n % 400), 30 + n, 2, false);
  } else if (PROFILE === 'warm') {
    body = WARM[__ITER % WARM.length];
  } else {
    const hot = Math.random() < 0.8;
    body = SKEWED[hot ? Math.floor(Math.random() * 10) : 10 + Math.floor(Math.random() * 40)];
  }
  const res = http.post(PLAN_URL, body, PARAMS);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
