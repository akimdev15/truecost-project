# TrueCost implementation plan

Read CLAUDE.md first. Phases run in order. Each phase lists what gets built, decisions to resolve before building, acceptance criteria, and the model that does the work. A phase is done only when its acceptance criteria pass from the terminal.

## Architecture at a glance

Single Spring Boot application, single Maven module, organized by package, for the synchronous request path. The Kafka Streams prefetcher in Phase 6 is a second, separate deployable that only ever communicates with the API through Kafka topics, never a direct call, so the synchronous hot path's latency is unaffected by the split. This is a deliberate two-deployable design, not a step toward a wider microservices decomposition, TrueCost's core computation is one cohesive request/response workload without naturally independent bounded contexts, and a fuller service split would trade away the latency the thesis is measured on without teaching the operational lessons a genuinely multi-team microservices system would. Data services run in Docker Compose locally and in kind for the Kubernetes phase.

The synchronous request path never calls a third party when the cache is warm. It reads Redis and Postgres only. Kafka Streams sits off the hot path and turns traffic itself into the cache warming signal: search events are windowed and counted, popular routes get their quotes and tolls refreshed ahead of TTL expiry. Under heavy load the cache hit rate rises on exactly the routes being hammered. That is the load story for the thesis.

Key design choices, already made:

- Java 21 virtual threads for provider fan-out, no reactive stack. Simpler code, same concurrency win, easy to explain in the thesis.
- Money is a value type holding long cents. No floating point anywhere in pricing.
- Toll rates, congestion schedules, and rental company toll program terms are data in Postgres with effective dates, never constants in code. They change yearly and the thesis needs to state the snapshot date.
- Toll computation is deterministic given route, vehicle class, and departure time, so priced routes are cached long and cheaply.
- The toll strategy engine is a pure function module with zero I/O. It is the thesis centerpiece and must be exhaustively testable in isolation.

## Repository layout

```
truecost/
  CLAUDE.md
  PLAN.md
  Makefile
  docs/RUNBOOK.md
  pom.xml  mvnw  mvnw.cmd  .mvn/
  src/main/java/com/truecost/
    api/         controllers and request or response records
    domain/      Money, TripRequest, PricedCrossing, StrategyResult, TripOption
    toll/        crossing detection, rate lookup, congestion, strategy engine
    route/       OSRM client, polyline handling
    provider/    rental, fuel, hotel connectors plus synthetic providers
    aggregate/   fan-out orchestration, ranking, recommendation
    cache/       Redis config, keyed singleflight, stale while revalidate
    stream/      Kafka Streams topology and prefetch consumer
    persist/     repositories
  src/main/resources/db/migration/   Flyway
  src/test/java/...
  data/seeds/    toll_rates.csv, crossings.csv, congestion.csv, toll_programs.csv,
                 vehicle_classes.csv, rental_calibration.csv
  deploy/
    compose.yaml
    prometheus/  scrape config, alert rules
    grafana/     provisioning plus dashboard JSON
    k8s/         kustomize base and overlays
  eval/
    golden/      manually collected real quotes and receipts
    k6/          load scenarios
    results/     generated reports
  scripts/       seed.sh, osrm-setup.sh, k8s-up.sh, eval.sh, all wrapped by Makefile targets
```

## Developer workflow, terminal and nvim only

There is no IDE debugger in this project. The debugging strategy, in order of reach:

1. A failing test. Fastest loop. `./mvnw test -Dtest=TollTimelineTest` reruns a single test class. Maven has no built in equivalent to Gradle's `--continuous` watch mode, so the terminal native substitute is a file watcher piped into the same command, for example `find src -name '*.java' | entr -c ./mvnw test -Dtest=TollTimelineTest` reruns on every save with entr installed via Homebrew.
2. Readable logs. The `dev` Spring profile logs human-readable console lines, the `prod` profile logs single-line JSON. Committed code never logs at debug level, but any logger can be raised at runtime without a restart through the Actuator loggers endpoint, command in the runbook.
3. Poking the live system with curl, jq, psql, redis-cli, and kcat. The full command catalog lives in docs/RUNBOOK.md.
4. True step debugging when rarely needed: `./mvnw spring-boot:run -Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005` opens JDWP on port 5005, attach jdb or nvim-dap with the java-debug adapter.

