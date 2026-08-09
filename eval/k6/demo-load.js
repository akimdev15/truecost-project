import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// Demo load for POST /api/v1/trips/plan, tuned for a live demo rather than a pass or fail gate.
// Four fixed trip bodies are reused over and over by the repeat_routes scenario so the two tier
// cache hit rate climbs visibly on the UI deck as the run progresses. A second scenario, burst,
// starts at once on a cold dedicated body with sixty simultaneous virtual users, so the first wave
// of identical concurrent requests all miss the same cold cache key together and collapse onto the
// keyed singleflight, driving its coalesced counter up on screen. No threshold aborts the run, this
// script always exits zero so it is safe to leave running on stage. Point it at a running stack
// with OSRM up, TRUECOST_BASE_URL overrides the default localhost:8080.

const BASE = __ENV.TRUECOST_BASE_URL || 'http://localhost:8080';
const PLAN_URL = `${BASE}/api/v1/trips/plan`;
const PARAMS = { headers: { 'Content-Type': 'application/json' } };

const planDuration = new Trend('demo_plan_duration', true);

const ROUTES = [
  JSON.stringify({
    originLat: 40.7505,
    originLng: -73.9934,
    pickupLocationCode: 'EWR',
    destLat: 39.95,
    destLng: -75.16,
    destinationCode: 'PHL',
    departureAt: '2026-08-07T22:00:00Z',
    returnAt: '2026-08-09T18:00:00Z',
    hasPersonalEzpass: false,
    carClass: 'MIDSIZE',
    rentalRateOverrides: null,
  }),
  JSON.stringify({
    originLat: 40.6782,
    originLng: -73.9442,
    pickupLocationCode: 'JFK',
    destLat: 41.0362,
    destLng: -71.9509,
    destinationCode: 'MTK',
    departureAt: '2026-08-14T20:00:00Z',
    returnAt: '2026-08-16T17:00:00Z',
    hasPersonalEzpass: true,
    carClass: 'MIDSIZE',
    rentalRateOverrides: null,
  }),
  JSON.stringify({
    originLat: 40.7831,
    originLng: -73.9712,
    pickupLocationCode: 'NYC',
    destLat: 41.7476,
    destLng: -74.0893,
    destinationCode: 'SWF',
    departureAt: '2026-08-21T21:00:00Z',
    returnAt: '2026-08-23T16:00:00Z',
    hasPersonalEzpass: false,
    carClass: 'MIDSIZE',
    rentalRateOverrides: null,
  }),
  JSON.stringify({
    originLat: 40.758,
    originLng: -73.9855,
    pickupLocationCode: 'NYC',
    destLat: 41.3083,
    destLng: -72.9279,
    destinationCode: 'HVN',
    departureAt: '2026-08-28T22:00:00Z',
    returnAt: '2026-08-30T18:00:00Z',
    hasPersonalEzpass: false,
    carClass: 'MIDSIZE',
    rentalRateOverrides: null,
  }),
];

// A dedicated route no repeat_routes virtual user touches, so it is cold when the burst starts and
// the first simultaneous wave of identical requests collapses onto the singleflight loader.
const BURST_TRIP = JSON.stringify({
  originLat: 40.8448,
  originLng: -73.8648,
  pickupLocationCode: 'BRX',
  destLat: 41.7003,
  destLng: -73.9209,
  destinationCode: 'POU',
  departureAt: '2026-09-04T22:00:00Z',
  returnAt: '2026-09-06T18:00:00Z',
  hasPersonalEzpass: false,
  carClass: 'MIDSIZE',
  rentalRateOverrides: null,
});

export const options = {
  scenarios: {
    repeat_routes: {
      executor: 'ramping-vus',
      exec: 'repeatRoute',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 8 },
        { duration: '50s', target: 8 },
        { duration: '10s', target: 0 },
      ],
    },
    burst_coalesce: {
      executor: 'constant-vus',
      exec: 'burstCoalesce',
      vus: 60,
      duration: '12s',
      startTime: '0s',
    },
  },
};

// Each VU sticks to one of the four fixed bodies for its whole lifetime, that repetition is what
// lets the cache hit rate climb, a body jittered per iteration would keep everything a miss.
export function repeatRoute() {
  const body = ROUTES[__VU % ROUTES.length];
  const res = http.post(PLAN_URL, body, PARAMS);
  planDuration.add(res.timings.duration);
  check(res, { 'repeat route status is 2xx': (r) => r.status >= 200 && r.status < 300 });
  sleep(0.1 + Math.random() * 0.2);
}

// Every VU in this scenario hammers the same single body with almost no sleep, so many identical
// requests overlap in time and pile onto the keyed singleflight, driving the coalesced counter up.
export function burstCoalesce() {
  const res = http.post(PLAN_URL, BURST_TRIP, PARAMS);
  planDuration.add(res.timings.duration);
  check(res, { 'burst status is 2xx': (r) => r.status >= 200 && r.status < 300 });
  sleep(0.02);
}
