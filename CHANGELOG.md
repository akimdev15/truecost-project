# Changelog

Notable changes to TrueCost. Based on Keep a Changelog, and this project uses semantic versioning
once it is past 0.x. Until then the minor number moves when a phase lands.

Dates are the dates the work was committed, not the dates it was planned.

## [Unreleased]

### Added
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

### Fixed
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

## [0.1.0-baseline] 2026-10-04

The engineering baseline. Phases 0 through 8 and the CI pipeline are built, the full stack runs in
containers, and 94 tests pass. Tagged as the reference point later sprints are compared against.

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