Recommended homebrew installs beyond the obvious: jq, kcat, k6, kind, kubectl, k9s, promtool via the prometheus formula.

`make` is the single command surface. Phase 0 creates the Makefile and later phases extend it:

- `make up` and `make down`, the compose stack
- `make run`, the app with the dev profile
- `make test` and `make itest`, unit and integration tests
- `make seed`, load reference data
- `make psql` and `make redis`, open clients against the local stack
- `make kafka-topics`, list topics
- `make osrm`, one-time OSRM data preparation
- `make k6-smoke`, quick load check
- `make k8s-up` and `make k8s-down`, kind cluster lifecycle
- `make eval`, thesis evaluation reports

Integration tests use Testcontainers so `./mvnw test` is self-contained and never depends on the compose stack being up or in a clean state. The compose stack is for interactive development, tests own their containers.

Fixed local port map so muscle memory works:

| Service | Host port |
| --- | --- |
| App | 8080 |
| Postgres | 5432 |
| Redis | 6379 |
| Kafka | 9092 |
| OSRM | 5001, because macOS AirPlay squats on 5000 |
| Prometheus | 9090 |
| Grafana | 3000 |

## Runbook discipline

docs/RUNBOOK.md is the operational memory of the project. Phase 0 seeds it with compose and app basics. Every later phase appends a section with the commands to run, inspect, and debug whatever it added, and every command in it must have been run successfully once before commit. A phase is not complete if the runbook was not updated. This is an acceptance criterion for every phase even where not restated.

## Debugging each technology from the terminal

These commands are the seed content for the runbook. Sonnet copies them in during the relevant phase and keeps them current.

App:
- `curl -s localhost:8080/actuator/health | jq`
- `curl -s localhost:8080/api/v1/trips/plan -H 'Content-Type: application/json' -d @eval/golden/sample-request.json | jq`
- Raise a logger at runtime: `curl -s -X POST localhost:8080/actuator/loggers/com.truecost.toll -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'`
- `curl -s localhost:8080/actuator/metrics/http.server.requests | jq`

Postgres:
- `make psql` wraps `psql postgresql://truecost:truecost@localhost:5432/truecost`
- Migration state: `select version, description, success from flyway_schema_history order by installed_rank`
- Seed sanity: row counts for toll_crossing, toll_rate, toll_program

Redis:
- List cache keys by class: `redis-cli --scan --pattern 'rental:*'`
- Inspect one entry: `redis-cli ttl <key>` then `redis-cli get <key> | jq`, cached values are JSON
- Watch cache traffic live while hitting the API: `redis-cli monitor`, dev only

Kafka:
- List topics: `docker compose -f deploy/compose.yaml exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list`
- Tail a topic: `kcat -b localhost:9092 -t trip-searches -C -o -10`
- Consumer lag: `docker compose -f deploy/compose.yaml exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group truecost`
- After changing the topology in dev, reset state: `kafka-streams-application-reset.sh --application-id truecost` then delete local state dirs

OSRM:
- Health check, note the semicolon is mandatory OSRM URL syntax and exempt from the style rule: `curl -s 'localhost:5001/route/v1/driving/-73.99,40.75;-75.16,39.95?overview=false' | jq '.routes[0].duration'`
- If it fails, `docker compose logs osrm`, and rerun `make osrm` if the prepared data is missing

Prometheus and Grafana:
- Validate alert rules before starting: `promtool check rules deploy/prometheus/alerts.yml`
- Query from the terminal: `curl -s 'localhost:9090/api/v1/query?query=up' | jq '.data.result'`
- Grafana is viewed in a browser at localhost:3000, the one browser step in the workflow. Dashboards are provisioned from JSON files in the repo, nothing is ever configured by hand in the UI, so the browser is read-only.

k6:
- `k6 run eval/k6/warm.js`, thresholds encoded in the script so failure is an exit code, results also visible in Grafana

Kubernetes:
- `kubectl get pods -w` during rollouts
- `kubectl logs -f deployment/truecost`
- `kubectl describe pod <name>` for probe failures and scheduling problems
- `kubectl port-forward svc/truecost 8080:8080`
- `k9s` for interactive terminal cluster browsing
- Cluster autopsy when kind misbehaves: `kind export logs`

