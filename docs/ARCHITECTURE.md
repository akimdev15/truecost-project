# Architecture notes

How the repository on disk maps to the architecture in the Hard Stop 2 Design Review Package.
`docs/ENGINEERING.md` covers conventions. This file covers the mapping, so a reader can go from a
box in a diagram to the package that implements it.

## The two deployables

One source tree, one jar build, two entry points. They share the domain code and communicate only
through Kafka topics, never directly.

| Deployable | Entry point | Port | What it does |
| --- | --- | --- | --- |
| API | `com.truecost.TrueCostApplication` | 8080 | Values a trip synchronously and returns a ranked response |
| Prefetcher | `com.truecost.stream.PrefetcherApplication` | 8082 | Kafka Streams worker, detects hot routes and warms caches ahead of the next user |

Maven produces both from one `package`, the second through a second `repackage` execution with a
`prefetcher` classifier. `deploy/Dockerfile` has a shared build stage and two runtime stages, so
the images differ only in which jar they carry.

## Package to component map

| Package | Files | Architecture component | Requirements |
| --- | --- | --- | --- |
| `api` | 2 | The `POST /api/v1/trips/plan` endpoint | FR1, FR14 |
| `api.dto` | 10 | Request and response records, the wire contract | FR10, FR11 |
| `aggregate` | 3 | `TripPlanner`, the fan-out orchestrator under the deadline, and leg combination | FR10, FR11, NFR1, NFR12 |
| `cache` | 12 | Two tier cache, Caffeine over Redis, keyed singleflight, Redis lock, geohash keying | NFR3, NFR4, NFR12, FR16 |
| `toll` | 10 | Crossing detection, timeline pricing, congestion pricing | FR3, FR4, FR5 |
| `toll.strategy` | 9 | The toll strategy engine, the thesis centerpiece | FR6, FR7, NFR5, NFR7 |
| `route` | 5 | OSRM client and polyline decoding | FR2 |
| `provider.rental` | 5 | Rental connector and the synthetic calibrated fallback | FR8 |
| `provider.fuel` | 4 | EIA connector and the static fallback | FR9 |
| `provider.hotel` | 5 | Amadeus connector and the synthetic fallback | FR9 |
| `provider.support` | 1 | Shared resilient HTTP transport, timeout, one retry, circuit breaker | NFR6, NFR8 |
| `persist` | 8 | JDBC repositories over the reference tables | FR3, FR4, FR5 |
| `events` | 1 | Avro event publishing from the API side | NFR3 |
| `stream.topology` | 5 | Hot route detection on a hopping window | NFR3 |
| `stream.consumer` | 2 | Cache warming and the quote snapshot sink | NFR3 |
| `stream.config` | 5 | Topic definitions, serdes, and stream properties | NFR3 |
| `seed` | 3 | One shot reference data loader, runs under the `seed` profile | FR3 |
| `domain` | 1 | Shared value types | |

The test tree mirrors this exactly, so `toll.strategy` is tested by
`src/test/java/com/truecost/toll/strategy`.

## The request path

The sequence the Design Review Package documents, in code terms.

1. `TripController` validates the request and calls `TripPlanner.plan`.
2. `TripPlanner` publishes a `SearchRequested` event, fire and forget, before the fan out rather
   than after the response, so a prefetch can start on the first search rather than the second.
3. A 3 second overall deadline opens. Every join below is bounded by it.
4. Route lookup per leg goes through `TwoTierCache`. On a miss, one singleflight leader calls
   OSRM and the rest wait on the result instead of stampeding.
5. The crossing timeline is priced at each crossing's estimated arrival instant, not at the
   departure date, because congestion pricing is time of day dependent.
6. Fuel, hotel, and rental values are fetched in parallel, each through the cache, each carrying
   its own freshness tag.
7. `TollStrategyEngine` runs per rental option as a pure function and picks the cheapest of the
   candidate strategies.
8. Options are ranked by true total and the response carries the recommendation, the per option
   breakdown, and the freshness block.

A provider that misses the deadline yields a flagged fallback rather than blocking the response.

## Data and schema

| Artifact | Location |
| --- | --- |
| Flyway migrations | `src/main/resources/db/migration`, `V1__baseline.sql` and `V2__reference_data.sql` |
| Reference data CSVs | `data/seeds`, with `SOURCES.md` recording the provenance of every row |
| Avro schemas | `src/main/avro`, `SearchRequested`, `HotRouteSignal`, `QuoteSnapshot` |
| Kafka topics | `trip-searches`, `hot-routes`, `quote-snapshots` |

`V2` creates eight reference tables, `toll_crossing`, `vehicle_class`, `rental_company`,
`toll_rate`, `congestion_schedule`, `congestion_credit`, `toll_program`, and `quote_snapshot`.
`rental_calibration.csv` is read directly by the synthetic rental provider and is not a table.

## Deployment

| Concern | Location |
| --- | --- |
| Local stack | `deploy/compose.yaml`, eleven services behind an `app` profile |
| Images | `deploy/Dockerfile`, one build stage and two runtime targets |
| Kubernetes | `deploy/k8s/base`, rendered with kustomize |
| Observability | `deploy/prometheus`, `deploy/grafana`, `deploy/tempo` |
| CI | `.github/workflows/ci.yml`, three jobs, verify, manifest render, image builds |

## What is deliberately not here

The accuracy evaluation runner is Phase 9 and is not built, so `make eval` prints a pointer and
exits. FR14 out of bounds rejection is specified and not implemented. Both are tracked in
`docs/RISK_LOG.md` as I-02 and I-01 and are the first two items of Sprint 2.
