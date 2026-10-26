# Changelog

Notable changes to TrueCost. Based on Keep a Changelog, and this project uses semantic versioning
once it is past 0.x. Until then the minor number moves when a phase lands.

Dates are the dates the work was committed, not the dates it was planned.

## [Unreleased]

### Added, FR14 coverage rejection
- `data/seeds/coverage_regions.csv`, migration `V3__coverage_regions.sql`, and the `coverage_region`
  table. A trip whose origin, destination, or routed path leaves these rectangles now returns 422
  with code `OUT_OF_COVERAGE` and no total. Closes I-01 and I-09, opens I-21.

### Added, Hard Stop 3 validation
- `scripts/validate.sh`, 42 graded checks against a running stack across the toolchain, the
  reference data, routing, API correctness, the user supplied rate path, Kafka and the schema
  registry, observability, and the process logs. A
  check that reproduces a documented issue reports KNOWN rather than passing or failing.
- `scripts/clean-run.sh`, which tears the stack down with its volumes, waits for both jars to exit,
  deletes the prefetcher's Kafka Streams state, records that every store is empty, seeds from the CSVs, starts both jars, and
  validates. Under a minute.
- `scripts/observe-prefetch.sh`, which watches a hot route signal get emitted and consumed live.
- `make validate` and `make clean-run`.
- Issues I-13 to I-18 in `docs/RISK_LOG.md`, found by the validation runs and an independent review.

### Added, response transparency
- A top level `rateSnapshot` response field with the seed snapshot date and the latest effective
  dates of the toll rates and toll programs used. The snapshot date is seeded from
  `data/seeds/snapshot.csv`.
- The demo UI labels totals as estimates, shows the rate snapshot, and badges every synthetic or
  fallback rental, hotel, and fuel value.

### Fixed
- A synthetic hotel was reported FRESH in `freshness.hotel`. It now reads UNAVAILABLE, the same as
  the fuel fallback, I-18.
- The demo scenario buttons discarded typed Manual mode quotes. They now keep the pricing mode and
  quotes, I-20.
- The routing data was quoted as 9.8 GB. A full `make osrm` writes 4.7 GB.

### Removed
- `docs/AI_USAGE_LOG.md`, `docs/sprint/`, and `docs/evidence/`, added in 0.1.0-baseline, are no
  longer tracked. Course assignment material and captured run output stay local, and the
  validation scripts regenerate the evidence on any machine.

### Changed
- `docs/RUNBOOK.md` has a validation section for `make clean-run`, `make validate`, and the live
  prefetch observation.
- README status now has a works and partial table, each row citing the check that proves it.
- `docs/SETUP.md` lists disk space as a prerequisite and documents the clean restart workflow.

## [0.1.0-baseline] 2026-10-04

The engineering baseline. Phases 0 through 8 and the CI pipeline are built, the full stack runs in
containers, and 94 tests pass. Tagged as the reference point later sprints are compared against.

### Sprint 1, the engineering baseline. 2026-10-04

#### Added
- `docs/ENGINEERING.md`, the repository layout, naming conventions, branch strategy, and artifact
  storage rules that the history already followed but that were never written down.
- `docs/ARCHITECTURE.md`, the package by package map from the repository to the architecture.
- `docs/API.md`, the endpoint contract with real captured payloads and the stub behaviour a clean
  clone actually runs on.
- `.env.example`, every environment variable with its default and what happens when it is unset.
- `docs/SETUP.md`, a clean clone setup path with a two minute level and a full stack level.
- `docs/RISK_LOG.md`, the risk and issue register, carrying the Hard Stop 1 and Hard Stop 2
  registers forward with status.
- `docs/KNOWN_ISSUES.md`, the operational limitations a peer will actually hit.
- `docs/AI_USAGE_LOG.md`, the in repository record of assistant supported work.
- `docs/sprint/SPRINT-01-REFLECTION.md`, the Sprint 1 check-in reflection.
- `docs/evidence/`, captured verification, live stack smoke, and k6 load output.
- `make smoke`, one target that runs the build, the full test suite, and the live stack check.