## Where the data comes from

### Rental car pricing

There is no free official API for live rental car prices. The plan uses three tiers.

1. Synthetic provider, primary for development, load testing, and demos. A deterministic pricing model seeded from `data/seeds/rental_calibration.csv`, which is built by manually collecting 30 to 50 real quotes from Kayak, Costco Travel, and company sites across companies, car classes, dates, and lead times. The model produces realistic base rates with company-specific taxes and fees. Deterministic output given a seed makes load tests and accuracy tests reproducible.
2. One real connector behind a feature flag. RapidAPI hosts unofficial Priceline and Booking.com car rental endpoints with free tiers. Fragile and rate limited, but real. Used to demonstrate the live integration path and to collect calibration data.
3. Documented production path, not built: Expedia Rapid and Priceline Partner Network require a partnership. The thesis mentions them as the production integration.

Accuracy evaluation never depends on an API. It compares computed totals against the manually collected golden dataset in `eval/golden/`.

### Toll rates and crossings

- Crossing locations: OpenStreetMap toll tags, refined by hand into `data/seeds/crossings.csv` with coordinates and direction. Roughly 20 crossings cover the NYC weekend trip space: GWB, Lincoln, Holland, Verrazzano, RFK, Queens Midtown, Hugh Carey, Whitestone, Throgs Neck, Henry Hudson, Bayonne, Goethals, Outerbridge, plus NJ Turnpike, GSP, and NY Thruway ticket segments.
- Rates: published schedules from the Port Authority, MTA Bridges and Tunnels, NJ Turnpike Authority, and NY Thruway. NY Thruway also publishes toll data on data.ny.gov. Rates include E-ZPass, Tolls by Mail, peak and off-peak windows, and carpool where relevant. Seeded with effective dates, verified against the agency pages at seeding time.
- Validation: TollGuru toll API free tier as an independent cross-check of computed route tolls. HERE Routing API, which returns toll costs, is a second optional cross-check. Both are validation tools, not runtime dependencies.

### Congestion pricing

MTA Congestion Relief Zone published schedule. Passenger vehicle with E-ZPass 9.00 peak, 2.25 overnight, higher Tolls by Mail rates, peak is 5am to 9pm weekdays and 9am to 9pm weekends, charged once per day, with crossing credits for the four tunnels entering the zone during peak. Static seed table with the schedule and credit rules, verified at seeding time.

### Rental company toll programs

Collected manually from company policy pages into `data/seeds/toll_programs.csv`. The structural shapes matter more than the numbers and the engine models the shapes:

- Usage-day fee: admin fee charged only on days a toll is incurred, capped per rental, tolls billed at the program's rate basis. Hertz PlatePass shape.
- All-rental-days fee: once any toll is incurred, the fee applies to every day of the rental, capped, plus tolls. Avis and Budget e-Toll shape.
- Unlimited flat daily plan covering all tolls. Dollar and Thrifty all-inclusive shape, Avis e-Toll Unlimited shape.

Each program row carries company, type, daily fee, cap, fee basis, toll rate basis (E-ZPass rate or maximum cash rate), whether the plan covers the congestion charge, and effective date. Numbers get verified at seeding time, the thesis records the snapshot date.

### Routing

Self-hosted OSRM with the Geofabrik us-northeast extract. Free, deterministic, fast, and works offline under load tests. No per-request cost, which matters for k6 runs.

### Fuel

- Regional gas price: EIA open data API, free key, weekly retail gasoline price for New York or PADD 1B. Cached 24 hours with a static fallback value.
- Vehicle efficiency: EPA fueleconomy.gov web service for MPG by make and model, with a default MPG per car class table in seeds as fallback.

### Hotels

Amadeus for Developers Self-Service Hotel Search, free tier, real data. Synthetic fallback provider for load tests and when the sandbox is down. Hotel is a per-trip constant across rental options so it never changes the ranking, it only completes the total.

## The heavy load design

This section is the thesis performance story. The pieces, in request order:

