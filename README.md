# TrueCost

A real time, all in cost planner for weekend car rental trips out of New York City. A user enters
an origin, a destination, travel dates, whether they own a personal E-ZPass, and the base rate each
rental company quoted them. TrueCost computes the true total per option including that base rate,
toll program fees, congestion pricing, route tolls, fuel, and hotel, ranks every option, and
recommends the cheapest.

Base rates are user supplied because no free licensed rental pricing API exists, and the available
alternatives were an unofficial third party connector or scraping, neither of which is defensible
for a thesis accuracy claim. Every other input, route tolls, congestion pricing, toll program terms,
fuel, and hotel, is fetched or computed by the system from a real source.

The core engineering problem is deciding, per rental option, whether the user's own E-ZPass, the
rental company's per crossing fee, or the company's unlimited toll plan is cheapest for the specific
route and rental duration. This is a master's applied project, evaluated on the accuracy of the
computed totals, response time under parallel fetching with caching, and the correctness of the toll
strategy recommendation across varied routes and trip lengths.

## Architecture

TrueCost is a Spring Boot application that deploys as two independently scaled processes talking only
through Kafka.

- The API values a trip synchronously. It fans out on virtual threads through a two tier cache,
  Caffeine in process and Redis across instances, with a keyed singleflight and stale while
  revalidate, prices both trip legs, runs the toll strategy engine per option, and ranks the
  results.
- The prefetcher is a Kafka Streams worker. It counts search events in a hopping window, detects
  hot routes, and warms the caches ahead of the next user, and it sinks every fetched rental quote
  into Postgres for the accuracy analysis.

```
com.truecost
  api            the POST /api/v1/trips/plan endpoint, api.dto holds the request and response records
  aggregate      TripPlanner, the fan-out orchestrator, and the leg combination
  cache          the two tier cache, singleflight, Redis lock, and geohash keying
  toll           crossing detection, timeline pricing, and toll.strategy, the strategy engine
  route          the OSRM routing client
  provider       the rental, fuel, and hotel connectors and the shared resilient HTTP transport
  persist        the JDBC repositories
  events         the Avro events and the API side publisher
  stream         the prefetcher, stream.topology, stream.consumer, and stream.config
```

The design decisions behind each phase live in `docs/design`, and `docs/RUNBOOK.md` catalogs every
command to run, inspect, and debug the stack.

## Tech stack

Java 21 on Spring Boot 3, Kafka with Kafka Streams, Avro with a self hosted Apicurio Registry,
PostgreSQL, Redis, a self hosted OSRM routing engine, Flyway migrations, Prometheus, Grafana, and
Tempo for metrics and tracing, Docker Compose for local development, Kubernetes for deployment
practice, and k6 for load testing. The build is Maven, the single command surface is `make`.

## Running it

The fastest proof the project works needs only a JDK 21 and a running Docker daemon, and takes
about a minute warm:

```
./mvnw -B verify   # builds both deployables and runs all 94 tests against real containers
```

The tests start their own Postgres, Redis, Kafka, and Apicurio through Testcontainers and stub the
outbound providers with WireMock, so no stack and no credentials are needed.

To run the whole system:

```
make osrm        # one time, download and prepare the OSRM routing data, this is the long step
make up-all      # build the images and start everything, infra plus seed, API, and prefetcher
make demo-check  # confirm the API, OSRM, and a real plan request are all healthy
```

The first `make up-all` is slow, budget half an hour, because the Dockerfile resolves the whole
dependency tree inside the build container.

`docs/SETUP.md` is the full clean clone walkthrough, including what `make osrm` costs and what to
do when it runs out of memory. To run the two processes on the host against a containerized
infrastructure instead, use `make up`, then `make seed`, `make run`, and `make prefetch`.

Then a plan request:

```
curl -s localhost:8080/api/v1/trips/plan -H 'Content-Type: application/json' -d '{
  "originLat": 40.7505, "originLng": -73.9934, "pickupLocationCode": "EWR",
  "destLat": 39.95, "destLng": -75.16, "destinationCode": "PHL",
  "departureAt": "2026-08-14T22:00:00Z", "returnAt": "2026-08-16T18:00:00Z",
  "hasPersonalEzpass": false, "carClass": "MIDSIZE"
}' | jq
```

Grafana is on `localhost:3000`, Prometheus on `9090`. See `docs/RUNBOOK.md` for the full command set.

## Testing

```
make test      # unit and Testcontainers integration tests
make smoke     # the baseline check, build plus full suite plus the live stack probe
```

The suite is self contained, it spins up its own Postgres, Redis, Kafka, and Apicurio through
Testcontainers and stubs the external HTTP providers with WireMock, so it needs Docker but not the
compose stack. 94 tests across 24 classes. The acceptance tests pin the toll strategy engine
against an independent oracle, pin a golden trip response, prove the singleflight and stale while
revalidate guarantees, and prove the prefetcher warms the cache with no user request.

One caveat worth knowing before you trust the property test. `StrategyOracle` is written
independently of the engine except for the `NO_ARRANGEMENT` gate, which it copies, so the 10,000
case agreement shows the engine matches its specification rather than that the specification is
right. Tracked in `docs/KNOWN_ISSUES.md`.

Captured output from real runs is in `docs/evidence/`, a full build and test run, a live stack
smoke, and a k6 load run against both latency thresholds.

## Status

Baseline `v0.1.0-baseline`, tagged 4 October 2026.

Phases 0 through 8 are built, the domain and reference data, routing and toll detection, the toll
strategy engine, the data providers, the aggregation API and cache, the Kafka Streams prefetcher,
observability, and the Kubernetes packaging. CI runs the full suite, a kustomize render, and both
image builds on every push.

Not built yet. The accuracy evaluation runner, Phase 9, so `make eval` is a stub and the headline
accuracy numbers have no evidence path. FR14 out of bounds rejection, so a route leaving the
seeded toll coverage still returns a biased ranking rather than an error. Both are the top items
in `docs/RISK_LOG.md`.

The full phase plan is in `PLAN.md`, what actually shipped is in `CHANGELOG.md`, and where they
disagree the changelog is right.

## Engineering documentation

| Document | What it is for |
| --- | --- |
| `docs/SETUP.md` | clean clone setup, prerequisites, and the two levels of running it |
| `.env.example` | every environment variable, what it defaults to, and what happens unset |
| `docs/ARCHITECTURE.md` | how the repository maps to the architecture, package by package |
| `docs/API.md` | the endpoint contract with real payloads, and the documented stub behaviour |
| `docs/RUNBOOK.md` | per subsystem commands to run, inspect, and debug |
| `docs/ENGINEERING.md` | layout, naming, branch strategy, and artifact storage conventions |
| `docs/RISK_LOG.md` | the risk and issue register with severity, owner, and status |
| `docs/KNOWN_ISSUES.md` | what is currently broken or limited, with workarounds |
| `CHANGELOG.md` | what shipped and when |
| `docs/AI_USAGE_LOG.md` | provenance of assistant supported work |
| `docs/design/` | one design record per phase, written before the phase was built |
| `docs/sprint/` | sprint check-in reflections |