#### Fixed
- `scripts/osrm-setup.sh` now produces the map file `deploy/compose.yaml` actually serves. It
  previously prepared `us-northeast-latest.osrm` while compose started `osrm-routed` against
  `/data/nyc-metro.osrm`, so a clean clone following the README got a routing container that could
  not start. The script clips to the metro bounding box by default and takes
  `TRUECOST_OSRM_EXTENT=full` for the whole extract.
- `scripts/osrm-setup.sh` no longer re-runs the multi minute `osrm-extract` stage on every
  invocation. Its skip check looked for a bare `.osrm` file, which OSRM never writes, it writes a
  set of files sharing that base name. The marker is now `.osrm.ebg` and a no-op run is 2 seconds.
- `scripts/osrm-setup.sh` records a sha256 of the prepared map, so an accuracy measurement can
  name the map it was taken against.
- The README status section claimed Kubernetes was unbuilt after Phase 8 had shipped.

### Phase 8, Kubernetes, and CI. 2026-07-20
- Kubernetes manifests under `deploy/k8s/base`, nine Deployments, nine Services, two Jobs, two
  horizontal pod autoscalers, a pod disruption budget, three ConfigMaps, and a Secret.
- Zero downtime rollout settings and an in cluster k6 load job that drives the API autoscaler.
- `scripts/k8s-up.sh` and `scripts/k8s-down.sh`, a kind cluster from zero.
- GitHub Actions CI on every push and pull request, three jobs, `mvnw verify`, a kustomize render
  of the manifest base, and a build of both container images.
- Tempo running in cluster so tracing has an export target.

### Phase 7, observability. 2026-07-19
- Micrometer metrics, OpenTelemetry tracing through to the outbound HTTP clients, and four Grafana
  dashboards covering the API, the cache, the providers, and the Kafka pipeline.
- Prometheus alert rules.

### Phase 6, Kafka Streams prefetcher. 2026-07-19
- Hot route detection on a hopping window, and the cache warming consumer.
- Apicurio Avro serde wired on both sides, and the API side event publisher.
- Packaged as a second deployable sharing one jar, selected by main class.

### Phase 5, aggregation API and caching. 2026-07-19
- `POST /api/v1/trips/plan`, the fan out orchestrator on virtual threads under a 3 s deadline.
- Two tier cache, Caffeine in process over Redis, with keyed singleflight and stale while
  revalidate.

### Phase 4, data providers. 2026-07-19
- Rental, fuel, and hotel connectors behind a shared resilient HTTP transport with timeouts,
  one retry, and a circuit breaker. Every connector is off by default with a declared fallback.

### Phase 3, toll strategy engine. 2026-07-18
- The pure function engine that prices four strategies per rental option and picks the cheapest.
- `StrategyOracle`, an independent reimplementation, and a 10,000 case property test against it.

### Phase 2, routing and toll detection. 2026-07-18
- OSRM client, polyline decoding, crossing detection on distance and bearing, and congestion
  pricing on the arrival instant rather than the departure date.

### Phase 1, domain model and reference data. 2026-07-18
- Eight reference tables under Flyway, seeded from CSVs in `data/seeds` with `SOURCES.md`
  recording the provenance of every row.

### Phase 0, bootstrap. 2026-07-17
- Spring Boot skeleton, Docker Compose stack, and the `make` command surface.
- Converted from Gradle to Maven early in the phase.

### Demo and correction work. 2026-08-03 to 2026-09-06
- Containerized the API and prefetcher behind a compose profile.
- User supplied rental rate overrides, additive to the synthetic path.
- A demo UI with a live metrics panel and a per option strategy comparison drawer.
- Pointed OSRM at the clipped extract and documented the memory workaround.
- Corrected the README to state that rental base rates are user supplied rather than fetched.