1. L1 in-process cache, Caffeine, 30 second TTL, absorbs identical bursts within one instance.
2. L2 Redis, the shared cache. Per-data-class TTLs: route geometry 7 days, priced tolls per route and time bucket 24 hours, rental quotes 15 minutes, fuel price 24 hours, hotel offers 6 hours.
3. Keyed singleflight. N concurrent identical cache misses produce exactly one upstream fetch. In-process keyed lock plus a short Redis lock so the guarantee holds across instances.
4. Stale while revalidate. Expired entries are served up to 2x TTL, flagged stale in the response, while a background refresh runs. Users get fast answers, upstreams get smoothed load.
5. Parallel fan-out on virtual threads with a 2 second per-provider timeout, retry once, circuit breaker per provider. Partial results are tolerated and flagged, one slow provider never blocks the response.
6. Precomputed toll matrix. Toll cost is deterministic per route, vehicle class, and departure time bucket, so priced routes are effectively free lookups after first computation. OSRM and crossing detection only run for novel origin destination pairs.
7. Kafka Streams predictive prefetch. Every search emits an event. A hopping window counts searches per route and date bucket, routes crossing a threshold are emitted to a hot-routes topic, and a prefetch consumer refreshes their rental quotes and tolls into Redis before TTL expiry. Load itself warms the cache for the load that follows.
8. Everything above exports Micrometer metrics, so the thesis performance chapter is Grafana screenshots and Prometheus histogram data from k6 runs, not hand-waving.

## Phases

### Phase 0, bootstrap. Model: Sonnet

Build: git init, Gradle wrapper, Spring Boot 3.x app on Java 21 with virtual threads enabled, the com.github.davidmc24.gradle.avro.plugin generating Java from the Avro event schemas under `src/main/avro`, Actuator with health, metrics, and loggers endpoints exposed, `deploy/compose.yaml` with Postgres 16, Redis 7, single-node Kafka in KRaft mode, Apicurio Registry for Avro schema management, Flyway wired with an empty baseline, logging with two profiles, human-readable console lines under `dev` and single-line JSON under `prod`, the Makefile with the targets listed in the developer workflow section, Testcontainers wiring for integration tests, `docs/RUNBOOK.md` seeded with the compose and app commands from the debugging section, `.gitignore`. The Avro event schemas, topic design, and schema registry choices are specified in docs/design/kafka-avro-design.md. Phase 0 only stands up the registry container and the codegen plugin, the topology itself is Phase 6.

Decisions before building: none, all made above.

Acceptance criteria:
- `make test` passes and includes a Testcontainers-backed integration test that runs with the compose stack down.
- `make up` then `make run` then `curl -s localhost:8080/actuator/health | jq` shows UP including Postgres and Redis health.
- Console logs under the dev profile are human readable, the prod profile emits single-line JSON with level, logger, and message fields, verified by running once with each profile.
- The runtime log level bump through the Actuator loggers endpoint works and is documented in the runbook.
- docs/RUNBOOK.md exists and every command in it has been run once successfully.

### Phase 1, domain model and reference data. Model: Opus for schema review, Sonnet implements

Build: Flyway migrations and seed loading for `toll_crossing` (name, agency, latitude, longitude, direction bearing, tolled directions), `toll_rate` (crossing, payment type, vehicle class, day mask, local time window, amount cents, effective date), `congestion_schedule` (period windows, amounts, crossing credit rules, once per day flag), `rental_company`, `toll_program` (fields listed in the data section), `vehicle_class` with default MPG, and `quote_snapshot` (empty for now, filled by phase 6). Seed CSVs under `data/seeds/` loaded by `scripts/seed.sh` or Flyway repeatable migrations. The `Money` value type and core domain records in `domain/`.

Decisions before building: final column shapes for time windows (recommendation: day-of-week bitmask plus start and end local time, America/New_York assumed throughout), and confirmation of each company's fee basis from their current policy pages while collecting seed numbers.

Acceptance criteria:
- Migrations apply clean on a fresh database and are idempotent on rerun.
- Seeds load at least 15 crossings and at least 6 toll programs across 5 companies.
- Repository tests fetch the correct rate for crossing, vehicle class, payment type, and timestamp, including a peak versus off-peak boundary case.

### Phase 2, routing and toll detection. Model: Opus for the detection approach, Sonnet implements

