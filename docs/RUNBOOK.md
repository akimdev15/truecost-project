# TrueCost runbook

Operational commands for running, inspecting, and debugging the stack from the terminal. Commands have been run successfully against this build wherever the environment allows. Commands that need a piece the development sandbox cannot run, a fully prepared OSRM extract, a running compose or kind stack, or Grafana, are called out in their own sections as run once locally rather than in the sandbox, with the reason. Phase 0 seeds the App, Postgres, Redis, and Kafka sections. Later phases append OSRM, Prometheus and Grafana, k6, Kubernetes, and the rest as those pieces land.

Typical loop: `make up` starts Postgres, Redis, Kafka, and Apicurio Registry in Docker Compose, `make run` starts the app on the host against that stack with the dev profile.

## Fully containerized stack

Two ways to run the system. `make up` brings up the infra only and you run the app on the host with `make run`, the fast inner loop for development. `make up-all` runs everything in containers, the infra plus a one shot seed, the API, and the prefetcher, wired to the infra by compose service name. Use this to exercise the system exactly as it deploys.

Bring the whole thing up in containers, this builds both images on first run:

```
make up-all
```

The seed container runs first, loads the reference CSVs, and exits. The API and prefetcher wait for it, so they never serve against an unseeded database. Watch the app come up:

```
make logs
```

Health of the containerized API, mapped to host port 8080:

```
curl -s localhost:8080/actuator/health | jq
```

The prefetcher Actuator is on host port 8082. A trip plan request also needs OSRM data prepared once with `make osrm`, see the OSRM section, everything else works without it.

Tear the containerized stack down including its volumes:

```
make down-all
```

Note on Kafka listeners. The broker advertises two addresses, `localhost:9092` for clients on the host, make run, kcat, and `kafka:9094` for containers on the compose network, the API and prefetcher. Host tooling keeps using localhost:9092 unchanged.

## App

Health, including Postgres and Redis component status:

```
curl -s localhost:8080/actuator/health | jq
```

HTTP request latency metrics:

```
curl -s localhost:8080/actuator/metrics/http.server.requests | jq
```

Raise a logger to DEBUG at runtime, no restart required:

```
curl -s -X POST localhost:8080/actuator/loggers/com.truecost -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

Confirm the change:

```
curl -s localhost:8080/actuator/loggers/com.truecost | jq
```

Run with the dev profile, human-readable console lines, this is what `make run` does:

```
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

Run with the prod profile, single-line JSON console lines with level, logger, and message fields:

```
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run
```

## Postgres

`make psql` wraps:

```
psql postgresql://truecost:truecost@localhost:5432/truecost
```

Migration state:

```sql
select version, description, success from flyway_schema_history order by installed_rank
```

Phase 0 applies only the V1 baseline migration. Reference data table sanity checks arrive in Phase 1 once the seed tables exist.

## Redis

`make redis` wraps:

```
redis-cli -h localhost -p 6379
```

Ping check:

```
redis-cli -h localhost -p 6379 ping
```

List cache keys by pattern, empty in Phase 0 since no caching code exists yet:

```
redis-cli -h localhost -p 6379 --scan --pattern '*'
```

## Kafka

Single-node broker in KRaft mode, no Zookeeper. `make kafka-topics` wraps:

```
docker compose -f deploy/compose.yaml exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

Broker reachability and API version check:

```
docker compose -f deploy/compose.yaml exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092
```

Phase 0 declares no topics, the codegen plugin only generates Java classes from the Avro schemas under `src/main/avro`. Tailing a topic with kcat and checking consumer lag with kafka-consumer-groups.sh are documented in Phase 6 once the topology creates the trip-searches, hot-routes, and quote-snapshots topics.

## Reference data, Phase 1

`make seed` loads `data/seeds/*.csv` into Postgres through a Spring Boot run under the `seed` profile, which applies pending Flyway migrations first, truncates the seven reference tables, reloads them from the CSVs, logs a row count per table, then exits. Safe to run repeatedly, each run replaces the prior seed with the same data rather than duplicating rows. Requires `make up` first so Postgres is reachable.

```
make seed
```

Equivalently, direct from the project root:

```
./scripts/seed.sh
```

Row counts across the seeded reference tables:

```sql
select 'toll_crossing' as table_name, count(*) from toll_crossing
union all select 'toll_rate', count(*) from toll_rate
union all select 'congestion_schedule', count(*) from congestion_schedule
union all select 'congestion_credit', count(*) from congestion_credit
union all select 'rental_company', count(*) from rental_company
union all select 'toll_program', count(*) from toll_program
union all select 'vehicle_class', count(*) from vehicle_class
union all select 'quote_snapshot', count(*) from quote_snapshot;
```

Toll programs by company, confirming the spread across companies required by PLAN.md Phase 1:

```sql
select rc.code, count(*) as programs
from toll_program tp
join rental_company rc on rc.id = tp.company_id
group by rc.code
order by rc.code;
```

Sample rate lookup, the George Washington Bridge E-ZPass passenger rate at the weekday morning peak boundary, 6am on a Wednesday, mirroring the lookup `TollRateRepository` runs at request time:

```sql
select tr.amount_cents, tr.window_start, tr.window_end, tr.effective_date
from toll_rate tr
join toll_crossing tc on tc.id = tr.crossing_id
join vehicle_class vc on vc.toll_class = tr.toll_class
where tc.code = 'GWB'
  and vc.code = 'MIDSIZE'
  and tr.payment_type::text = 'EZPASS'
  and tr.effective_date <= '2026-01-07'
  and (tr.day_of_week_mask & (1 << (3 - 1))) <> 0
  and time '06:00:00' >= tr.window_start
  and time '06:00:00' < tr.window_end
order by tr.effective_date desc
limit 1;
```

Every dollar figure in the seed CSVs and its source URL and retrieval date are recorded in `data/seeds/SOURCES.md`.

## OSRM, Phase 2

Self hosted routing, `route/RouteClient` calls it over HTTP. One time data preparation, run once and whenever the map extract needs refreshing:

```
make osrm
```

Equivalently, direct from the project root:

```
./scripts/osrm-setup.sh
```

This downloads the Geofabrik us-northeast OpenStreetMap extract into `data/osrm`, gitignored, and runs the modern multi level Dijkstra prepare pipeline, `osrm-extract` with the car profile, then `osrm-partition`, then `osrm-customize`. It is a multi gigabyte download and a multi minute CPU and memory heavy step, matching the risk PLAN.md calls out, and `scripts/osrm-setup.sh` downloads and prepares the full us-northeast extract exactly as PLAN.md specifies, producing `data/osrm/us-northeast-latest.osrm`, which is what `deploy/compose.yaml`'s `osrm` service command references.

A note on this development sandbox specifically, not a change to the script. The full us-northeast extract's turn analysis and edge expansion step needs more than the roughly 7.6 gigabytes of memory Docker Desktop had available here, and the `osrm-extract` container was killed with no error output twice at that step. `osrm-extract`, `osrm-partition`, and `osrm-customize` did all complete successfully end to end, proving the pipeline itself is correct, once the input was clipped down with `osmium extract` to bounding box `-75.35,39.85,-71.5,41.8`, covering New York City, all of New Jersey, Connecticut, the Hudson Valley up through New Paltz, and Long Island out to Montauk. That smaller extract, saved locally as `data/osrm/nyc-metro.osm.pbf`, needed well under half the memory and the full three step pipeline finished in under two minutes total. Boston and Washington DC fall outside this tighter bounding box, verified by checking the snapped waypoint in each response, a query toward either one resolves to a route that stops at the box edge rather than reaching the named city, so the live instance in this sandbox answers real, full routes for the Manhattan to Philadelphia, Brooklyn to Montauk, and Manhattan to Hudson Valley golden routes, and a partial route toward the edge of the box for the Boston and DC directions. The official `ghcr.io/osmcode/osmium-tool` image was not pullable from this sandbox, so `stefda/osmium-tool`, a third party build of the same `osmium` command line tool, was used instead. To reproduce this locally, run `make osrm` for the real full extract on a host with more Docker memory, or clip first for a lighter local instance:

```
docker run --rm -v "$(pwd)/data/osrm:/data" stefda/osmium-tool osmium extract -b -75.35,39.85,-71.5,41.8 /data/us-northeast-latest.osm.pbf -o /data/nyc-metro.osm.pbf
docker run --rm -v "$(pwd)/data/osrm:/data" ghcr.io/project-osrm/osrm-backend osrm-extract -p /opt/car.lua /data/nyc-metro.osm.pbf
docker run --rm -v "$(pwd)/data/osrm:/data" ghcr.io/project-osrm/osrm-backend osrm-partition /data/nyc-metro.osrm
docker run --rm -v "$(pwd)/data/osrm:/data" ghcr.io/project-osrm/osrm-backend osrm-customize /data/nyc-metro.osrm
```

`deploy/compose.yaml`'s `osrm` service command now points at `/data/nyc-metro.osrm` by default, matching this. A later run of the clipped extract still hit the same kill at the edge expansion step whenever the rest of the app stack, api, prefetcher, kafka, and the observability containers, was up at the same time, on this same 7.7 gigabyte machine peak usage measured at 6.3 gigabytes for `osrm-extract` alone. `docker compose -f deploy/compose.yaml --profile app stop` before running the three prepare commands, then `up -d` again afterward, is what got it through cleanly.

Bring up the routing service once its data is prepared:

```
docker compose -f deploy/compose.yaml up -d osrm
```

Health check, note the semicolon between the two coordinate pairs is mandatory OSRM URL syntax and exempt from the project's no semicolon style rule. Verified against the clipped local instance, Manhattan to Philadelphia, real duration about two hours five minutes:

```
curl -s 'localhost:5001/route/v1/driving/-73.99,40.75;-75.16,39.95?overview=false' | jq '.routes[0].duration'
```

If it fails:

```
docker compose -f deploy/compose.yaml logs osrm
```

and rerun `make osrm` if the prepared data is missing or stale, then restart the service:

```
docker compose -f deploy/compose.yaml up -d osrm
```

The container image ships no curl or wget, so its compose healthcheck opens a raw TCP connection to port 5000 with a bash `/dev/tcp` redirect rather than an HTTP request, confirming `osrm-routed` is bound and listening. The curl command above is the real routing query check.

## Toll strategy engine, Phase 3

The strategy engine in `toll/strategy/` is a pure function, no database, no Redis, no OSRM, so its tests need none of the compose stack up. `make test` or a plain `./mvnw test` runs them along with everything else, and the commands below isolate just this package for the fast inner loop described in PLAN.md's developer workflow section.

Run every test in the strategy package, the curated boundary and branch coverage tests plus the ten thousand case property test:

```
./mvnw test -Dtest='TollStrategy*' -Dsurefire.failIfNoSpecifiedTests=false
```

Run only the curated tests, the three named boundary scenarios from the design doc section 8, the decision boundary figure from section 9, and the branch coverage tests for every cost formula fork and tie-break key:

```
./mvnw test -Dtest=TollStrategyEngineTest -Dsurefire.failIfNoSpecifiedTests=false
```

Run only the property test, ten thousand randomized scenarios checked against the independent brute-force oracle in `StrategyOracle`:

```
./mvnw test -Dtest=TollStrategyPropertyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Every case is deterministic from a fixed master seed plus its case index, `StrategyScenarioGenerator.generate(masterSeed, caseIndex)`, so if this test ever fails, the assertion message reports the failing case index and the same seed, and rerunning that single index reproduces the exact scenario for debugging without needing to capture any random state.

The oracle in `StrategyOracle` deliberately shares no code with `TollStrategyEngine`, it recomputes the usage day count, the toll totals, and the congestion totals directly from the input lists and transcribes every cost formula from the design doc inline, so a bug in the engine cannot be mirrored in the oracle. Reading `docs/design/toll-strategy-engine.md` section 7 alongside `StrategyOracle.java` is the fastest way to audit that independence by eye.

There is no JaCoCo or other coverage plugin wired into this build yet, so branch coverage on the strategy package is verified by manual review against the engine's source rather than a tool gate, every fork in the cost formulas, the tie-break comparator, and the explanation factor rules in `TollStrategyEngine` has at least one dedicated assertion in `TollStrategyEngineTest`.

## Data providers, Phase 4

`com.truecost.provider` holds every external data connector, rental, fuel, and hotel, plus the shared HTTP transport and partial result contract they all use. Nothing here needs the compose stack, Postgres, Redis, Kafka, or OSRM, up. `SyntheticRentalProvider` reads its calibration CSV straight off disk, `FuelCostService` and the real connectors talk to WireMock in tests instead of the real network, and `make test` or a plain `./mvnw test` covers all of it alongside every earlier phase's tests. `FuelCostService` does need `VehicleClassRepository`, which needs Postgres, so its Spring wiring is exercised by the full application context tests, but its own unit test stubs that repository out and needs nothing running.

Run every provider test:

```
./mvnw test -Dtest='com.truecost.provider.**' -Dsurefire.failIfNoSpecifiedTests=false
```

The determinism acceptance criterion, two calls with an identical `RentalQuoteRequest` return equal quote lists:

```
./mvnw test -Dtest=SyntheticRentalProviderTest -Dsurefire.failIfNoSpecifiedTests=false
```

`SyntheticRentalProvider` seeds a `java.util.Random` per company from a CRC32 hash of the pickup location, dates, car class, and company code, so the same request always lands on the same seed and the same output, no matter how many times or on how many JVMs it runs. The base rates, per company multipliers, and flat fees it reads from `data/seeds/rental_calibration.csv` are provisional placeholder figures, not researched real prices, see the header comment in that file and the matching section in `data/seeds/SOURCES.md`. Replacing them with real collected quotes is a Phase 9 open item for the user and needs no code change, only new numbers in the same CSV shape.

The shared transport and circuit breaker behavior, every real connector goes through `ResilientHttpClient`:

```
./mvnw test -Dtest=ResilientHttpClientTest -Dsurefire.failIfNoSpecifiedTests=false
```

This proves a success returns Present, a non 200 status and a body the caller's parser rejects both return Absent without throwing, a connection failure is retried exactly once and can still succeed, and repeated failures past the configured threshold flip the named `CircuitBreaker` to `OPEN`, after which further calls return Absent immediately rather than reaching the network. Each provider gets its own breaker keyed by the provider name it passes to `ResilientHttpClient.get` or `.post`, so one upstream tripping open never affects another's state, observable per name through `CircuitBreakerRegistry.circuitBreaker(name).getState()` and, once the app is running, through the `resilience4j-spring-boot3` starter's actuator and Micrometer integration.

Per connector WireMock contract tests, each covers a successful parse plus the required failure shapes, a timeout, an HTTP 429, and a malformed body, and each asserts every failure path returns `ProviderResult.Absent` with a reason string rather than throwing:

```
./mvnw test -Dtest=RapidApiRentalProviderTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -Dtest=FuelCostServiceTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -Dtest=AmadeusHotelProviderTest -Dsurefire.failIfNoSpecifiedTests=false
```

`AmadeusHotelProviderTest` additionally stubs the OAuth2 client credentials token endpoint and covers a failed token exchange separately from a failed search, since `AmadeusHotelProvider` caches the access token in memory and only re-requests it once the cached token is within 60 seconds of its published expiry.

Every real connector is disabled by default in `src/main/resources/application.yml`, `truecost.providers.rental.rapidapi.enabled`, `truecost.providers.fuel.eia.enabled`, and `truecost.providers.hotel.amadeus.enabled` all default to `false`, so a plain `make run` never dials out and never needs a key. Flipping one on for real also needs its API key or OAuth credentials, `RAPIDAPI_KEY`, `EIA_API_KEY`, `AMADEUS_CLIENT_ID`, and `AMADEUS_CLIENT_SECRET`, read from the environment at startup and empty by default, set them alongside the matching `enabled: true` override in an environment specific properties file or as `-D` overrides passed to `make run`. Signing up for these free keys is PLAN.md's Phase 4 open item for the user, RapidAPI, EIA, and Amadeus Self-Service.

`FuelCostService` falls back to a static price per gallon, `truecost.providers.fuel.fallback-price-per-gallon`, whenever the EIA connector is disabled or a live fetch fails. Phase 5 moved the successful live price out of a private in memory field and behind the shared two tier cache under `fuel:v1:price:{region}` with a 24 hour logical TTL, since the underlying EIA series only updates weekly, so the one upstream fetch guarantee now holds across instances rather than only inside one JVM. `FuelEstimate.priceIsFallback()` tells the caller which path produced the number. Vehicle MPG always comes from `vehicle_class.default_mpg`, there is no per make or model lookup yet, that is a deliberately deferred `ponytail:` comment on `FuelCostService` itself, to revisit once a specific vehicle model enters the request pipeline rather than only a car class code.

## Aggregation API and caching, Phase 5

`POST /api/v1/trips/plan` is the one public endpoint, in `com.truecost.api`. It takes both endpoint coordinates and provider location codes, the departure and return instants, the personal E-ZPass flag, and an optional car class, and returns every rental option priced with its toll program fee, toll total, congestion charge, fuel, and hotel, the chosen toll strategy with its explanation, a ranked recommendation, and per field freshness. `com.truecost.aggregate.TripPlanner` fans out every route, tolls, rental, fuel, and hotel load on the shared virtual thread executor through `com.truecost.cache.TwoTierCache`, Caffeine L1 plus Redis L2, with a keyed singleflight and stale while revalidate. The full design is `docs/design/aggregation-caching-design.md`.

A trip is two independent legs, outbound at `departureAt` and return at `returnAt`, each with its own `route:v1` and `tolls:v1` cache entry, combined into one crossing list and one deduplicated congestion day list only when the toll strategy engine runs. Every cache key carries a `v1` schema segment, so `redis-cli --scan --pattern 'route:v1:*'` and the same for `tolls:v1`, `rental:v1`, `fuel:v1`, and `hotel:v1` inspect one data class at a time.

A ready-to-send request body, Manhattan pickup to Philadelphia, matching the values proven in `TripPlanSnapshotTest`, lives at `eval/golden/sample-request.json`. Its dates are a fixed weekend and need bumping to a future one before reuse:

```
curl -s localhost:8080/api/v1/trips/plan -H 'Content-Type: application/json' -d @eval/golden/sample-request.json | jq
```

A demo UI is served at `http://localhost:8080/` once the app is up, `src/main/resources/static/`, no separate process or port, Spring Boot serves it directly alongside the API. It has a trip form, a toggle between automatic synthetic pricing and manually entered rental rates per company, and a live panel of cache hit rate, singleflight coalescing, and toll strategy mix pulled from the Actuator metrics endpoints below.

There is no free live rental pricing API, so `rentalRateOverrides` on the request lets a caller supply a real rate per company instead of relying on the synthetic model, the request field, and the demo UI's manual mode, both exist for this reason. Omit the field, or send it null or empty, for the existing automatic path. When present, `carClass` must be a specific class, not blank, since a person prices one car at a time:

```
curl -s localhost:8080/api/v1/trips/plan -H 'Content-Type: application/json' -d '{
  "originLat": 40.7505, "originLng": -73.9934, "pickupLocationCode": "EWR",
  "destLat": 39.9500, "destLng": -75.1600, "destinationCode": "PHL",
  "departureAt": "2026-08-07T22:00:00Z", "returnAt": "2026-08-09T18:00:00Z",
  "hasPersonalEzpass": false, "carClass": "MIDSIZE",
  "rentalRateOverrides": [
    {"companyCode": "HERTZ", "companyName": "Hertz", "totalCents": 21500},
    {"companyCode": "AVIS", "companyName": "Avis", "totalCents": 19900}
  ]
}' | jq
```

Options built from `rentalRateOverrides` carry `"rentalSource": "USER_SUPPLIED"` in their provenance rather than `SYNTHETIC` or `RAPIDAPI`, so a manually entered rate is never confused with a fetched one downstream.

The whole endpoint, cache, and aggregator layer is covered by Testcontainers backed tests that need Docker but not the compose stack up, `make test` or a plain `./mvnw test` runs them alongside every earlier phase. The four PLAN Phase 5 acceptance tests are these.

The keyed singleflight, fifty concurrent identical loads collapse to exactly one upstream fetch per key, proven both at the cache unit level, in process and across two instances sharing one Redis, and end to end through HTTP against stubbed OSRM, EIA, and RapidAPI upstreams:

```
./mvnw test -Dtest=TwoTierCacheConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -Dtest=TripPlanSingleflightTest -Dsurefire.failIfNoSpecifiedTests=false
```

Stale while revalidate, a read past the logical TTL but inside twice it returns the stale value with a `STALE` flag and triggers one background refresh that lands a fresh entry within the second TTL span:

```
./mvnw test -Dtest=TwoTierCacheStaleTest -Dsurefire.failIfNoSpecifiedTests=false
```

The full response snapshot, one golden Manhattan to Philadelphia weekend trip through the New Jersey Turnpike, with OSRM stubbed to a fixed polyline and the synthetic rental and hotel providers plus the static fuel fallback keeping every figure deterministic, pinned against `src/test/resources/snapshots/trip-plan-golden.json`:

```
./mvnw test -Dtest=TripPlanSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false
```

Regenerate the golden file only when a pricing, ranking, or response tree change is intended, by printing the actual response and replacing the resource, never to make a red test pass without understanding the diff.

The k6 load smoke, the two throughput thresholds, warm cache p95 under 300 milliseconds at 200 requests per second and the cold fan-out path under 3 seconds. Unlike the tests above this one needs a running stack, `make up`, a prepared OSRM per `make osrm`, and `make run` in another terminal, since it drives the live endpoint against real routing:

```
make k6-smoke
```

`eval/k6/trip-plan-smoke.js` runs a cold scenario that jitters the endpoints every iteration so each request is a genuine geohash-6 cache miss under the 3 second budget, then a 200 request per second warm scenario against one fixed trip that `setup` has already warmed into Caffeine, with a `warm_duration` p95 under 300 milliseconds threshold. Override the target with `TRUECOST_BASE_URL`, it defaults to `http://localhost:8080`.

### Demo load

`make demo-load` drives repeated traffic against four fixed trip bodies plus a mid run burst of concurrent identical requests, so the cache hit rate and singleflight coalesced counters on the UI deck climb live while it runs, proving the caching and coalescing performance story in front of an audience. It carries no failing thresholds and always exits zero, so it is safe to leave running on stage. Needs the containerized stack up, `make up-all`, and k6 installed, `brew install k6`:

```
make demo-load
```

## Kafka Streams prefetcher, Phase 6

Phase 6 is landed. The Avro event schemas under src/main/avro, the topic and stream configuration, the hot route detection topology, the Apicurio Avro serde wiring, the API event producer, the prefetch consumer, the quote snapshot sink, the separate PrefetcherApplication deployable, and its Docker image are all built and tested. The Kubernetes manifests for the prefetcher are Phase 8.

The build produces two boot jars from one source tree, target/truecost-0.0.1-SNAPSHOT.jar for the API and target/truecost-0.0.1-SNAPSHOT-prefetcher.jar for the prefetcher, through two repackage executions of the spring-boot-maven-plugin with distinct main classes. The API deployable, TrueCostApplication, produces SearchRequested and QuoteSnapshot when truecost.events.enabled is true, and never runs a topology, it excludes com.truecost.stream from its component scan. The prefetcher deployable, PrefetcherApplication, runs the topology, the prefetch consumer, and the quote snapshot sink, and warms caches by reusing TripPlanner. Run the prefetcher on the host against the compose stack with:

```
make prefetch
```

Build the two images from deploy/Dockerfile, a multi stage build whose api and prefetcher targets share one compile:

```
make images
```

Both images build clean, and the API image was run to confirm it boots, the seed CSVs ship in it and Spring starts and proceeds normally until it needs Postgres, so the container is correct, it just needs its dependencies. The image is about 477 megabytes on a JRE base.

The serde round trip against a real Apicurio registry is proven by EventSerdeIntegrationTest, and the full event flow, twenty searches into a warmed Redis entry and a captured quote_snapshot row with no user request, is proven by PrefetcherFlowIntegrationTest, both Testcontainers backed so they run in the normal suite:

```
./mvnw test -Dtest=EventSerdeIntegrationTest,PrefetcherFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

PrefetcherFlowIntegrationTest is the PLAN Phase 6 acceptance test for criteria two and three, a Redis entry refreshed with no user request and snapshots appearing in Postgres. Criterion one, windowing, threshold, and dedup, is HotRouteTopologyTest.

The topology is the core of the phase, a fifteen minute hopping window advancing every five minutes that counts searches per route key and date bucket and emits one HotRouteSignal per window that reaches the hotness threshold. It is covered by TopologyTestDriver tests that need no broker, so they run in the normal suite and cover windowing, the threshold, and dedup of repeated hot emissions:

```
./mvnw test -Dtest=HotRouteTopologyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Dedup uses suppress until the window closes rather than a filter on the exact threshold count, because a filter on the exact count is not safe under Kafka Streams record caching, which can coalesce updates and skip past the threshold value. Suppress forwards exactly one final aggregate per window, which is caching safe and gives one emission per window rather than one per counted search.

Observe emissions and consumer lag, PLAN Phase 6 acceptance criterion four. These need the compose stack up, the API running with events on, and the prefetcher running, so they are not exercised in the sandbox where OSRM and a full broker stack are unavailable, run them once locally against a live stack. Tail the hot routes topic, the string route key is human readable while the Avro value is binary:

```
kcat -b localhost:9092 -t hot-routes -C -o beginning -f 'key=%k offset=%o\n'
```

Check the prefetch consumer group lag, the group id the prefetch consumer joins is hot-route-prefetch and the snapshot sink is quote-snapshot-sink:

```
docker compose -f deploy/compose.yaml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group hot-route-prefetch
```

Inspect the captured snapshots directly in Postgres, the accuracy analysis source of truth:

```sql
select route_key, company, car_class, provider, total_rental_cents, stale, fetched_at
from quote_snapshot order by fetched_at desc limit 20;
```

## Observability, Phase 7

Metrics are landed and verified in the suite, tracing is wired, and the Prometheus, Grafana, and Tempo stack is provisioned in compose. Full design in docs/design/observability-design.md. The verification that Grafana renders the dashboards, that the circuit breaker alert fires, and that a trace waterfall shows the slow dependency needs the live stack and a k6 run, so those are run once locally, not in the sandbox.

Every service exposes Prometheus metrics at the actuator endpoint, scraped by the compose Prometheus every five seconds:

```
curl -s localhost:8080/actuator/prometheus | grep -E 'truecost_|http_server_requests'
```

`make up` now also starts Prometheus on 9090, Tempo on 3200 with an OTLP receiver on 4318, and Grafana on 3000 with anonymous admin. Grafana provisions one Prometheus and one Tempo datasource and four dashboards, API overview, providers, cache effectiveness, and Kafka pipeline, from deploy/grafana. Open Grafana:

```
open http://localhost:3000
```

Run the API and the prefetcher on the host so Prometheus scrapes them over host.docker.internal, the prefetcher on 8082 to avoid the API's 8080:

```
make run
make prefetch
```

The business metric that splits by chosen toll strategy, proven by the snapshot test asserting it increments across strategies, is `truecost_strategy_chosen_total`:

```
curl -s localhost:8080/actuator/prometheus | grep truecost_strategy_chosen_total
```

Tracing exports over OTLP to Tempo. Dev samples every trace, the default profile samples none so tests need no backend. Trace context propagates through Kafka message headers, so one trace spans the API request, the broker hop, and the prefetcher. Log lines carry the traceId and spanId, in the dev pattern as a bracketed pair and in the prod JSON as fields, so Grafana pivots from a Tempo span to its logs. The one remaining tracing step, documented in the design, is wrapping the two java.net.http outbound clients, RouteClient and ResilientHttpClient, in Micrometer Observations so the OSRM and provider calls appear as their own spans, Redis, Postgres, and Kafka already auto instrument.

Prometheus alert rules live in deploy/prometheus/alerts.yml, the p95 latency, circuit breaker open, and cache hit rate collapse alerts the phase acceptance calls for. Check them loaded:

```
curl -s localhost:9090/api/v1/rules | jq '.data.groups[].rules[].name'
```

## Kubernetes, Phase 8

The API and the prefetcher deploy to a kind cluster as two separate Deployments, each with its own readiness and liveness probes on Actuator, resource requests and limits, and its own HPA, sharing one ConfigMap and one Secret. Postgres, Redis, Kafka, and Apicurio run in cluster, and Prometheus and Grafana in cluster scrape both Deployments through pod annotations. The manifests are kustomize under deploy/k8s/base, rendered and structurally validated with kubectl kustomize. The end to end cluster run needs kind and a running Docker, which the sandbox does not have, so the script and manifests are authored and validated but the live cluster run is done once locally.

From zero to a served request:

```
make k8s-up
```

This creates the kind cluster, builds and loads the two images, installs metrics-server for the HPAs, applies the manifests, and waits for the rollouts. Reach the API:

```
kubectl -n truecost port-forward svc/api 8080:8080
curl -s localhost:8080/actuator/health | jq
```

Tear it down:

```
make k8s-down
```

Pod logs, the API and prefetcher are labeled app:

```
kubectl -n truecost logs -l app=api --tail=100 -f
kubectl -n truecost logs -l app=prefetcher --tail=100 -f
```

Diagnose a probe failure. Watch the pods, a readiness probe failing shows as a pod stuck not ready with restarts climbing when liveness also fails, then describe it for the probe events and read the container log for why the health endpoint is down:

```
kubectl -n truecost get pods -w
kubectl -n truecost describe pod -l app=api
kubectl -n truecost logs -l app=api --previous
```

A probe failure is deliberately induced to verify these steps by pointing the readiness probe at a wrong path or port in api.yaml and reapplying, the pod then never turns ready and the Service drops it from its endpoints, kubectl get endpoints api shows it removed, and reverting the probe restores it.

Watch the HPA scale under load. The in cluster k6 load Job drives the API's CPU from inside the cluster, so no port-forward or host k6 is needed, then watch the replicas move between one and three and fall back after the Job finishes:

```
make k8s-loadtest
kubectl -n truecost get hpa api -w
kubectl -n truecost get pods -l app=api -w
```

A rolling restart during that load completes with zero failed requests. The API Deployment sets a maxUnavailable 0 and maxSurge 1 rolling update, so the new pod becomes ready before the old one is removed, and it never drops below one ready replica even at a single replica. A preStop drain and Spring Boot graceful shutdown let in-flight plans finish after the pod leaves the Service endpoints, so no request in flight is cut off:

```
kubectl -n truecost rollout restart deployment/api
```

Startup, liveness, and readiness. A startup probe gives the JVM up to five minutes to boot before liveness and readiness take over on a tight interval, so a slow cold start under load is never killed as unhealthy, and the readiness probe alone gates traffic. The prefetcher uses the opposite rollout trade, maxUnavailable 1 and maxSurge 0, one consumer group rebalance rather than two, since a brief gap in its background cache warming is harmless. A PodDisruptionBudget keeps at least one API pod through a node drain.

k9s gives a live terminal view of the namespace, pods, logs, and resource use in one place:

```
k9s -n truecost
```
