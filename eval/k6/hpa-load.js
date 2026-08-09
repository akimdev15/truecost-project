import http from 'k6/http';
import { check } from 'k6';

// Sustained load to drive the API's CPU up so the HorizontalPodAutoscaler scales out, PLAN Phase 8
// acceptance criterion three. Unlike trip-plan-smoke.js this carries no latency thresholds, its only
// job is to hold enough concurrent plan requests to push CPU past the HPA target for long enough to
// trigger a scale up, then stop so the scale down can be observed. It targets the in cluster API
// Service by default, overridable with TRUECOST_BASE_URL, and runs as a Kubernetes Job.
//
// OSRM is not run in cluster, so route and tolls warming degrades to partial and the response is a
// 200 with a partial flag, which is exactly the load the synchronous fan-out still does, the rental,
// fuel, and hotel work plus the strategy engine per option, so the request still burns real CPU.

const BASE = __ENV.TRUECOST_BASE_URL || 'http://api:8080';
const PLAN_URL = `${BASE}/api/v1/trips/plan`;
const PARAMS = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 40 },
        { duration: '3m', target: 40 },
        { duration: '1m', target: 0 },
      ],
    },
  },
};

// Jitter the endpoints so requests spread across cache keys rather than all hitting one warm entry,
// which keeps the fan-out doing real work per request rather than serving one cached answer.
function jitter() {
  return (Math.random() - 0.5) * 0.3;
}

export default function () {
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
  check(res, { 'status 200': (r) => r.status === 200 });
}