Build: `scripts/osrm-setup.sh` that downloads the Geofabrik us-northeast extract and prepares OSRM, OSRM service in compose, `route/RouteClient` returning polyline geometry, distance, and duration, `toll/CrossingDetector` that matches the polyline against crossing points using haversine distance under a threshold with a direction check from the polyline bearing, `toll/TollTimeline` that assigns an estimated arrival time to each detected crossing from departure time plus cumulative duration and prices it from the rate table, congestion zone entry detection via point-in-polygon against the CBD boundary polygon with crossing credits and the once per day cap applied.

Decisions before building: detection threshold in meters and bearing tolerance (recommendation: 50 meters and 45 degrees, tune against golden routes), plain Java geometry rather than PostGIS unless golden tests prove otherwise.

Acceptance criteria:
- Golden route tests pass with exact expected crossing lists and toll totals for fixed departure times: Manhattan to Philadelphia, Manhattan to Boston, Manhattan to DC, Brooklyn to Montauk, Manhattan to Hudson Valley.
- A route passing near but not over a crossing detects nothing, and wrong-direction travel detects nothing.
- Departure times straddling a peak boundary price differently and correctly.

### Phase 3, toll strategy engine. Model: Opus designs, Sonnet implements and writes tests

Build: pure functions in `toll/strategy/`. Input: priced crossings each carrying E-ZPass and cash or mail amounts, congestion charge details, rental day count, usage day estimate, whether the user has a personal E-ZPass, and the set of program terms for the rental company. Output: cost breakdown per strategy and the winner. Strategies: personal tag, company per-use program, company unlimited plan, and no toll arrangement when the route has no tolls. Handles fee basis (usage days versus all rental days), caps, toll rate basis per program, and whether a plan covers the congestion charge.

Also build the brute-force oracle used in tests: enumerate every strategy, sum costs directly, take the minimum.

Decisions before building: the usage day estimation rule (recommendation: days on which the itinerary crosses a tolled facility, derived from the trip timeline), and whether personal tag plus unlimited plan combinations are ever sensible (they are not, document why in the engine javadoc).

Acceptance criteria:
- Property test: 10,000 randomized scenarios across crossing sets, rental lengths 1 to 7 days, and tag ownership, engine result equals oracle result in every case.
- Curated boundary tests where the cap flips the winner, where congestion coverage flips the winner, and where a zero-toll route selects no arrangement.
- Full branch coverage on the strategy package.
- The result object carries a human-readable explanation of why the winner won, used later by the API.

### Phase 4, data providers. Model: Sonnet, Opus signs off on the SPI shape first

Build: `RentalQuoteProvider` interface with `SyntheticRentalProvider` (deterministic from calibration seed) and one real RapidAPI connector behind a config flag. `FuelCostService` using the EIA API with 24 hour cache and static fallback, MPG from the EPA service with class-default fallback. `HotelProvider` with Amadeus and a synthetic fallback. All external calls go through one shared HTTP client wrapper with a 2 second timeout, single retry, resilience4j circuit breaker per provider, and Micrometer timers.

Decisions before building: SPI method shapes and the partial-result contract (a provider failure yields an absent result with a reason, never an exception crossing the aggregation boundary).

Acceptance criteria:
- WireMock contract tests per connector covering success, timeout, 429, and malformed response.
- Synthetic provider returns identical quotes for identical seeds.
- With a provider forced down, the system still answers and marks the result partial, and the circuit breaker metric shows open state.

### Phase 5, aggregation API and caching. Model: Opus for cache key and TTL design review, Sonnet implements

Build: `POST /api/v1/trips/plan` accepting origin, destination, departure and return timestamps, personal E-ZPass flag, and optional car class. Response: every rental option with base rate, toll program fee, toll total, congestion charge, fuel estimate, hotel estimate, true total, the chosen toll strategy with its explanation, ranked by total, plus a recommendation block and per-field freshness flags. Fan-out on virtual threads. The full cache stack from the heavy load section: Caffeine L1, Redis L2 with the listed TTLs, keyed singleflight across instances, stale while revalidate up to 2x TTL.

Decisions before building: exact cache key formats and the route hash definition (recommendation: geohash-5 of endpoints plus date bucket), confirm TTL table.

Acceptance criteria:
- WireMock-counted test proves 50 concurrent identical requests cause exactly one upstream fetch per provider.
- k6 smoke locally: warm cache p95 under 300ms at 200 RPS, cold path under 3 seconds.
- Snapshot test pins the full response JSON for a golden scenario.
- Stale responses carry the stale flag and a background refresh lands within 2x TTL.

### Phase 6, Kafka Streams prefetcher. Model: Opus designs the topology, Sonnet implements

Build: the API publishes a `SearchRequested` event keyed by route key to a `trip-searches` topic. Streams topology under profile `stream`: 15 minute hopping window with 5 minute hops counting by route key and date bucket, filter on a configurable threshold, emit to `hot-routes`, a prefetch consumer refreshes rental quotes and priced tolls into Redis before their TTL expires. A second branch sinks every fetched quote into `quote_snapshot` in Postgres for the thesis accuracy analysis. Topics, replication, and retention declared in code with sensible local defaults.

Decisions before building: hotness threshold and prefetch lead time (recommendation: 5 searches per window, refresh at 80 percent of TTL). Resolved separately, the Avro serialization, schema registry, schema evolution mode, topic design, and the quote_snapshot sinking mechanism are specified in docs/design/kafka-avro-design.md.

Deployment topology, discussed and decided ahead of building, revisit the details with an Opus pass when this phase starts rather than redeciding the direction. The prefetcher builds as its own deployable from the start, not embedded behind a Spring profile as originally sketched in the architecture section. It only ever communicates with the rest of the system through Kafka topics, never a direct method or HTTP call, so splitting it costs nothing on the synchronous request path's latency, and it gives the project a second genuinely independently scaled and deployed service for real multi-deployment Kubernetes practice in Phase 8. The reasoning: Kafka Streams itself scales by replica count and partition count regardless of how many other services exist, so this split is about honest deployment boundaries, not about Kafka Streams needing microservices to function. The Opus pass at build time should cover its own Docker image and build artifact, how it shares Postgres and Redis connection configuration with the API without duplicating secrets management, and separate resource sizing from the API deployment.

Acceptance criteria:
- TopologyTestDriver unit tests cover windowing, threshold, and dedup of repeated hot emissions.
- Integration test: 20 searches for one route, Redis entry is refreshed before expiry without any new user request, cache hit rate metric rises.
- Quote snapshots appear in Postgres with route, company, price, and timestamp.
- Emissions on `hot-routes` are observable from the terminal with kcat and consumer lag for the prefetch group is visible with kafka-consumer-groups, both commands recorded in the runbook.

### Phase 7, observability. Model: Sonnet, Opus designs the tracing architecture

Build: Micrometer to Prometheus. Metrics: HTTP latency histograms per endpoint, provider call latency and error counters, cache hits and misses per layer, singleflight coalesced-request counter, stale-served counter, Kafka Streams lag, and a business counter of chosen toll strategy by type. Prometheus and Grafana in compose with provisioned dashboards checked into `deploy/grafana/`: API overview, providers, cache effectiveness, Kafka pipeline. Alert rules: p95 above threshold, circuit breaker open, cache hit rate collapse.

Distributed tracing, discussed and decided ahead of building, revisit the details with an Opus pass when this phase starts. OpenTelemetry instrumentation across every real network boundary the monolith already has, the provider fan-out calls, the OSRM call, Redis, Postgres, and Kafka including trace context propagated through message headers to the Phase 6 prefetcher, exported to a self-hosted Tempo or Jaeger and correlated with the structured logs by trace id. This is genuine distributed tracing because the spans correspond to real external dependencies, it does not require or wait on a wider microservices split. The Opus pass at build time should pick the tracing backend, the sampling strategy, and the log correlation format.

Acceptance criteria:
- `docker compose up` yields Grafana on port 3000 with all dashboards preloaded and populating during a k6 smoke run.
- Forcing a provider down fires the circuit breaker alert in Prometheus.
- The strategy-chosen counter visibly splits across strategies during the phase 9 matrix run.
- A single slow request under load can be found in the tracing backend and its waterfall shows which real dependency, a provider call, OSRM, Redis, or Postgres, accounted for the latency.

### Phase 8, Kubernetes. Model: Sonnet, Opus decides sizing and autoscaling

Build: `scripts/k8s-up.sh` creating a kind cluster and deploying kustomize manifests from `deploy/k8s/`: the API deployment and the Phase 6 prefetcher deployment as two separate Deployments with their own readiness and liveness probes wired to Actuator, resource requests and limits, and their own HPA policy, ConfigMaps and Secrets shared between them without duplication, Postgres, Redis, and Kafka in-cluster for the exercise, Prometheus and Grafana in-cluster scraping both deployments, ingress or a documented port-forward.

Decisions before building: resource numbers and HPA bounds for each of the two deployments separately, since the API is I/O bound on provider fan-out and the prefetcher's load shape is different (Opus reviews after a baseline k6 run reports actual usage for both).

Acceptance criteria:
- One script from zero to a served request through the cluster.
- `kubectl rollout restart` during a k6 run completes with zero failed requests.
- HPA scales from 1 to 3 replicas under load and back down after.
- The runbook gains a Kubernetes section covering pod logs, probe failure diagnosis, port-forwarding, and k9s, and a probe failure was deliberately induced once to verify the diagnosis steps work.

### Phase 9, thesis evaluation harness. Model: Opus designs methodology, Sonnet implements

Build: `eval/golden/` scenario files pairing real manually collected quotes and receipts with TrueCost inputs, an accuracy runner computing per-component and total error with MAPE, a strategy correctness runner sweeping routes by durations by tag ownership against the oracle, k6 scenarios for cold cache, warm cache, and 80/20 zipf-skewed hot routes with Prometheus snapshots captured, and a report generator writing markdown tables to `eval/results/` ready to paste into the thesis. `scripts/eval.sh` runs all three.

Decisions before building: golden dataset size and collection protocol (recommendation: 20 scenarios minimum, quotes and route tolls collected the same day, snapshot date recorded per row).

Acceptance criteria:
- `scripts/eval.sh` produces three reports without manual steps.
- The accuracy report breaks error down by component so toll model error is separable from rental price drift.
- The strategy matrix report shows the decision boundary shifting with trip length, the thesis figure.

### Phase 10, CI/CD pipeline. Model: Sonnet

Discussed and decided ahead of building, revisit the details with an Opus pass only if the pipeline design turns out to have real tradeoffs once the two deployables from Phase 6 and Phase 8 exist, otherwise this is standard well-defined boilerplate. Where this phase sits in the overall sequence, whether it runs earlier so every later phase gets CI validation for free, or here after Kubernetes exists so it can deploy somewhere real, is also undecided and should be picked when this phase actually starts.

Build: GitHub Actions workflow, test and lint on every pull request, build and push a container image on merge to main, deploy to the kind cluster or a real cluster if one exists by then. CI/CD is orthogonal to the monolith versus microservices question discussed for Phase 6, a single deployable is if anything a cleaner first pipeline to build correctly than coordinating deploys across multiple services would be.

Prerequisite: this repository needs a GitHub remote, it has been local only for the whole project so far. Pushing it and wiring the remote is the first step of this phase, not a precondition to plan around now.

Acceptance criteria:
- A pull request against main runs tests and lint and blocks merge on failure.
- A merge to main produces a built and pushed container image tagged with the commit sha.
- The pipeline can deploy that image to a real or kind cluster and the deployed app answers a health check.

## Risks and mitigations

- Rental price APIs are the weakest link. Mitigated by the synthetic-primary design and manual golden data, the thesis never blocks on an API partnership.
- Toll and program numbers drift. Everything is dated seed data, the thesis states its snapshot date, and reseeding is one script.
- OSRM northeast extract needs several GB of disk and a few minutes of preprocessing. One-time cost handled by `scripts/osrm-setup.sh`.
- Congestion pricing rules have edge cases (credits, once per day). Encoded as data plus golden tests, and TollGuru cross-checks catch systematic errors.

## Open items for the user

- Sign up for free keys when phase 4 starts: EIA, Amadeus Self-Service, RapidAPI, TollGuru.
- Confirm embedded Kafka Streams with a profile versus a separate deployable at phase 6.
- Collect the rental calibration quotes and golden dataset, manual work only you can do, roughly two hours total.
